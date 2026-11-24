package app.gogglesstream

internal fun ByteArray.le32(off: Int): Int =
    (this[off].toInt() and 0xff) or
        ((this[off + 1].toInt() and 0xff) shl 8) or
        ((this[off + 2].toInt() and 0xff) shl 16) or
        ((this[off + 3].toInt() and 0xff) shl 24)

internal fun ByteArray.le16(off: Int): Int =
    (this[off].toInt() and 0xff) or ((this[off + 1].toInt() and 0xff) shl 8)

internal fun ByteArray.putLe32(off: Int, v: Int) {
    this[off] = v.toByte()
    this[off + 1] = (v ushr 8).toByte()
    this[off + 2] = (v ushr 16).toByte()
    this[off + 3] = (v ushr 24).toByte()
}

internal fun ByteArray.putLe16(off: Int, v: Int) {
    this[off] = v.toByte()
    this[off + 1] = (v ushr 8).toByte()
}

internal fun ByteArray.hex(len: Int = size): String =
    take(len).joinToString(":") { "%02x".format(it.toInt() and 0xff) }
