package app.gossi.offline

import java.io.File

/** Reads the PCM samples of a 16-bit mono WAV file (assumed 16 kHz). */
fun readWav16k(file: File): ShortArray {
    val b = file.readBytes()
    var p = 12
    while (p + 8 <= b.size) {
        val id = String(b, p, 4, Charsets.US_ASCII)
        val len = (b[p + 4].toInt() and 0xFF) or ((b[p + 5].toInt() and 0xFF) shl 8) or
            ((b[p + 6].toInt() and 0xFF) shl 16) or ((b[p + 7].toInt() and 0xFF) shl 24)
        if (id == "data") {
            val n = minOf(len, b.size - p - 8) / 2
            return ShortArray(n) { k ->
                val o = p + 8 + 2 * k
                ((b[o].toInt() and 0xFF) or (b[o + 1].toInt() shl 8)).toShort()
            }
        }
        p += 8 + len + (len and 1)
    }
    throw IllegalArgumentException("No PCM data in ${file.name}")
}
