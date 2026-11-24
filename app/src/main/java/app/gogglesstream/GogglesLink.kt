package app.gogglesstream

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TCP wire protocol, reverse-engineered from VisorSim.exe (visor_wgc_nvenc):
 *
 *  1. Connect to host:port (goggles: 192.168.60.2 on the USB-RNDIS network).
 *  2. Send a 16-byte little-endian hello: "VMS2", width, height, fps.
 *  3. Receiver answers with one byte 'R' (0x52) when its decoder is ready.
 *  4. Then repeatedly: u32 LE length + H.264 Annex-B access unit (SPS/PPS before each IDR).
 *     If nothing was sent for >= 1 s, send a 4-byte zero length as a keepalive.
 */
class GogglesLink(private val cfg: StreamConfig) {
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var inp: InputStream? = null

    fun connect() {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(cfg.host, cfg.port), 5_000)
        s.soTimeout = 15_000
        socket = s
        out = s.getOutputStream()
        inp = s.getInputStream()

        val hello = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        hello.put('V'.code.toByte()).put('M'.code.toByte()).put('S'.code.toByte()).put('2'.code.toByte())
        hello.putInt(cfg.width).putInt(cfg.height).putInt(cfg.fps)
        out!!.write(hello.array())
        out!!.flush()

        val r = inp!!.read()
        if (r < 0) throw java.io.IOException("receiver closed during hello")
        if (r != 'R'.code) throw java.io.IOException("unexpected ready reply 0x%02x".format(r))
        s.soTimeout = 0
    }

    fun sendFrame(data: ByteArray) {
        val buf = ByteBuffer.allocate(4 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(data.size).put(data)
        out!!.write(buf.array())
    }

    fun sendKeepalive() {
        out!!.write(byteArrayOf(0, 0, 0, 0))
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
    }
}
