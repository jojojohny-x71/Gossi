package app.gossi.offline

import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import app.gossi.audio.MicCapture
import app.gossi.audio.VoiceFeature
import app.gossi.audio.VoiceFeatureAccumulator
import app.gossi.core.Lang
import java.io.File
import java.util.concurrent.LinkedBlockingDeque
import kotlin.math.max
import kotlin.math.min

/**
 * Fully offline path: mic → utterance segmenter → whisper.cpp (detect language + translate to
 * English) on the phone CPU. Results are delivered on the main thread.
 */
class OfflinePipeline(
    private val modelFile: File,
    private val strongNoiseSuppression: Boolean,
    private val inputDevice: AudioDeviceInfo?,
    private val callbacks: Callbacks,
    /** Self-test (debug builds): play this 16 kHz mono WAV in place of the microphone. */
    private val testWav: File? = null,
) {
    interface Callbacks {
        fun onReady()
        fun onWorking(queued: Int)
        fun onResult(lang: Lang, english: String, original: String, feature: VoiceFeature?)
        fun onError(message: String)
    }

    /** Settings read on every utterance. */
    @Volatile var forcedLang: Lang? = null
    @Volatile var showOriginal = false
    /** When true, mic audio is discarded (Gossi is talking through the phone speaker). */
    @Volatile var muted = false

    private val main = Handler(Looper.getMainLooper())
    private val queue = LinkedBlockingDeque<ShortArray>()
    @Volatile private var running = false
    private var worker: Thread? = null
    @Volatile private var mic: MicCapture? = null
    @Volatile private var feeder: Thread? = null
    private val segmenter = UtteranceSegmenter { utterance ->
        // Keep latency bounded: if we're far behind, drop the oldest speech.
        while (queue.size >= 4) queue.pollFirst()
        queue.offerLast(utterance)
        val n = queue.size
        main.post { callbacks.onWorking(n) }
    }

    val inputLevel: Float get() = segmenter.level

    fun start() {
        if (running) return
        running = true
        worker = Thread({ work() }, "gossi-whisper").apply { start() }
    }

    fun stop() {
        running = false
        mic?.stop()
        mic = null
        feeder?.interrupt()
        feeder = null
        worker?.interrupt()
        worker = null
        queue.clear()
    }

    private fun work() {
        val whisper = WhisperNative.load(modelFile.path)
        if (whisper == null) {
            main.post {
                callbacks.onError(
                    if (!WhisperNative.available) "Offline engine missing from this build"
                    else "Offline model is damaged — delete and download it again in Settings",
                )
            }
            running = false
            return
        }
        // Start listening only once the model is loaded.
        if (testWav != null) {
            feeder = Thread({ feedWav(testWav) }, "gossi-wav").apply { start() }
            main.post { callbacks.onReady() }
        } else startMic()

        val threads = min(4, max(2, Runtime.getRuntime().availableProcessors() - 2))
        val allowed = Lang.entries.map { it.code }.toTypedArray()
        try {
            loop(whisper, threads, allowed)
        } finally {
            whisper.close()
        }
    }

    private fun startMic() {
        val m = MicCapture(
            strongNoiseSuppression = strongNoiseSuppression,
            preferredDevice = inputDevice,
            onFrame = { buf, n -> if (muted) segmenter.reset() else segmenter.addFrame(buf, n) },
            onError = { msg -> main.post { callbacks.onError(msg) } },
        )
        mic = m
        m.start()
        main.post { callbacks.onReady() }
    }

    /** Plays a WAV file into the segmenter in real time, as if it were the microphone. */
    private fun feedWav(file: File) {
        val pcm = readWav16k(file)
        val frame = ShortArray(MicCapture.FRAME)
        var i = 0
        try {
            while (running && i + MicCapture.FRAME <= pcm.size) {
                System.arraycopy(pcm, i, frame, 0, MicCapture.FRAME)
                if (muted) segmenter.reset() else segmenter.addFrame(frame, MicCapture.FRAME)
                i += MicCapture.FRAME
                Thread.sleep(20)
            }
            frame.fill(0)
            repeat(100) { segmenter.addFrame(frame, MicCapture.FRAME) } // trailing silence flushes the last line
        } catch (_: InterruptedException) {
        }
    }

    private fun loop(whisper: WhisperNative, threads: Int, allowed: Array<String>) {
        while (running) {
            val pcm16 = try { queue.takeFirst() } catch (_: InterruptedException) { break }
            val feature = VoiceFeatureAccumulator().run {
                var i = 0
                while (i + MicCapture.FRAME <= pcm16.size) {
                    addFrame(pcm16.copyOfRange(i, i + MicCapture.FRAME), MicCapture.FRAME)
                    i += MicCapture.FRAME
                }
                take()
            }
            val pcm = FloatArray(pcm16.size) { pcm16[it] / 32768f }
            val r = whisper.translate(pcm, forcedLang?.code, allowed, threads, showOriginal) ?: continue
            val english = TranslationFilter.accept(r.english, r.noSpeechProb) ?: continue
            val lang = Lang.fromLocaleTag(r.langCode) ?: Lang.HINDI
            val original = TranslationFilter.cleanup(r.original)
            val left = queue.size
            main.post {
                callbacks.onResult(lang, english, original, feature)
                callbacks.onWorking(left)
            }
        }
    }

}
