package app.gossi.speech

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import app.gossi.audio.MicCapture
import app.gossi.core.Lang

/**
 * Keeps Android's SpeechRecognizer listening continuously: every time it finishes an utterance
 * (or times out) a new session is started. Main thread only.
 */
class RecognizerLoop(private val context: Context, private val cb: Callbacks) {

    interface Callbacks {
        /** Smart mode returns a pipe with Gossi's own audio; null lets the recogniser use the mic. */
        fun openAudioSource(): ParcelFileDescriptor?
        fun onSessionEnd()
        fun onPartial(text: String)
        fun onFinal(text: String, detectedLocale: String?)
        fun onLanguageDetected(locale: String)
        fun onListening()
        fun onError(code: Int, consecutive: Int)
    }

    companion object {
        private const val TAG = "GossiRecognizer"

        fun errorName(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
            SpeechRecognizer.ERROR_NETWORK -> "network error"
            SpeechRecognizer.ERROR_AUDIO -> "audio error"
            SpeechRecognizer.ERROR_SERVER -> "server error"
            SpeechRecognizer.ERROR_CLIENT -> "client error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech"
            SpeechRecognizer.ERROR_NO_MATCH -> "no match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recogniser busy"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission missing"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "too many requests"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "server disconnected"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language not supported"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language pack not downloaded"
            else -> "error $code"
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var paused = false
    private var sessionActive = false
    private var consecutiveErrors = 0
    private var sessionToken = 0

    /** Language the next session listens in. */
    var locale: String = Lang.HINDI.locale
    /** Ask Android (14+) to detect / switch between our languages automatically. */
    var autoDetect = true
    var useAudioSource = false
    var onDevice = false
    var preferOffline = false

    fun start() {
        if (running) return
        running = true
        paused = false
        consecutiveErrors = 0
        createRecognizer()
        begin()
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "destroy", e)
        }
        recognizer = null
        if (sessionActive) {
            sessionActive = false
            cb.onSessionEnd()
        }
    }

    /** Stop listening temporarily (e.g. while Gossi talks through the phone speaker). */
    fun pause() {
        if (paused) return
        paused = true
        if (sessionActive) {
            try { recognizer?.cancel() } catch (_: Exception) {}
            sessionActive = false
            cb.onSessionEnd()
        }
    }

    fun resume() {
        if (!paused) return
        paused = false
        if (running && !sessionActive) schedule(50)
    }

    private fun createRecognizer() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        val useOnDevice = onDevice && Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        recognizer = (if (useOnDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)).also { it.setRecognitionListener(listener) }
    }

    private fun schedule(delayMs: Long, recreate: Boolean = false) {
        main.removeCallbacksAndMessages(null)
        main.postDelayed({
            if (!running) return@postDelayed
            if (recreate || recognizer == null) createRecognizer()
            begin()
        }, delayMs)
    }

    private fun begin() {
        if (!running || paused || sessionActive) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            if (autoDetect && Build.VERSION.SDK_INT >= 34) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, Lang.ALL_LOCALES)
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, Lang.ALL_LOCALES)
            }
        }
        if (useAudioSource && Build.VERSION.SDK_INT >= 33) {
            val source = cb.openAudioSource()
            if (source != null) {
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, MicCapture.SAMPLE_RATE)
            }
        }
        sessionActive = true
        sessionToken++
        try {
            recognizer!!.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed", e)
            endSession(1000, recreate = true)
        }
    }

    private fun endSession(delayMs: Long, recreate: Boolean = false) {
        if (sessionActive) {
            sessionActive = false
            cb.onSessionEnd()
        }
        if (running && !paused) schedule(delayMs, recreate)
    }

    private fun firstResult(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun detectedLanguage(b: Bundle?): String? =
        if (Build.VERSION.SDK_INT >= 34) b?.getString(SpeechRecognizer.DETECTED_LANGUAGE) else null

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = cb.onListening()
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.let(cb::onPartial)
        }

        override fun onResults(results: Bundle?) {
            if (!sessionActive) return
            consecutiveErrors = 0
            val text = firstResult(results)
            val detected = detectedLanguage(results)
            // End the session first so the next one starts while we translate.
            endSession(60)
            if (text != null) cb.onFinal(text, detected)
        }

        override fun onError(error: Int) {
            if (!sessionActive) return
            consecutiveErrors++
            val token = sessionToken
            cb.onError(error, consecutiveErrors)
            // The callback may have stopped or restarted us.
            if (token != sessionToken || !sessionActive || !running) return
            val backoff = consecutiveErrors.coerceAtMost(5)
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> endSession(60)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> endSession(400L * backoff, recreate = true)
                SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_AUDIO -> endSession(300L * backoff, recreate = true)
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> endSession(1000L * backoff)
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> endSession(5000)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> stop()
                else -> endSession(800L * backoff, recreate = true)
            }
        }

        override fun onLanguageDetection(results: Bundle) {
            detectedLanguage(results)?.let(cb::onLanguageDetected)
        }
    }
}
