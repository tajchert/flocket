package com.mtajchert.flocket.core.protocol.wire

/**
 * Reads the plain-mode hello reply: bytes up to the first `\n`. Everything after it is returned
 * as [Result.remainder] and belongs to the frame decoder (SPEC gotcha #2: one read can contain
 * the reply and the first frames).
 *
 * Rejects non-protocol output early: the first non-blank byte must be `{` (gotcha #27, e.g. an
 * SSH banner or MOTD printed to stdout).
 */
class HelloLineReader(
    private val maxLine: Int = 1 shl 20,
) {
    class Result(
        val line: ByteArray,
        val remainder: ByteArray,
    )

    private var buf = ByteArray(1024)
    private var len = 0
    private var lineStart = -1

    fun feed(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Result? {
        if (len + length > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + length))
        System.arraycopy(bytes, offset, buf, len, length)
        len += length
        if (lineStart < 0) {
            val first = (0 until len).firstOrNull { !buf[it].isBlankByte() }
            if (first != null) {
                if (buf[first] != '{'.code.toByte()) throw garbage()
                lineStart = first
            }
        }
        // Blank lines before the reply (an empty `echo` in a login script) are skipped, not parsed.
        if (lineStart < 0) return null.also { checkMax() }
        val nl = (lineStart until len).firstOrNull { buf[it] == '\n'.code.toByte() }
        if (nl == null) {
            checkMax()
            return null
        }
        return Result(buf.copyOfRange(lineStart, nl), buf.copyOfRange(nl + 1, len))
    }

    private fun checkMax() {
        if (len > maxLine) {
            throw ProtocolException(
                "hello reply exceeds $maxLine bytes",
                ProtocolException.Reason.GARBAGE_BEFORE_HELLO,
            )
        }
    }

    private fun Byte.isBlankByte() =
        this == ' '.code.toByte() || this == '\n'.code.toByte() || this == '\r'.code.toByte() ||
            this == '\t'.code.toByte()

    private fun garbage(): ProtocolException {
        val sample =
            buildString {
                for (i in 0 until minOf(len, 80)) {
                    val c = buf[i].toInt() and 0xFF
                    append(if (c in 0x20..0x7E) c.toChar() else '.')
                }
            }
        return ProtocolException(
            "remote shell printed text to stdout before the hc reply (SSH banner / MOTD?): \"$sample\"",
            ProtocolException.Reason.GARBAGE_BEFORE_HELLO,
        )
    }
}
