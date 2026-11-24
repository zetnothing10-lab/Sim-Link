package app.gogglesstream

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Makes 192.168.60.2 (the goggles) reachable from normal sockets:
 *   app socket -> TUN (VpnService, 192.168.60.0/24 only) -> Ethernet+ARP -> userspace RNDIS -> USB bulk.
 * Only that /24 is routed into the tunnel, so the rest of the phone's traffic is untouched.
 */
class UsbBridgeService : VpnService() {

    companion object {
        const val ACTION_START = "app.gogglesstream.USB_START"
        const val ACTION_STOP = "app.gogglesstream.USB_STOP"
        private const val ACTION_PERM = "app.gogglesstream.USB_PERMISSION"
        private const val DJI_VID = 0x2CA3
        private const val GOGGLES_PID = 0x0020

        @Volatile var running = false
        @Volatile var lastStatus = "Goggles not connected"
        var listener: ((String, Boolean) -> Unit)? = null
        private val main = Handler(Looper.getMainLooper())
        fun publish(msg: String, active: Boolean) {
            lastStatus = msg
            main.post { listener?.invoke(msg, active) }
        }
    }

    private val hostIp = byteArrayOf(192.toByte(), 168.toByte(), 60, 1)
    private val gogglesIp = byteArrayOf(192.toByte(), 168.toByte(), 60, 2)
    private val ourMac = byteArrayOf(0x02, 0x44, 0x4A, 0x49, 0x00, 0x01)
    private val broadcast = ByteArray(6) { 0xFF.toByte() }

