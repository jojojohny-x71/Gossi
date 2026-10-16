package app.gossi.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * English text-to-speech with a palette of distinct voices — one per speaker.
 * Uses the phone's TTS engine (Google Speech Services on Nothing phones), which works offline
 * for its installed voices.
 */
class VoiceBank(context: Context, private val onSpeakingChanged: (Boolean) -> Unit) {

    class Preset(val voice: Voice?, val pitch: Float, val label: String)

    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var pending = 0
    private var counter = 0
    var presets: List<Preset> = defaultPresets(emptyList())
        private set

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        main.post { onInit(status) }
    }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.language = Locale("en", "IN")
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                main.post { onSpeakingChanged(true) }
            }

            override fun onDone(utteranceId: String?) = finished()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished()

            override fun onError(utteranceId: String?, errorCode: Int) = finished()

            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished()
        })
        presets = defaultPresets(pickVoices())
        ready = true
    }

    private fun finished() {
        main.post {
            pending = (pending - 1).coerceAtLeast(0)
            if (pending == 0) onSpeakingChanged(false)
        }
    }

    private fun pickVoices(): List<Voice> {
        val all = try { tts.voices?.toList().orEmpty() } catch (_: Exception) { emptyList() }
        val english = all.filter {
            it.locale.language == "en" &&
                !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }
        val local = english.filter { !it.isNetworkConnectionRequired }
        val pool = if (local.size >= 2) local else english
        // Interleave accents (Indian, US, British, Australian…) so neighbouring speakers differ most.
        val byCountry = pool.groupBy { it.locale.country }
            .toSortedMap(compareBy<String> { countryRank(it) }.thenBy { it })
            .values.map { list -> list.sortedBy { it.name }.toMutableList() }
        val result = ArrayList<Voice>()
        while (result.size < 8 && byCountry.any { it.isNotEmpty() }) {
            for (list in byCountry) if (list.isNotEmpty()) result.add(list.removeAt(0))
        }
        return result.take(8)
    }

    private fun countryRank(c: String) = when (c) {
        "IN" -> 0; "US" -> 1; "GB" -> 2; "AU" -> 3; else -> 4
    }

    private fun defaultPresets(voices: List<Voice>): List<Preset> {
        val pitches = floatArrayOf(1.0f, 0.8f, 1.2f, 0.9f, 1.1f, 0.72f, 1.3f, 0.96f)
        return pitches.mapIndexed { i, pitch ->
            val v = if (voices.isEmpty()) null else voices[i % voices.size]
            val accent = when (v?.locale?.country) {
                "IN" -> "Indian"; "US" -> "American"; "GB" -> "British"; "AU" -> "Australian"
                null -> "Default"; else -> v.locale.displayCountry
            }
            val tone = when {
                pitch < 0.85f -> "deep"
                pitch < 0.97f -> "low"
                pitch > 1.15f -> "bright"
                pitch > 1.03f -> "high"
                else -> "natural"
            }
            Preset(v, pitch, "Voice ${i + 1} · $accent, $tone")
        }
    }

    /** Index of a preset not yet used, preferring deep presets for low voices and vice versa. */
    fun choosePreset(pitchHz: Double, used: Set<Int>): Int {
        val free = presets.indices.filter { it !in used }.ifEmpty { presets.indices.toList() }
        val wantLow = pitchHz in 1.0..165.0
        val wantHigh = pitchHz > 165.0
        return free.firstOrNull { (wantLow && presets[it].pitch < 1f) || (wantHigh && presets[it].pitch > 1f) }
            ?: free.first()
    }

    fun speak(text: String, presetIndex: Int, baseRate: Float) {
        if (!ready || text.isBlank()) return
        val p = presets[presetIndex.mod(presets.size)]
        p.voice?.let { tts.voice = it }
        tts.setPitch(p.pitch)
        // Speed up a little when sentences are piling up so we don't fall behind.
        val rate = baseRate * when {
            pending >= 3 -> 1.35f
            pending >= 1 -> 1.15f
            else -> 1f
        }
        tts.setSpeechRate(rate)
        pending++
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "g${counter++}")
    }

    fun preview(presetIndex: Int, baseRate: Float) = speak("Hello, this is how I will sound.", presetIndex, baseRate)

    fun stopSpeaking() {
        if (!ready) return
        tts.stop()
        pending = 0
        onSpeakingChanged(false)
    }

    fun shutdown() {
        tts.shutdown()
    }
}
