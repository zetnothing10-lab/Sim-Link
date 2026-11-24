package app.gogglesstream

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var fps: EditText
    private lateinit var mbps: EditText
    private lateinit var button: Button
    private lateinit var statusView: TextView
    private val prefs by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }

    private fun field(label: String, value: String, numeric: Boolean): EditText {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply { text = label; textSize = 13f })
        val e = EditText(this).apply {
            setText(value); setSingleLine()
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
        }
        box.addView(e)
        (findViewById<LinearLayout>(1))?.addView(box)
        return e
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            id = 1; orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        setContentView(root)
        root.addView(TextView(this).apply { text = "Goggles Screen Stream"; textSize = 24f })
        root.addView(TextView(this).apply {
            text = "Streams your entire Android screen as 1920x1080 H.264 to a goggles receiver."
            textSize = 13f
        })
        host = field("Receiver address", prefs.getString("host", "192.168.60.2")!!, false)
        port = field("TCP port", prefs.getString("port", "")!!, true)
        fps = field("FPS (30–120)", prefs.getString("fps", "60")!!, true)
        mbps = field("Bitrate (Mb/s)", prefs.getString("mbps", "20")!!, true)

        button = Button(this).apply { setOnClickListener { onButton() } }
        root.addView(button)
        statusView = TextView(this).apply { textSize = 14f; gravity = Gravity.START; setPadding(0, 32, 0, 0) }
        root.addView(statusView)

        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        render(ScreenStreamService.lastStatus, ScreenStreamService.running)
    }

    override fun onStart() {
        super.onStart()
        ScreenStreamService.listener = { s, active -> render(s, active) }
        render(ScreenStreamService.lastStatus, ScreenStreamService.running)
    }

    override fun onStop() {
        ScreenStreamService.listener = null
        super.onStop()
    }

    private fun render(msg: String, active: Boolean) {
        statusView.text = msg
        button.text = if (active) "Stop streaming" else "Start streaming (entire screen)"
    }

    private fun onButton() {
        if (ScreenStreamService.running) {
            startService(Intent(this, ScreenStreamService::class.java).setAction(ScreenStreamService.ACTION_STOP))
            return
        }
        val p = port.text.toString().toIntOrNull()
        val f = fps.text.toString().toIntOrNull()?.coerceIn(30, 120)
        val m = mbps.text.toString().toIntOrNull()?.coerceIn(1, 100)
        if (p == null || p !in 1..65535 || host.text.isBlank() || f == null || m == null) {
            Toast.makeText(this, "Enter a valid address, port, FPS and bitrate", Toast.LENGTH_LONG).show()
            return
        }
        prefs.edit().putString("host", host.text.toString().trim()).putString("port", "$p")
            .putString("fps", "$f").putString("mbps", "$m").apply()

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // Android 14+: restrict the picker to the entire default display (no single-app option).
        val intent = if (Build.VERSION.SDK_INT >= 34)
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        @Suppress("DEPRECATION") startActivityForResult(intent, 100)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 100) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen capture was not allowed", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(this, ScreenStreamService::class.java)
            .setAction(ScreenStreamService.ACTION_START)
            .putExtra(ScreenStreamService.EXTRA_CODE, resultCode)
            .putExtra(ScreenStreamService.EXTRA_DATA, data)
            .putExtra(ScreenStreamService.EXTRA_HOST, host.text.toString().trim())
            .putExtra(ScreenStreamService.EXTRA_PORT, port.text.toString().toInt())
            .putExtra(ScreenStreamService.EXTRA_FPS, fps.text.toString().toInt().coerceIn(30, 120))
            .putExtra(ScreenStreamService.EXTRA_MBPS, mbps.text.toString().toInt().coerceIn(1, 100))
        startForegroundService(i)
    }
}
