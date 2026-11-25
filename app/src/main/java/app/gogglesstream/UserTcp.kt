package app.gogglesstream

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A tiny userspace TCP *client* stack, used instead of a VpnService/TUN so that no VPN permission is needed.
 *
 * It only ever talks to one peer (the goggles) over a point-to-point USB link, so it is deliberately simple:
 * outgoing connections only, fixed receive window, go-back-N retransmission, in-order receive only.
 * Input: raw IPv4 packets via [onIpPacket]. Output: raw IPv4 packets via [sendIp].
 */
class UserTcpStack(
    private val localIp: ByteArray,
    private val remoteIp: ByteArray,
    private val sendIp: (ByteArray) -> Unit,
) {
    companion object {
        const val FIN = 0x01
        const val SYN = 0x02
        const val RST = 0x04
        const val PSH = 0x08
        const val ACK = 0x10
        internal const val MAX_MSS = 1360
        internal const val RCV_WINDOW = 65535
        internal const val MAX_INFLIGHT = 65535

        internal fun u16(p: ByteArray, o: Int) = ((p[o].toInt() and 0xff) shl 8) or (p[o + 1].toInt() and 0xff)
        internal fun u32(p: ByteArray, o: Int) =
            ((p[o].toInt() and 0xff) shl 24) or ((p[o + 1].toInt() and 0xff) shl 16) or
                ((p[o + 2].toInt() and 0xff) shl 8) or (p[o + 3].toInt() and 0xff)
        internal fun put16(p: ByteArray, o: Int, v: Int) { p[o] = (v ushr 8).toByte(); p[o + 1] = v.toByte() }
        internal fun put32(p: ByteArray, o: Int, v: Int) {
            p[o] = (v ushr 24).toByte(); p[o + 1] = (v ushr 16).toByte(); p[o + 2] = (v ushr 8).toByte(); p[o + 3] = v.toByte()
        }
        internal fun lt(a: Int, b: Int) = (a - b) < 0
        internal fun le(a: Int, b: Int) = (a - b) <= 0

        /** Ones-complement sum of [len] bytes (not yet inverted / folded). */
        private fun sum(p: ByteArray, off: Int, len: Int, init: Long = 0): Long {
            var s = init
            var i = off
            val end = off + len
            while (i + 1 < end) { s += ((p[i].toInt() and 0xff) shl 8) or (p[i + 1].toInt() and 0xff); i += 2 }
            if (i < end) s += (p[i].toInt() and 0xff) shl 8
            return s
        }
        private fun fold(s0: Long): Int {
            var s = s0
            while (s ushr 16 != 0L) s = (s and 0xffff) + (s ushr 16)
            return (s.inv() and 0xffff).toInt()
        }
        internal fun ipChecksum(p: ByteArray, off: Int, len: Int) = fold(sum(p, off, len))
        internal fun tcpChecksum(p: ByteArray, ipOff: Int, tcpOff: Int, tcpLen: Int): Int {
            var s = sum(p, ipOff + 12, 8) // src + dst
            s += 6 // protocol
            s += tcpLen
            s = sum(p, tcpOff, tcpLen, s)
            return fold(s)
        }
    }

    private val conns = ConcurrentHashMap<Int, UserTcpSocket>()
    private val rnd = Random()
    private val ipId = AtomicInteger(rnd.nextInt(0xffff))
    @Volatile private var stopped = false

    /** Optional diagnostic sink (the Android service points this at the session log). */
    @Volatile var onEvent: ((String) -> Unit)? = null
    internal fun note(s: String) { try { onEvent?.invoke(s) } catch (_: Exception) {} }

    private val timer = Thread({ timerLoop() }, "usb-tcp-timer").apply { isDaemon = true; start() }

    /** Opens a TCP connection to [port] on the goggles. Blocks until established or [timeoutMs] elapsed. */
    @Throws(IOException::class)
    fun connect(port: Int, timeoutMs: Int): UserTcpSocket {
        if (stopped) throw IOException("USB link is not up")
        var lp: Int
        do { lp = 40000 + rnd.nextInt(20000) } while (conns.containsKey(lp))
        val s = UserTcpSocket(this, lp, port, rnd.nextInt())
        conns[lp] = s
        try {
            s.open(timeoutMs)
        } catch (e: IOException) {
            s.abort(e.message ?: "connect failed")
            conns.remove(lp)
            throw e
        }
        return s
    }

    fun shutdown() {
        stopped = true
        for (c in conns.values) c.abort("USB link closed")
        conns.clear()
        timer.interrupt()
    }

    internal fun forget(localPort: Int) { conns.remove(localPort) }

    private fun timerLoop() {
        try {
            while (!stopped) {
                Thread.sleep(40)
                for (c in conns.values) c.tick()
            }
        } catch (_: InterruptedException) {
        }
    }

    // ------------------------------------------------------------------ incoming

    fun onIpPacket(p: ByteArray, off: Int, len: Int) {
        if (len < 40 || off + len > p.size) return
        if ((p[off].toInt() shr 4) != 4) return
        val ihl = (p[off].toInt() and 0x0f) * 4
        if (ihl < 20 || len < ihl + 20) return
        if ((p[off + 9].toInt() and 0xff) != 6) return
        val fragOff = u16(p, off + 6) and 0x1fff
        val moreFrags = (p[off + 6].toInt() and 0x20) != 0
        if (fragOff != 0 || moreFrags) return // fragments are not reassembled
        for (i in 0 until 4) {
            if (p[off + 12 + i] != remoteIp[i] || p[off + 16 + i] != localIp[i]) return
        }
        val total = u16(p, off + 2)
        val end = off + minOf(total, len)
        val t = off + ihl
        if (t + 20 > end) return
        val dataOff = ((p[t + 12].toInt() shr 4) and 0x0f) * 4
        if (dataOff < 20 || t + dataOff > end) return
        val srcPort = u16(p, t)
        val dstPort = u16(p, t + 2)
        val seq = u32(p, t + 4)
        val ack = u32(p, t + 8)
        val flags = p[t + 13].toInt() and 0x3f
        val wnd = u16(p, t + 14)
        var mssOpt = 0
        if (flags and SYN != 0) {
            var i = t + 20
            while (i < t + dataOff) {
                val kind = p[i].toInt() and 0xff
                if (kind == 0) break
                if (kind == 1) { i++; continue }
                if (i + 1 >= t + dataOff) break
                val l = p[i + 1].toInt() and 0xff
                if (l < 2) break
                if (kind == 2 && l == 4 && i + 3 < t + dataOff) mssOpt = u16(p, i + 2)
                i += l
            }
        }
        val payOff = t + dataOff
        val payLen = end - payOff
        val c = conns[dstPort]
        if (c == null || c.remotePort != srcPort) {
            if (flags and RST == 0) {
                if (flags and ACK != 0) sendSegment(dstPort, srcPort, ack, 0, RST, 0, null, 0, 0, 0)
                else {
                    val a = seq + payLen + (if (flags and SYN != 0) 1 else 0) + (if (flags and FIN != 0) 1 else 0)
                    sendSegment(dstPort, srcPort, 0, a, RST or ACK, 0, null, 0, 0, 0)
                }
            }
            return
        }
        c.onSegment(seq, ack, flags, wnd, p, payOff, payLen, mssOpt)
    }

    // ------------------------------------------------------------------ outgoing

    internal fun sendSegment(
        lp: Int, rp: Int, seq: Int, ack: Int, flags: Int, wnd: Int,
        data: ByteArray?, dOff: Int, dLen: Int, mssOpt: Int,
    ) {
        val optLen = if (mssOpt > 0) 4 else 0
        val tcpLen = 20 + optLen + dLen
        val pkt = ByteArray(20 + tcpLen)
        pkt[0] = 0x45
        put16(pkt, 2, pkt.size)
        put16(pkt, 4, ipId.incrementAndGet() and 0xffff)
        put16(pkt, 6, 0x4000) // DF
        pkt[8] = 64
        pkt[9] = 6
        System.arraycopy(localIp, 0, pkt, 12, 4)
        System.arraycopy(remoteIp, 0, pkt, 16, 4)
        put16(pkt, 10, ipChecksum(pkt, 0, 20))
        val t = 20
        put16(pkt, t, lp)
        put16(pkt, t + 2, rp)
        put32(pkt, t + 4, seq)
        put32(pkt, t + 8, ack)
        pkt[t + 12] = (((20 + optLen) / 4) shl 4).toByte()
        pkt[t + 13] = flags.toByte()
        put16(pkt, t + 14, wnd)
        if (optLen > 0) { pkt[t + 20] = 2; pkt[t + 21] = 4; put16(pkt, t + 22, mssOpt) }
        if (dLen > 0 && data != null) System.arraycopy(data, dOff, pkt, t + 20 + optLen, dLen)
        put16(pkt, t + 16, tcpChecksum(pkt, 0, t, tcpLen))
        // A failed USB write just loses the packet; TCP retransmits, and the USB service tears the link down if it is really gone.
        try { sendIp(pkt) } catch (_: Exception) {}
    }
}

