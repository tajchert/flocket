package com.mtajchert.flocket.core.ssh

/** POSIX single-quote escaping for building an exec command line. */
object ShellQuote {
    // ASCII-only safe set; `=` is excluded so a first word can never be read as an assignment.
    fun quote(arg: String): String =
        if (arg.isNotEmpty() &&
            arg.all { (it in 'a'..'z') || (it in 'A'..'Z') || (it in '0'..'9') || it in "_-./:@%+," }
        ) {
            arg
        } else {
            "'" + arg.replace("'", "'\\''") + "'"
        }

    fun join(command: List<String>): String = command.joinToString(" ") { quote(it) }
}

/** Fixed-capacity tail buffer; thread-safe. */
internal class ByteRing(
    private val capacity: Int,
) {
    private val data = ByteArray(capacity)
    private var size = 0
    private var start = 0

    @Synchronized
    fun append(
        bytes: ByteArray,
        length: Int,
    ) {
        for (i in 0 until length) {
            if (size < capacity) {
                data[(start + size) % capacity] = bytes[i]
                size++
            } else {
                data[start] = bytes[i]
                start = (start + 1) % capacity
            }
        }
    }

    @Synchronized
    fun text(): String {
        val out = ByteArray(size) { data[(start + it) % capacity] }
        return String(out, Charsets.UTF_8)
    }
}
