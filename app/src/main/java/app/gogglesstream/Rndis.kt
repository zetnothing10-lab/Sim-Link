package app.gogglesstream

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.IOException

/** Interfaces of the goggles' USB-RNDIS function. */
class RndisInterfaces(
    val control: UsbInterface,
    val data: UsbInterface,
    val bulkIn: UsbEndpoint,
    val bulkOut: UsbEndpoint,
) {
    companion object {
        /** Human readable dump of every interface, shown on failure to make debugging possible. */
        fun describe(d: UsbDevice): String = buildString {
            append("%04x:%04x".format(d.vendorId, d.productId)).append(" interfaces: ")
            for (i in 0 until d.interfaceCount) {
                val f = d.getInterface(i)
                append("[#${f.id}.${f.alternateSetting} c=0x%02x/%02x/%02x ep=".format(f.interfaceClass, f.interfaceSubclass, f.interfaceProtocol))
                for (e in 0 until f.endpointCount) {
                    val ep = f.getEndpoint(e)
                    append("0x%02x/t%d ".format(ep.address, ep.type))
                }
                append("] ")
            }
        }

        private fun bulkPair(f: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (e in 0 until f.endpointCount) {
                val ep = f.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
            return if (inEp != null && outEp != null) inEp to outEp else null
        }

        fun find(d: UsbDevice): RndisInterfaces? {
            // Control interface: RNDIS-over-"wireless controller" (e0/01/03) or CDC ACM-vendor (02/02/ff).
            var control: UsbInterface? = null
            for (i in 0 until d.interfaceCount) {
                val f = d.getInterface(i)
                val c = f.interfaceClass; val s = f.interfaceSubclass; val p = f.interfaceProtocol
                if ((c == 0xE0 && s == 1 && p == 3) || (c == 2 && s == 2 && p == 0xFF)) { control = f; break }
            }
            if (control == null) return null
            // Data interface: the interface after the control one that has a bulk IN/OUT pair
            // (prefer CDC-data class 0x0A; RNDIS-over-e0 devices may use class 0x0A too).
            var best: Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? = null
            for (i in 0 until d.interfaceCount) {
                val f = d.getInterface(i)
                if (f.id == control.id) continue
                val pair = bulkPair(f) ?: continue
                if (f.interfaceClass == 0x0A) { best = Triple(f, pair.first, pair.second); break }
                if (best == null && f.id == control.id + 1) best = Triple(f, pair.first, pair.second)
            }
            val b = best ?: return null
            return RndisInterfaces(control, b.first, b.second, b.third)
        }
    }
}

/** Minimal RNDIS host driver in userspace (Android has no kernel rndis_host). */
class RndisLink(private val conn: UsbDeviceConnection, private val ifs: RndisInterfaces) {
    private val INIT_C = 0x80000002L.toInt()
    private val QUERY_C = 0x80000004L.toInt()
    private val SET_C = 0x80000005L.toInt()
    private val KEEPALIVE_C = 0x80000008L.toInt()

    private var nextId = 1
    private val txLock = Any()
    var permanentMac: ByteArray? = null
        private set

    private fun sendCommand(msg: ByteArray) {
        val r = conn.controlTransfer(0x21, 0x00, 0, ifs.control.id, msg, msg.size, 2000)
        if (r < 0) throw IOException("RNDIS command rejected by goggles")
    }

    private fun readResponse(expectType: Int, id: Int): ByteArray {
        val buf = ByteArray(4096)
        val deadline = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < deadline) {
            val n = conn.controlTransfer(0xA1, 0x01, 0, ifs.control.id, buf, buf.size, 500)
            if (n >= 12) {
                val t = buf.le32(0)
                if (t == expectType && buf.le32(8) == id) return buf.copyOf(n)
                // ignore INDICATE_STATUS and unrelated replies
            } else {
                Thread.sleep(5)
            }
        }
        throw IOException("RNDIS response timeout")
    }

    fun init(): String {
        if (!conn.claimInterface(ifs.control, true)) throw IOException("cannot claim RNDIS control interface")
        if (!conn.claimInterface(ifs.data, true)) throw IOException("cannot claim RNDIS data interface")
        if (ifs.data.alternateSetting != 0) conn.setInterface(ifs.data)

        // INITIALIZE
        var id = nextId++
        val init = ByteArray(24)
        init.putLe32(0, 2); init.putLe32(4, 24); init.putLe32(8, id)
        init.putLe32(12, 1); init.putLe32(16, 0); init.putLe32(20, 0x4000)
        sendCommand(init)
        val ic = readResponse(INIT_C, id)
        val status = ic.le32(12)
        if (status != 0) throw IOException("RNDIS init failed, status 0x%08x".format(status))

        // QUERY OID_802_3_PERMANENT_ADDRESS
        id = nextId++
        val q = ByteArray(28)
        q.putLe32(0, 4); q.putLe32(4, 28); q.putLe32(8, id); q.putLe32(12, 0x01010101)
        sendCommand(q)
        try {
            val qc = readResponse(QUERY_C, id)
            if (qc.le32(12) == 0 && qc.le32(16) >= 6) {
                val off = 8 + qc.le32(20)
                if (off + 6 <= qc.size) permanentMac = qc.copyOfRange(off, off + 6)
            }
        } catch (_: IOException) { /* optional */ }

        // SET OID_GEN_CURRENT_PACKET_FILTER: directed | all-multicast | broadcast | promiscuous
        id = nextId++
        val s = ByteArray(32)
        s.putLe32(0, 5); s.putLe32(4, 32); s.putLe32(8, id); s.putLe32(12, 0x0001010E)
        s.putLe32(16, 4); s.putLe32(20, 20); s.putLe32(24, 0); s.putLe32(28, 0x2D)
        sendCommand(s)
        val sc = readResponse(SET_C, id)
        if (sc.le32(12) != 0) throw IOException("RNDIS packet filter rejected, status 0x%08x".format(sc.le32(12)))

        return "RNDIS up, goggles MAC ${permanentMac?.hex() ?: "unknown"}"
    }

    fun sendFrame(eth: ByteArray) {
        val total = 44 + eth.size
        val m = ByteArray(total)
        m.putLe32(0, 1); m.putLe32(4, total); m.putLe32(8, 36); m.putLe32(12, eth.size)
        System.arraycopy(eth, 0, m, 44, eth.size)
        synchronized(txLock) {
            val n = conn.bulkTransfer(ifs.bulkOut, m, m.size, 1000)
            if (n < 0) throw IOException("USB write failed")
            if (m.size % ifs.bulkOut.maxPacketSize == 0) conn.bulkTransfer(ifs.bulkOut, ByteArray(0), 0, 1000)
        }
    }

    /** Blocks, delivering Ethernet frames, until [running] returns false or the USB link fails. */
    fun receiveLoop(running: () -> Boolean, onFrame: (ByteArray) -> Unit) {
        val buf = ByteArray(16384)
        var failures = 0
        while (running()) {
            val n = conn.bulkTransfer(ifs.bulkIn, buf, buf.size, 500)
            if (n < 0) {
                // -1 is also what a timeout looks like; only give up if the device is gone.
                if (++failures > 20) { if (!running()) return }
                continue
            }
            failures = 0
            var off = 0
            while (off + 8 <= n) {
                val type = buf.le32(off)
                val len = buf.le32(off + 4)
                if (len < 8 || off + len > n) break
                when (type) {
                    1 -> { // RNDIS_PACKET_MSG
                        val dOff = buf.le32(off + 8)
                        val dLen = buf.le32(off + 12)
                        val start = off + 8 + dOff
                        if (dLen > 0 && start >= off && start + dLen <= off + len) {
                            onFrame(buf.copyOfRange(start, start + dLen))
                        }
                    }
                    8 -> { // KEEPALIVE_MSG -> reply
                        val r = ByteArray(16)
                        r.putLe32(0, KEEPALIVE_C); r.putLe32(4, 16); r.putLe32(8, buf.le32(off + 8)); r.putLe32(12, 0)
                        try { conn.controlTransfer(0x21, 0x00, 0, ifs.control.id, r, r.size, 500) } catch (_: Exception) {}
                    }
                }
                off += len
            }
        }
    }
}