class UserTcpSocket internal constructor(
    private val stack: UserTcpStack,
    private val localPort: Int,
    internal val remotePort: Int,
    private val iss: Int,
) {
    private class Seg(val seq: Int, val data: ByteArray, var sentAt: Long)

    private val lock = ReentrantLock()
    private val cond = lock.newCondition()

    // 0 = SYN_SENT, 1 = ESTABLISHED, 2 = CLOSED
    private var state = 0
    private var error: String? = null
    private var sndUna = iss
    private var sndNxt = iss
    private var rcvNxt = 0
    private var peerWnd = UserTcpStack.RCV_WINDOW
    private var mss = 536
    private val unacked = ArrayDeque<Seg>()
    private val rx = ArrayDeque<ByteArray>()
    private var rxOff = 0
    private var rxEof = false
    private var rto = 250L
    private var retries = 0
    private var lastProbe = 0L
    private var closedAt = 0L
    private var retxTotal = 0
    private var zeroWndNoted = false

    @Volatile var soTimeout = 0

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            val n = read(b, 0, 1)
            return if (n < 0) -1 else b[0].toInt() and 0xff
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int = this@UserTcpSocket.read(b, off, len)
        override fun close() { this@UserTcpSocket.close() }
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }
        override fun write(b: ByteArray, off: Int, len: Int) { this@UserTcpSocket.write(b, off, len) }
        override fun close() { this@UserTcpSocket.close() }
    }

    private fun now() = System.nanoTime() / 1_000_000

    private fun awaitMs(ms: Long) { cond.await(ms.coerceAtLeast(1), TimeUnit.MILLISECONDS) }

    // ------------------------------------------------------------------ connect

    internal fun open(timeoutMs: Int) = lock.withLock {
        val deadline = now() + timeoutMs
        sndNxt = iss + 1
        var synAt = 0L
        while (state == 0) {
            val n = now()
            if (n >= deadline) throw SocketTimeoutException("connect timed out")
            if (n - synAt >= 500) {
                stack.sendSegment(localPort, remotePort, iss, 0, UserTcpStack.SYN, UserTcpStack.RCV_WINDOW, null, 0, 0, UserTcpStack.MAX_MSS)
                synAt = n
            }
            awaitMs(minOf(100, deadline - n))
        }
        if (state != 1) throw IOException(error ?: "connection refused")
    }

    // ------------------------------------------------------------------ incoming segment

    internal fun onSegment(seq: Int, ack: Int, flags: Int, wnd: Int, buf: ByteArray, off: Int, len: Int, mssOpt: Int) {
        lock.withLock {
            if (state == 2) {
                if (flags and UserTcpStack.FIN != 0) sendCtl(UserTcpStack.ACK, seq + len + 1)
                return
            }
            if (flags and UserTcpStack.RST != 0) {
                stack.note("RST from goggles: state=$state inflight=${sndNxt - sndUna} peerWnd=$peerWnd retx=$retxTotal rto=$rto")
                if (state == 1 || (flags and UserTcpStack.ACK != 0 && ack == iss + 1)) fail("connection reset by goggles")
                return
            }
            if (state == 0) {
                if (flags and UserTcpStack.SYN != 0 && flags and UserTcpStack.ACK != 0 && ack == iss + 1) {
                    sndUna = ack
                    rcvNxt = seq + 1
                    peerWnd = wnd
                    mss = if (mssOpt > 0) minOf(mssOpt, UserTcpStack.MAX_MSS) else 536
                    state = 1
                    sendCtl(UserTcpStack.ACK, rcvNxt)
                    cond.signalAll()
                }
                return
            }

            // ---- ACK processing
            if (flags and UserTcpStack.ACK != 0 && UserTcpStack.le(sndUna, ack) && UserTcpStack.le(ack, sndNxt)) {
                if (UserTcpStack.lt(sndUna, ack)) {
                    val n = now()
                    while (true) {
                        val h = unacked.peekFirst() ?: break
                        if (UserTcpStack.le(h.seq + h.data.size, ack)) {
                            if (retries == 0) {
                                val sample = (n - h.sentAt).coerceAtLeast(1)
                                rto = (sample * 3).coerceIn(60, 1000)
                            }
                            unacked.pollFirst()
                        } else break
                    }
                    sndUna = ack
                    retries = 0
                }
                if (wnd == 0 && !zeroWndNoted) { zeroWndNoted = true; stack.note("goggles advertised a zero window (inflight=${sndNxt - sndUna})") }
                if (wnd != 0) zeroWndNoted = false
                peerWnd = wnd
                cond.signalAll()
            }

            // ---- data / FIN (in-order only)
            val fin = flags and UserTcpStack.FIN != 0
            if (len > 0 || fin) {
                val d = rcvNxt - seq
                if (d < 0) { sendCtl(UserTcpStack.ACK, rcvNxt); return }   // gap: ask for retransmission
                if (d > len || (d == len && !fin)) { sendCtl(UserTcpStack.ACK, rcvNxt); return } // duplicate
                val newLen = len - d
                var changed = false
                if (newLen > 0) {
                    rx.add(buf.copyOfRange(off + d, off + len))
                    rcvNxt += newLen
                    changed = true
                }
                if (fin && !rxEof) { rcvNxt += 1; rxEof = true; changed = true }
                sendCtl(UserTcpStack.ACK, rcvNxt)
                if (changed) cond.signalAll()
            } else if (len == 0 && UserTcpStack.lt(seq, rcvNxt)) {
                sendCtl(UserTcpStack.ACK, rcvNxt) // keepalive probe
            }
        }
    }

    private fun sendCtl(flags: Int, ackNo: Int) {
        stack.sendSegment(localPort, remotePort, sndNxt, ackNo, flags, UserTcpStack.RCV_WINDOW, null, 0, 0, 0)
    }

    private fun sendData(s: Seg) {
        stack.sendSegment(
            localPort, remotePort, s.seq, rcvNxt, UserTcpStack.ACK or UserTcpStack.PSH,
            UserTcpStack.RCV_WINDOW, s.data, 0, s.data.size, 0,
        )
    }

    // ------------------------------------------------------------------ timers

    internal fun tick() {
        lock.withLock {
            val n = now()
            if (state == 2) {
                if (closedAt != 0L && n - closedAt > 2000) stack.forget(localPort)
                return
            }
            if (state != 1) return
            val head = unacked.peekFirst()
            if (head != null) {
                if (n - head.sentAt >= rto) {
                    if (retries >= 12) { fail("TCP timeout (goggles stopped answering)"); return }
                    retries++
                    retxTotal++
                    if (retxTotal <= 20 || retxTotal % 50 == 0) {
                        stack.note("retransmit #$retxTotal inflight=${sndNxt - sndUna} rto=$rto peerWnd=$peerWnd")
                    }
                    for (s in unacked) { sendData(s); s.sentAt = n } // go-back-N
                    rto = (rto * 2).coerceAtMost(2000)
                }
            } else if (peerWnd == 0 && n - lastProbe >= 1000) {
                lastProbe = n
                // zero-window probe: a segment with an old sequence number forces the peer to answer with its window
                stack.sendSegment(localPort, remotePort, sndNxt - 1, rcvNxt, UserTcpStack.ACK, UserTcpStack.RCV_WINDOW, null, 0, 0, 0)
            }
        }
    }

    // ------------------------------------------------------------------ read / write / close

    private fun read(b: ByteArray, off: Int, len: Int): Int = lock.withLock {
        if (len == 0) return 0
        val timeout = soTimeout
        val deadline = if (timeout > 0) now() + timeout else Long.MAX_VALUE
        while (rx.isEmpty()) {
            if (rxEof) return -1
            error?.let { throw IOException(it) }
            if (state == 2) throw IOException("socket closed")
            val left = if (deadline == Long.MAX_VALUE) 1000L else deadline - now()
            if (left <= 0) throw SocketTimeoutException("read timed out")
            awaitMs(minOf(left, 1000L))
        }
        var copied = 0
        while (copied < len) {
            val head = rx.peekFirst() ?: break
            val n = minOf(len - copied, head.size - rxOff)
            System.arraycopy(head, rxOff, b, off + copied, n)
            copied += n
            rxOff += n
            if (rxOff >= head.size) { rx.pollFirst(); rxOff = 0 }
        }
        copied
    }

    private fun write(b: ByteArray, off: Int, len: Int) {
        lock.withLock {
            var o = off
            var rem = len
            while (rem > 0) {
                error?.let { throw IOException(it) }
                if (state != 1) throw IOException("socket closed")
                val inflight = sndNxt - sndUna
                val room = minOf(peerWnd, UserTcpStack.MAX_INFLIGHT) - inflight
                if (room < minOf(mss, rem) && inflight > 0 || room <= 0) { awaitMs(100); continue }
                val n = minOf(rem, mss, room)
                val seg = Seg(sndNxt, b.copyOfRange(o, o + n), now())
                unacked.add(seg)
                sndNxt += n
                sendData(seg)
                o += n
                rem -= n
            }
        }
    }

    fun close() {
        lock.withLock {
            if (state == 2) return
            if (state == 1) {
                stack.sendSegment(localPort, remotePort, sndNxt, rcvNxt, UserTcpStack.FIN or UserTcpStack.ACK, UserTcpStack.RCV_WINDOW, null, 0, 0, 0)
            }
            state = 2
            closedAt = now()
            cond.signalAll()
        }
    }

    internal fun abort(reason: String) {
        lock.withLock {
            if (state == 2) return
            if (state == 1) stack.sendSegment(localPort, remotePort, sndNxt, rcvNxt, UserTcpStack.RST or UserTcpStack.ACK, 0, null, 0, 0, 0)
            error = reason
            state = 2
            closedAt = now()
            cond.signalAll()
        }
    }

    /** One-line TCP state for the session log. */
    fun diag(): String = lock.withLock {
        "tcp[inflight=${sndNxt - sndUna} wnd=$peerWnd mss=$mss rto=$rto retx=$retxTotal]"
    }

    private fun fail(reason: String) {
        stack.note("connection failed: $reason")
        error = reason
        state = 2
        closedAt = now()
        cond.signalAll()
    }
}
