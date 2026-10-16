package app.gossi.offline

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Energy-based voice activity detection: cuts the mic stream into utterances
 * (speech followed by ~0.6 s of silence, or 12 s max). Mic thread only.
 */
class UtteranceSegmenter(private val onUtterance: (ShortArray) -> Unit) {
    companion object {
        private const val FRAME = 320                 // 20 ms
        private const val PREROLL_FRAMES = 15         // 300 ms kept before speech starts
        private const val END_SILENCE_FRAMES = 30     // 600 ms of silence ends an utterance
        private const val MIN_SPEECH_FRAMES = 20      // ignore < 400 ms blips
        private const val MAX_FRAMES = 600            // 12 s hard cut
    }

    private val preroll = ArrayDeque<ShortArray>()
    private val current = ArrayList<ShortArray>()
    private var speaking = false
    private var speechFrames = 0
    private var silenceRun = 0
    private var noiseFloor = 300.0

    /** Live input level 0..1 for the UI meter. */
    @Volatile var level = 0f
        private set

    fun addFrame(frame: ShortArray, n: Int) {
        val copy = frame.copyOf(n)
        var e = 0.0
        for (s in copy) e += s.toDouble() * s
        val rms = sqrt(e / max(1, n))
        level = (rms / 6000.0).coerceIn(0.0, 1.0).toFloat()

        val voiced = rms > max(noiseFloor * 3.0, 250.0)
        if (!voiced) {
            noiseFloor = if (rms < noiseFloor) 0.9 * noiseFloor + 0.1 * rms else noiseFloor * 1.002
            noiseFloor = noiseFloor.coerceIn(30.0, 4000.0)
        }

        if (!speaking) {
            preroll.addLast(copy)
            while (preroll.size > PREROLL_FRAMES) preroll.removeFirst()
            if (voiced) {
                speaking = true
                current.addAll(preroll)
                preroll.clear()
                speechFrames = 1
                silenceRun = 0
            }
            return
        }

        current.add(copy)
        if (voiced) {
            speechFrames++
            silenceRun = 0
        } else {
            silenceRun++
        }
        if (silenceRun >= END_SILENCE_FRAMES || current.size >= MAX_FRAMES) flush()
    }

    /** Drop everything (e.g. while Gossi itself is talking through the phone speaker). */
    fun reset() {
        preroll.clear()
        current.clear()
        speaking = false
        speechFrames = 0
        silenceRun = 0
    }

    private fun flush() {
        val frames = current.toList()
        val enough = speechFrames >= MIN_SPEECH_FRAMES
        reset()
        if (!enough) return
        val total = frames.sumOf { it.size }
        val out = ShortArray(total)
        var o = 0
        for (f in frames) {
            System.arraycopy(f, 0, out, o, f.size)
            o += f.size
        }
        onUtterance(out)
    }
}
