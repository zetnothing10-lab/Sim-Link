package app.gogglesstream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class ScreenStreamService : Service() {

    companion object {
        const val ACTION_START = "app.gogglesstream.START"
        const val ACTION_STOP = "app.gogglesstream.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_FPS = "fps"
        const val EXTRA_MBPS = "mbps"
        private const val CHANNEL = "stream"
        private const val NOTIF_ID = 1

        @Volatile var running = false
        @Volatile var lastStatus = "Idle"
        var listener: ((String, Boolean) -> Unit)? = null
        private val main = Handler(Looper.getMainLooper())

        fun publish(msg: String, active: Boolean) {
            lastStatus = msg
            LogApp.event("STREAM: $msg")
            main.post { listener?.invoke(msg, active) }
        }
    }

    private var pipeline: StreamPipeline? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { pipeline?.stop("Stopped"); stopSelf() }
            ACTION_START -> startStreaming(intent)
        }
        return START_NOT_STICKY
    }

    private fun startStreaming(i: Intent) {
        if (running) return
        createChannel()
        val notif = buildNotification("Starting…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIF_ID, notif)

        val code = i.getIntExtra(EXTRA_CODE, 0)
        @Suppress("DEPRECATION") val data = i.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) { publish("Missing screen-capture permission", false); stopSelf(); return }

        val cfg = StreamConfig(
            host = i.getStringExtra(EXTRA_HOST) ?: "192.168.60.2",
            port = i.getIntExtra(EXTRA_PORT, 0),
            fps = i.getIntExtra(EXTRA_FPS, 60),
            bitrateMbps = i.getIntExtra(EXTRA_MBPS, 20),
        )
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpm.getMediaProjection(code, data)

        running = true
        publish("Starting…", true)
        pipeline = StreamPipeline(
            this, projection, cfg,
            status = { s -> publish(s, true); updateNotification(s) },
            onEnded = { reason ->
                running = false
                publish(reason, false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            },
        ).also { it.start() }
    }

    override fun onDestroy() {
        pipeline?.stop("Stopped")
        running = false
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen streaming", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ScreenStreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Streaming screen to goggles")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}