    @Volatile private var gogglesMac: ByteArray? = null
    @Volatile private var alive = false
    private var conn: UsbDeviceConnection? = null
    private var tun: ParcelFileDescriptor? = null
    private var link: RndisLink? = null
    private var tunOut: FileOutputStream? = null
    private var detachReceiver: BroadcastReceiver? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> shutdown("Goggles disconnected")
            ACTION_START -> if (!running) {
                running = true
                publish("Looking for the goggles…", true)
                Thread({ setup() }, "usb-setup").start()
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() { shutdown("VPN permission revoked") }
    override fun onDestroy() { shutdown("Goggles disconnected"); super.onDestroy() }

    // ------------------------------------------------------------------ setup

    private fun setup() {
        try {
            val um = getSystemService(Context.USB_SERVICE) as UsbManager
            val candidates = um.deviceList.values.filter { it.vendorId == DJI_VID }
            val dev = candidates.firstOrNull { it.productId == GOGGLES_PID } ?: candidates.firstOrNull()
                ?: return fail("No DJI USB device found. Plug the goggles into the phone's USB-C port.")

            if (!um.hasPermission(dev)) {
                publish("Allow USB access to the goggles…", true)
                if (!requestPermission(um, dev)) return fail("USB permission was denied")
            }
            val c = um.openDevice(dev) ?: return fail("Could not open the goggles USB device")
            conn = c

            val ifs = RndisInterfaces.find(dev)
                ?: return fail("No RNDIS interface found. " + RndisInterfaces.describe(dev))
            publish("Starting USB network…", true)
            val l = RndisLink(c, ifs)
            link = l
            val info = l.init()

            val builder = Builder()
                .setSession("Goggles USB")
                .addAddress("192.168.60.1", 24)
                .addRoute("192.168.60.0", 24)
                .setMtu(1400)
                .setBlocking(true)
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
            val t = builder.establish() ?: return fail("VPN permission missing")
            tun = t
            tunOut = FileOutputStream(t.fileDescriptor)
            alive = true

            watchDetach(dev)
            Thread({ rxLoop() }, "usb-rx").start()
            Thread({ txLoop(t) }, "usb-tx").start()
            Thread({ arpLoop() }, "usb-arp").start()
            publish("Goggles linked ($info). Waiting for it to answer on 192.168.60.2…", true)

            // Optional: ask the goggles which plugins it has (also proves the control channel).
            Thread({ queryPlugins(dev, c) }, "usb-ctl").start()
        } catch (e: Exception) {
            fail("Goggles link failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun requestPermission(um: UsbManager, dev: UsbDevice): Boolean {
        val latch = CountDownLatch(1)
        var granted = false
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                latch.countDown()
            }
        }
        val f = IntentFilter(ACTION_PERM)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(r, f)
        try {
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_PERM).setPackage(packageName), PendingIntent.FLAG_MUTABLE,
            )
            um.requestPermission(dev, pi)
            latch.await(60, TimeUnit.SECONDS)
        } finally {
            try { unregisterReceiver(r) } catch (_: Exception) {}
        }
        return granted
    }

    private fun watchDetach(dev: UsbDevice) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d: UsbDevice? = if (Build.VERSION.SDK_INT >= 33)
                    i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (d?.deviceName == dev.deviceName) shutdown("Goggles unplugged")
            }
        }
        val f = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(r, f)
        detachReceiver = r
    }

    private fun queryPlugins(dev: UsbDevice, c: UsbDeviceConnection) {
        try {
            val ctl = GogglesControl.find(dev, c) ?: return
            ctl.claim()
            val json = ctl.plugins()
            val short = if (json.length > 160) json.take(160) + "…" else json
            publish("Goggles linked. Plugins: $short", true)
        } catch (e: Exception) {
            // Not fatal: streaming does not depend on this query.
            publish("Goggles linked (control query failed: ${e.message}). Select PC Sim on the goggles.", true)
        }
    }

    // ------------------------------------------------------------------ packet plumbing

    private fun rxLoop() {
        try {
            link!!.receiveLoop({ alive }) { eth -> onEthernet(eth) }
        } catch (e: Exception) {
            if (alive) shutdown("USB link lost: ${e.message}")
        }
    }

    private fun txLoop(t: ParcelFileDescriptor) {
        val inp = FileInputStream(t.fileDescriptor)
        val buf = ByteArray(4096)
        try {
            while (alive) {
                val n = inp.read(buf)
                if (n <= 0) continue
                if ((buf[0].toInt() shr 4) != 4) continue // IPv4 only
                val eth = ByteArray(14 + n)
                System.arraycopy(gogglesMac ?: broadcast, 0, eth, 0, 6)
                System.arraycopy(ourMac, 0, eth, 6, 6)
                eth[12] = 0x08; eth[13] = 0x00
                System.arraycopy(buf, 0, eth, 14, n)
                link!!.sendFrame(eth)
            }
        } catch (e: Exception) {
            if (alive) shutdown("USB link lost: ${e.message}")
        }
    }

    private fun arpLoop() {
        try {
            var tries = 0
            while (alive && gogglesMac == null && tries < 120) {
                link!!.sendFrame(arp(1, broadcast, ByteArray(6), gogglesIp))
                Thread.sleep(500); tries++
            }
        } catch (_: Exception) {}
    }

    private fun arp(op: Int, dstMac: ByteArray, tha: ByteArray, targetIp: ByteArray): ByteArray {
        val f = ByteArray(14 + 28)
        System.arraycopy(dstMac, 0, f, 0, 6)
        System.arraycopy(ourMac, 0, f, 6, 6)
        f[12] = 0x08; f[13] = 0x06
        f[14] = 0; f[15] = 1       // Ethernet
        f[16] = 0x08; f[17] = 0x00 // IPv4
        f[18] = 6; f[19] = 4
        f[20] = 0; f[21] = op.toByte()
        System.arraycopy(ourMac, 0, f, 22, 6)
        System.arraycopy(hostIp, 0, f, 28, 4)
        System.arraycopy(tha, 0, f, 32, 6)
        System.arraycopy(targetIp, 0, f, 38, 4)
        return f
    }

    private fun onEthernet(eth: ByteArray) {
        if (eth.size < 14) return
        val type = ((eth[12].toInt() and 0xff) shl 8) or (eth[13].toInt() and 0xff)
        when (type) {
            0x0806 -> {
                if (eth.size < 42) return
                val op = eth[21].toInt() and 0xff
                val senderIsGoggles = (0 until 4).all { eth[28 + it] == gogglesIp[it] }
                if (senderIsGoggles) learn(eth.copyOfRange(22, 28))
                val targetIsUs = (0 until 4).all { eth[38 + it] == hostIp[it] }
                if (op == 1 && targetIsUs) {
                    link?.sendFrame(arp(2, eth.copyOfRange(22, 28), eth.copyOfRange(22, 28), eth.copyOfRange(28, 32)))
                }
            }
            0x0800 -> {
                if (eth.size < 34) return
                if ((0 until 4).all { eth[26 + it] == gogglesIp[it] }) learn(eth.copyOfRange(6, 12))
                val ipLen = ((eth[16].toInt() and 0xff) shl 8) or (eth[17].toInt() and 0xff)
                val n = minOf(ipLen, eth.size - 14)
                if (n > 0) tunOut?.write(eth, 14, n)
            }
        }
    }

    private fun learn(mac: ByteArray) {
        if (gogglesMac == null) {
            gogglesMac = mac
            publish("Goggles reachable at 192.168.60.2 (${mac.hex()}). You can start streaming.", true)
        }
    }

    // ------------------------------------------------------------------ teardown

    private fun fail(msg: String) {
        shutdown(msg)
    }

    @Synchronized
    private fun shutdown(reason: String) {
        val wasRunning = running
        alive = false
        running = false
        try { detachReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        detachReceiver = null
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        try { conn?.close() } catch (_: Exception) {}
        conn = null
        gogglesMac = null
        if (wasRunning) publish(reason, false)
        stopSelf()
    }
}
