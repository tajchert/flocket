package com.mtajchert.flocket.core.protocol.wire

/** Frame kinds (protocol/SPEC.md §3.2). Unknown kinds are ignored by both sides. */
object FrameKind {
    const val DATA = 0
    const val CREDIT = 1
    const val CLOSE = 2
    const val RESIZE = 3
    const val FOCUS = 4
    const val FACTS = 5

    fun name(kind: Int): String =
        when (kind) {
            DATA -> "DATA"
            CREDIT -> "CREDIT"
            CLOSE -> "CLOSE"
            RESIZE -> "RESIZE"
            FOCUS -> "FOCUS"
            FACTS -> "FACTS"
            else -> "UNKNOWN($kind)"
        }
}

/** Wire limits and constants (protocol/SPEC.md §3.3, Appendix A). */
object WireLimits {
    const val HEADER_SIZE = 9
    const val MAX_PAYLOAD = 16 * 1024 * 1024

    /** Initial credit of every stream channel. */
    const val WINDOW = 262_144

    /** One-off credit boost sent after subscribing to events. */
    const val EVENTS_CREDIT_BOOST = 1_048_576

    /** Cap on the undecoded buffer: one maximal frame plus slack for a couple of reads. */
    const val MAX_BUFFERED = MAX_PAYLOAD + 2 * 64 * 1024 + HEADER_SIZE

    /** Largest DATA frame this client sends. */
    const val MAX_OUTGOING_DATA = 64 * 1024
}

/**
 * One frame. [channel] is the u32 channel number (channels stay far below 2^31, so an Int is
 * enough; negative values would mean a corrupt stream and are never created by the core).
 */
class Frame(
    val channel: Int,
    val kind: Int,
    val payload: ByteArray,
) {
    override fun toString(): String = "Frame(channel=$channel, kind=${FrameKind.name(kind)}, len=${payload.size})"
}

/** The byte stream violated the protocol. The connection must be dropped. */
class ProtocolException(
    message: String,
    val reason: Reason = Reason.MALFORMED,
    cause: Throwable? = null,
) : java.io.IOException(message, cause) {
    enum class Reason {
        /** Non-protocol bytes before/instead of the hello reply, typically an SSH banner on stdout. */
        GARBAGE_BEFORE_HELLO,
        OVERSIZE_FRAME,
        BUFFER_OVERFLOW,
        MALFORMED,
    }
}
