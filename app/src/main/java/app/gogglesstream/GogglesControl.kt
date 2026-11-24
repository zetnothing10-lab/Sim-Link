package app.gogglesstream

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.IOException

/**
 * Goggles control channel (reverse-engineered from VisorSim.exe): DJI "DUML" frames over the
 * vendor bulk interface #4 (OUT 0x04, IN 0x85), carrying a "PLB1" envelope with a JSON body.
 */
object Duml {
    fun crc8(b: ByteArray, len: Int): Int {
        var crc = 0x77
        for (i in 0 until len) {
            crc = crc xor (b[i].toInt() and 0xff)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8C else crc ushr 1 }
        }
        return crc and 0xff
    }

    fun crc16(b: ByteArray, len: Int): Int {
        var crc = 0x3692
        for (i in 0 until len) {
            crc = crc xor (b[i].toInt() and 0xff)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1 }
        }
        return crc and 0xffff
    }

    fun buildRequest(seq: Int, reqId: Int, json: String): ByteArray {
        val body = json.toByteArray(Charsets.UTF_8)
        val total = 11 + 0x18 + body.size + 2
        val f = ByteArray(total)
        f[0] = 0x55
        f[1] = total.toByte()
        f[2] = (((total ushr 8) and 0x03) or 0x04).toByte()
        f[3] = crc8(f, 3).toByte()
        f[4] = 0x0A; f[5] = 0x1E
        f.putLe16(6, seq)
        f[8] = 0x40; f[9] = 0x3F; f[10] = 0x7F
        // PLB1 envelope
        f[11] = 'P'.code.toByte(); f[12] = 'L'.code.toByte(); f[13] = 'B'.code.toByte(); f[14] = '1'.code.toByte()
        f[15] = 1       // version
        f[16] = 2       // type: request with body
        f[17] = 3
        f[18] = 0
        f.putLe32(19, reqId)
        f.putLe32(23, body.size)
        f.putLe32(27, 0)             // token
        f.putLe16(31, body.size)
        f.putLe16(33, 0)
        System.arraycopy(body, 0, f, 35, body.size)
        f.putLe16(total - 2, crc16(f, total - 2))
        return f
    }

    /** Scans [buf] for a valid response to [reqId]; returns its JSON body or null. */
    fun parseResponse(buf: ByteArray, len: Int, reqId: Int): String? {
        var i = 0
        while (i + 4 <= len) {
            if (buf[i] != 0x55.toByte()) { i++; continue }
            val flen = ((buf[i + 2].toInt() and 0xff) shl 8 or (buf[i + 1].toInt() and 0xff)) and 0x3ff
            if (flen < 0x25 || i + flen > len) { i++; continue }
            val f = buf.copyOfRange(i, i + flen)
            val ok = crc8(f, 3) == (f[3].toInt() and 0xff) &&
                crc16(f, flen - 2) == f.le16(flen - 2) &&
                f[4] == 0x1E.toByte() && f[5] == 0x0A.toByte() &&
                f[9] == 0x3F.toByte() && f[10] == 0x7F.toByte() &&
                f[11] == 'P'.code.toByte() && f[12] == 'L'.code.toByte() && f[13] == 'B'.code.toByte() && f[14] == '1'.code.toByte() &&
                f[16] == 3.toByte() && f.le32(19) == reqId
            if (ok) {
                val n = f.le16(31)
                val status = f.le16(33)
                if (status != 0) throw IOException("goggles rejected control request (status $status)")
                if (35 + n + 2 <= flen) return String(f, 35, n, Charsets.UTF_8)
            }
            i += flen.coerceAtLeast(1)
        }
        return null
    }
}

class GogglesControl(
    private val conn: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val outEp: UsbEndpoint,
    private val inEp: UsbEndpoint,
) {
    private var seq = 0
    private var reqId = 0

    fun claim() {
        if (!conn.claimInterface(iface, true)) throw IOException("goggles control interface is busy")
    }

    fun call(json: String, timeoutMs: Int = 2500): String {
        val id = ++reqId
        val req = Duml.buildRequest(++seq, id, json)
        if (conn.bulkTransfer(outEp, req, req.size, 700) != req.size) throw IOException("control write failed")
        val acc = ByteArray(16384)
        var have = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        val tmp = ByteArray(4096)
        while (System.currentTimeMillis() < deadline) {
            val n = conn.bulkTransfer(inEp, tmp, tmp.size, 250)
            if (n > 0 && have + n <= acc.size) {
                System.arraycopy(tmp, 0, acc, have, n); have += n
                Duml.parseResponse(acc, have, id)?.let { return it }
            }
        }
        throw IOException("control response timed out")
    }

    fun plugins(): String = call("""{"v":1,"id":1,"method":"desktop.plugins"}""")

    companion object {
        /** Vendor bulk interface #4 with OUT 0x04 / IN 0x85. */
        fun find(d: UsbDevice, conn: UsbDeviceConnection): GogglesControl? {
            for (i in 0 until d.interfaceCount) {
                val f = d.getInterface(i)
                if (f.id != 4 || f.alternateSetting != 0) continue
                var o: UsbEndpoint? = null; var inn: UsbEndpoint? = null
                for (e in 0 until f.endpointCount) {
                    val ep = f.getEndpoint(e)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.address == 0x04) o = ep
                    if (ep.address == 0x85) inn = ep
                }
                if (o != null && inn != null) return GogglesControl(conn, f, o, inn)
            }
            return null
        }
    }
}
