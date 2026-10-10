package au.chris.auxlink

/** Splits bytes from the Pi into lines (UTF-8, newline-terminated). */
class PiLines(private val onLine: (String) -> Unit) {
    private val buf = java.io.ByteArrayOutputStream()

    fun feed(data: ByteArray, n: Int) {
        for (i in 0 until n) {
            val b = data[i]
            if (b == '\n'.code.toByte()) {
                val line = buf.toString("UTF-8").trim()
                buf.reset()
                if (line.isNotEmpty()) onLine(line)
            } else if (buf.size() < 65536) {
                buf.write(b.toInt())
            }
        }
    }
}
