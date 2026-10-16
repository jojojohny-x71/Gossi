// Self-test harness: runs Gossi's offline engine code (the same Kotlin + JNI the app uses)
// on recorded speech. Built and run by .github/workflows/self-test.yml.
import app.gossi.audio.SpeakerTracker
import app.gossi.audio.VoiceFeatureAccumulator
import app.gossi.core.Lang
import app.gossi.offline.TranslationFilter
import app.gossi.offline.UtteranceSegmenter
import app.gossi.offline.WhisperNative
import app.gossi.offline.readWav16k
import java.io.File

private const val FRAME = 320 // 20 ms at 16 kHz, same as MicCapture.FRAME

fun main(args: Array<String>) {
    val (modelPath, dir, outPath) = args
    val threads = 4
    val allowed = Lang.entries.map { it.code }.toTypedArray()
    val t0 = System.nanoTime()
    val w = WhisperNative.load(modelPath) ?: error("model failed to load")
    val loadMs = (System.nanoTime() - t0) / 1_000_000
    val out = File(outPath).bufferedWriter()
    out.write("# load_ms\t$loadMs\n")

    // 1) Single sentences, clean and noisy.
    for (line in File(dir, "manifest.tsv").readLines().filter { it.isNotBlank() }) {
        val (id, lang, wav, ref) = line.split("\t")
        val pcm16 = readWav16k(File(dir, wav))
        val pcm = FloatArray(pcm16.size) { pcm16[it] / 32768f }
        val s = System.nanoTime()
        val r = w.translate(pcm, null, allowed, threads, false) // same as the app's default
        val ms = (System.nanoTime() - s) / 1_000_000
        val en = r?.let { TranslationFilter.accept(it.english, it.noSpeechProb) } ?: "(dropped)"
        out.write("S\t$id\t$lang\t${r?.langCode}\t$ms\t${pcm.size / 16}\t$en\t$ref\t${r?.original ?: ""}\n")
    }

    // 2) Conversation through the app's segmenter + speaker tracker.
    val tracker = SpeakerTracker()
    val utterances = ArrayList<ShortArray>()
    val seg = UtteranceSegmenter { utterances.add(it) }
    val conv = readWav16k(File(dir, "conversation.wav"))
    var i = 0
    while (i + FRAME <= conv.size) {
        seg.addFrame(conv.copyOfRange(i, i + FRAME), FRAME)
        i += FRAME
    }
    repeat(100) { seg.addFrame(ShortArray(FRAME), FRAME) }
    for ((k, u) in utterances.withIndex()) {
        val feat = VoiceFeatureAccumulator().run {
            var j = 0
            while (j + FRAME <= u.size) {
                addFrame(u.copyOfRange(j, j + FRAME), FRAME)
                j += FRAME
            }
            take()
        }
        val speaker = feat?.let { tracker.assign(it).first } ?: -1
        val r = w.translate(FloatArray(u.size) { u[it] / 32768f }, null, allowed, threads, false)
        val en = r?.let { TranslationFilter.accept(it.english, it.noSpeechProb) } ?: "(dropped)"
        out.write("C\t$k\t$speaker\t${"%.0f".format(feat?.pitchHz ?: 0.0)}\t${r?.langCode}\t${u.size / 16}\t$en\n")
    }
    out.close()
    w.close()
}
