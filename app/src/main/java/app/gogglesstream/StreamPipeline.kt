package app.gogglesstream

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Entire-screen capture (MediaProjection) -> GL letterbox to 1920x1080 -> H.264 (MediaCodec)
 * -> TCP to the goggles receiver.
 */
class StreamPipeline(
    private val ctx: Context,
    private val projection: MediaProjection,
    private val cfg: StreamConfig,
    private val status: (String) -> Unit,
    private val onEnded: (String) -> Unit,
) {
    private class Frame(val data: ByteArray, val key: Boolean)

    private val running = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<Frame>()
    private val sentBytes = AtomicLong()
    private val sentFrames = AtomicLong()
    private val dropped = AtomicLong()

    private var link: GogglesLink? = null
    private var codec: MediaCodec? = null
    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var captureSurface: Surface? = null
    private var inputSurface: Surface? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texId = 0
    private var aPos = 0
    private var aTex = 0
    private var uMat = 0

    @Volatile private var srcW = 0
    @Volatile private var srcH = 0
    private var dpi = 320
    private val texMatrix = FloatArray(16)

    private val workers = mutableListOf<Thread>()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val t = Thread({ runSetupThenSend() }, "goggles-setup").also { it.start() }
        workers += t
    }

    fun stop(reason: String = "Stopped") {
        if (!running.compareAndSet(true, false)) return
        workers.forEach { it.interrupt() }
        link?.close()
        glHandler?.post { releaseGl() }
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        try { surfaceTexture?.release() } catch (_: Exception) {}
        try { captureSurface?.release() } catch (_: Exception) {}
        try { inputSurface?.release() } catch (_: Exception) {}
        try { projection.stop() } catch (_: Exception) {}
        glThread?.quitSafely()
        onEnded(reason)
    }

    // ---------------------------------------------------------------- setup + sender

    private fun runSetupThenSend() {
        try {
            status("Connecting to ${cfg.host}:${cfg.port}…")
            val l = GogglesLink(cfg)
            link = l
            l.connect()
            status("Receiver ready. Starting capture…")

            readScreenSize()
            startEncoder()
            startGlAndCapture()

            val drain = Thread({ drainEncoder() }, "goggles-encoder").also { it.start() }
            workers += drain
            val stats = Thread({ statsLoop() }, "goggles-stats").also { it.start() }
            workers += stats

            senderLoop(l)
        } catch (e: InterruptedException) {
            // stopping
        } catch (e: Exception) {
            if (running.get()) stop("Failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun senderLoop(l: GogglesLink) {
        try {
            while (running.get()) {
                val f = queue.poll(1000, TimeUnit.MILLISECONDS)
                if (f == null) {
                    l.sendKeepalive()
                } else {
                    l.sendFrame(f.data)
                    sentBytes.addAndGet(f.data.size.toLong() + 4)
                    sentFrames.incrementAndGet()
                }
            }
        } catch (e: InterruptedException) {
        } catch (e: Exception) {
            if (running.get()) stop("Goggles connection lost: ${e.message}")
        }
    }

    private fun memLine(): String {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            "MEM avail=${mi.availMem shr 20}MB total=${mi.totalMem shr 20}MB threshold=${mi.threshold shr 20}MB " +
                "low=${mi.lowMemory} appPss=${android.os.Debug.getPss() / 1024}MB queue=${queue.size} dropped=${dropped.get()} " +
                (link?.diag() ?: "")
        } catch (e: Exception) { "MEM unavailable: ${e.message}" }
    }

    private fun statsLoop() {
        var tick = 0
        var lastBytes = 0L
        var lastFrames = 0L
        try {
            while (running.get()) {
                Thread.sleep(1000)
                val b = sentBytes.get()
                val fr = sentFrames.get()
                val mbps = (b - lastBytes) * 8 / 1_000_000.0
                status("Streaming ${cfg.width}x${cfg.height} · ${fr - lastFrames} fps · " +
                    "%.1f Mb/s · dropped ${dropped.get()}".format(mbps))
                lastBytes = b; lastFrames = fr
                if (++tick % 2 == 0) LogApp.event(memLine())
            }
        } catch (_: InterruptedException) {}
    }

    // ---------------------------------------------------------------- encoder

    private fun startEncoder() {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, cfg.width, cfg.height)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, cfg.bitrateMbps * 1_000_000)
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, cfg.fps)
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        fmt.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        fmt.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
        fmt.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        fmt.setInteger(MediaFormat.KEY_LATENCY, 1)
        // 0 = realtime, 1 = best effort. Best effort lets other apps' video (DJI Fly) win when the chip is busy.
        fmt.setInteger(MediaFormat.KEY_PRIORITY, if (cfg.lowImpact) 1 else 0)
        fmt.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        // Repeat the last frame when the screen is static so the decoder keeps getting data.
        fmt.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / cfg.fps)

        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Some encoders reject High profile / CBR / latency keys: retry with the basics.
            c.reset()
            val basic = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, cfg.width, cfg.height)
            basic.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            basic.setInteger(MediaFormat.KEY_BIT_RATE, cfg.bitrateMbps * 1_000_000)
            basic.setInteger(MediaFormat.KEY_FRAME_RATE, cfg.fps)
            basic.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            c.configure(basic, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        inputSurface = c.createInputSurface()
        c.start()
        codec = c
    }

    private fun drainEncoder() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        var config: ByteArray? = null
        try {
            while (running.get()) {
                val idx = c.dequeueOutputBuffer(info, 100_000)
                if (idx < 0) continue
                val buf: ByteBuffer? = c.getOutputBuffer(idx)
                val bytes = if (buf != null) {
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    ByteArray(info.size).also { buf.get(it) }
                } else ByteArray(0)
                c.releaseOutputBuffer(idx, false)

                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    config = bytes
                    continue
                }
                if (bytes.isEmpty()) continue
                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                val payload = if (key && config != null && !startsWithSps(bytes)) config + bytes else bytes
                enqueue(Frame(payload, key), c)
            }
        } catch (e: Exception) {
            if (running.get()) stop("Encoder error: ${e.message}")
        }
    }

    /** Keep latency bounded: if the link is slow, drop delta frames until the next keyframe. */
    private var waitingForKey = false
    private fun enqueue(f: Frame, c: MediaCodec) {
        if (waitingForKey) {
            if (!f.key) { dropped.incrementAndGet(); return }
            waitingForKey = false
        }
        if (queue.size >= 4) {
            queue.clear()
            dropped.addAndGet(4)
            if (!f.key) {
                waitingForKey = true
                val p = android.os.Bundle()
                p.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                try { c.setParameters(p) } catch (_: Exception) {}
                dropped.incrementAndGet()
                return
            }
        }
        queue.offer(f)
    }

    private fun startsWithSps(b: ByteArray): Boolean {
        if (b.size > 5 && b[0].toInt() == 0 && b[1].toInt() == 0) {
            if (b[2].toInt() == 1) return (b[3].toInt() and 0x1f) == 7
            if (b[2].toInt() == 0 && b[3].toInt() == 1) return (b[4].toInt() and 0x1f) == 7
        }
        return false
    }

    // ---------------------------------------------------------------- GL + capture

    private fun readScreenSize() {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            srcW = b.width(); srcH = b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            srcW = dm.widthPixels; srcH = dm.heightPixels
        }
        dpi = ctx.resources.displayMetrics.densityDpi
    }

    private fun startGlAndCapture() {
        val t = HandlerThread("goggles-gl").also { it.start() }
        glThread = t
        val h = Handler(t.looper)
        glHandler = h

        val ready = java.util.concurrent.CountDownLatch(1)
        var err: Exception? = null
        h.post {
            try {
                initGl(inputSurface!!)
                val st = SurfaceTexture(texId)
                st.setDefaultBufferSize(srcW, srcH)
                st.setOnFrameAvailableListener({ drawFrame() }, h)
                surfaceTexture = st
                captureSurface = Surface(st)
            } catch (e: Exception) { err = e }
            ready.countDown()
        }
        ready.await()
        err?.let { throw it }

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stop("Screen capture stopped") }
            override fun onCapturedContentResize(width: Int, height: Int) {
                srcW = width; srcH = height
                surfaceTexture?.setDefaultBufferSize(width, height)
                virtualDisplay?.resize(width, height, dpi)
            }
        }, Handler(android.os.Looper.getMainLooper()))

        virtualDisplay = projection.createVirtualDisplay(
            "GogglesScreenStream", srcW, srcH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            captureSurface, null, null,
        )
    }

    private fun initGl(target: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, v, 0, v, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142 /* EGL_RECORDABLE_ANDROID */, 1,
            EGL14.EGL_NONE,
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, cfgs, 0, 1, n, 0)
        check(n[0] > 0) { "no EGL config" }
        eglContext = EGL14.eglCreateContext(
            eglDisplay, cfgs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, cfgs[0], target, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }

        val vs = """
            attribute vec4 aPos; attribute vec4 aTex; uniform mat4 uMat; varying vec2 vTex;
            void main(){ gl_Position = aPos; vTex = (uMat * aTex).xy; }
        """.trimIndent()
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float; varying vec2 vTex; uniform samplerExternalOES sTex;
            void main(){ gl_FragColor = texture2D(sTex, vTex); }
        """.trimIndent()
        program = linkProgram(vs, fs)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uMat = GLES20.glGetUniformLocation(program, "uMat")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private val quad: ByteBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).also {
        // x, y, u, v  (triangle strip)
        it.asFloatBuffer().put(floatArrayOf(
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f,
        ))
    }

    private var lastTs = 0L
    private val minIntervalNs: Long get() = (1_000_000_000L / cfg.fps.coerceAtLeast(1)) * 9 / 10

    private fun drawFrame() {
        if (!running.get()) return
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
            // Cap to the chosen FPS (the screen delivers up to 60+): fewer frames = less GPU/encoder/RAM pressure.
            val ts = st.timestamp
            if (lastTs != 0L && ts - lastTs < minIntervalNs) return
            lastTs = ts
            st.getTransformMatrix(texMatrix)

            GLES20.glViewport(0, 0, cfg.width, cfg.height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // Letterbox the screen into the fixed 16:9 output.
            val sw = srcW.coerceAtLeast(1); val sh = srcH.coerceAtLeast(1)
            val scale = minOf(cfg.width.toFloat() / sw, cfg.height.toFloat() / sh)
            val vw = (sw * scale).toInt(); val vh = (sh * scale).toInt()
            GLES20.glViewport((cfg.width - vw) / 2, (cfg.height - vh) / 2, vw, vh)

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniformMatrix4fv(uMat, 1, false, texMatrix, 0)
            quad.position(0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
            quad.position(8)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, st.timestamp)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } catch (e: Exception) {
            if (running.get()) stop("Render error: ${e.message}")
        }
    }

    private fun releaseGl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun linkProgram(vsSrc: String, fsSrc: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vsSrc))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fsSrc))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }
}
