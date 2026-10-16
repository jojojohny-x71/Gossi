package app.gossi.audio

import android.os.ParcelFileDescriptor
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue

/**
 * Streams our own (noise-suppressed) mic audio into Android's speech recogniser through a pipe,
 * so Gossi can analyse the same audio for speaker detection.
 *
 * A fresh pipe is opened for every recognition session. Audio arriving between sessions is kept
 * in a short pre-roll so the start of the next sentence isn't lost.
 */
class PcmPipe {
    companion object {
        private const val PREROLL_FRAMES = 60 // 1.2 s
    }

    private val queue = LinkedBlockingQueue<ByteArray>(500)
    private val preroll = ArrayDeque<ByteArray>()
    @Volatile private var out: OutputStream? = null
    private var readSide: ParcelFileDescriptor? = null
    @Volatile private var running = true

    /** When true, silence is sent instead of mic audio (used to stop echo from the phone speaker). */
    @Volatile var muted = false

    private val writer = Thread({
        while (running) {
            val b = try { queue.take() } catch (_: InterruptedException) { break }
            val o = out
            if (o == null) {
                synchronized(preroll) {
                    preroll.addLast(b)
                    while (preroll.size > PREROLL_FRAMES) preroll.removeFirst()
                }
            } else {
                try {
                    o.write(b)
                } catch (_: IOException) {
                    // Recogniser closed its end; drop until the next session.
                    if (out === o) out = null
                }
            }
        }
    }, "gossi-pipe").apply { start() }

    /** Mic thread. */
    fun push(frame: ShortArray, n: Int) {
        val bytes = ByteArray(n * 2)
        if (!muted) {
            for (i in 0 until n) {
                val s = frame[i].toInt()
                bytes[2 * i] = (s and 0xFF).toByte()
                bytes[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
            }
        }
        if (!queue.offer(bytes)) {
            queue.poll()
            queue.offer(bytes)
        }
    }

    /** Main thread. Returns the read end to hand to the recogniser. */
    fun openSession(): ParcelFileDescriptor {
        closeSession()
        val (r, w) = ParcelFileDescriptor.createPipe()
        readSide = r
        val os = ParcelFileDescriptor.AutoCloseOutputStream(w)
        synchronized(preroll) {
            try {
                for (b in preroll) os.write(b)
            } catch (_: IOException) {
            }
            preroll.clear()
        }
        out = os
        return r
    }

    fun closeSession() {
        val o = out
        out = null
        try { o?.close() } catch (_: IOException) {}
        try { readSide?.close() } catch (_: IOException) {}
        readSide = null
    }

    fun release() {
        running = false
        writer.interrupt()
        closeSession()
    }
}
