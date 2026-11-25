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
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var fps: EditText
    private lateinit var mbps: EditText
    private lateinit var lowImpact: CheckBox
    private lateinit var usbButton: Button
    private lateinit var usbStatus: TextView
    private lateinit var button: Button
    private lateinit var statusView: TextView
    private val prefs by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }

    private fun label(text: String, size: Float = 13f) =
        TextView(this).apply { this.text = text; textSize = size; setPadding(0, 16, 0, 0) }

    private fun field(name: String, value: String, numeric: Boolean): EditText {
        root.addView(label(name))
        val e = EditText(this).apply {
            setText(value); setSingleLine()
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(e)
        return e
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        setContentView(android.widget.ScrollView(this).apply { addView(root) })

        root.addView(label("Sim-Link", 24f))
        root.addView(label("Streams your entire Android screen as 1920x1080 H.264 to DJI Goggles 3 (PC Sim)."))

        root.addView(label("Step 1 – USB link to the goggles", 16f))
        root.addView(label("Plug the goggles into the phone's USB-C port, then:"))
        usbButton = Button(this).apply { setOnClickListener { onUsbButton() } }
        root.addView(usbButton)
        usbStatus = label("", 13f)
        root.addView(usbStatus)

        root.addView(label("Step 2 – Stream", 16f))
        host = field("Receiver address", prefs.getString("host", "192.168.60.2")!!, false)
        port = field("TCP port", prefs.getString("port", "9153")!!, true)
        fps = field("FPS (30–120)", prefs.getString("fps", "60")!!, true)
        mbps = field("Bitrate (Mb/s)", prefs.getString("mbps", "8")!!, true)
        lowImpact = CheckBox(this).apply {
            text = "Low-impact mode (gentler on DJI Fly)"
            isChecked = prefs.getBoolean("low", true)
        }
        root.addView(lowImpact)
        button = Button(this).apply { setOnClickListener { onButton() } }
        root.addView(button)
        statusView = label("", 14f)
        root.addView(statusView)

        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        render(ScreenStreamService.lastStatus, ScreenStreamService.running)
        renderUsb(UsbBridgeService.lastStatus, UsbBridgeService.running)
    }

    override fun onStart() {
        super.onStart()
        ScreenStreamService.listener = { s, active -> render(s, active) }
        UsbBridgeService.listener = { s, active -> renderUsb(s, active) }
        render(ScreenStreamService.lastStatus, ScreenStreamService.running)
        renderUsb(UsbBridgeService.lastStatus, UsbBridgeService.running)
    }

    override fun onStop() {
        ScreenStreamService.listener = null
        UsbBridgeService.listener = null
        super.onStop()
    }

    private fun render(msg: String, active: Boolean) {
        statusView.text = msg
        button.text = if (active) "Stop streaming" else "Start streaming (entire screen)"
    }

    private fun renderUsb(msg: String, active: Boolean) {
        usbStatus.text = msg
        usbButton.text = if (active) "Disconnect goggles" else "Connect goggles (USB)"
    }

    // ---------------------------------------------------------------- USB link

    private fun onUsbButton() {
        if (UsbBridgeService.running) {
            startService(Intent(this, UsbBridgeService::class.java).setAction(UsbBridgeService.ACTION_STOP))
            return
        }
        // No VPN involved: the bridge runs its own userspace TCP/IP over the USB (RNDIS) link.
        startForegroundService(Intent(this, UsbBridgeService::class.java).setAction(UsbBridgeService.ACTION_START))
    }

    // ---------------------------------------------------------------- Streaming

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
            .putString("fps", "$f").putString("mbps", "$m")
            .putBoolean("low", lowImpact.isChecked).apply()

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
            .putExtra(ScreenStreamService.EXTRA_LOW, lowImpact.isChecked)
        startForegroundService(i)
    }
}
