package app.gossi.core

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.SpeechRecognizer
import app.gossi.audio.MicCapture
import app.gossi.audio.PcmPipe
import app.gossi.audio.SpeakerTracker
import app.gossi.audio.VoiceFeatureAccumulator
import app.gossi.speech.RecognizerLoop
import app.gossi.translate.GoogleFreeTranslator
import app.gossi.translate.MlKitTranslator
import app.gossi.translate.TranslatorChain
import app.gossi.tts.VoiceBank
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The whole pipeline: mic → speech recognition → language → speaker → translation → voice.
 * Lives for the app process; all public methods are main-thread only.
 */
object Engine {

    fun interface Listener {
        fun onEngineChanged()
    }

    private const val MAX_SEGMENTS = 150

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    private var appContext: Context? = null
    private lateinit var store: SettingsStore
    private lateinit var voices: VoiceBank
    private lateinit var worker: ExecutorService
    private lateinit var offline: MlKitTranslator
    private lateinit var translators: TranslatorChain
    private lateinit var audioManager: AudioManager

    var settings = Settings(); private set
    var running = false; private set
    /** Smart mode = Gossi records the mic itself and detects individual speakers. */
    var smartMode = false; private set
    var status = "Tap Start to begin"; private set
    var partial = ""; private set
    var headphonesConnected = false; private set
    var listeningLang: Lang = Lang.HINDI; private set
    val segments = ArrayList<Segment>()
    val speakers = LinkedHashMap<Int, Speaker>()

    private var recognizer: RecognizerLoop? = null
    private var mic: MicCapture? = null
    private var pipe: PcmPipe? = null
    private var features: VoiceFeatureAccumulator? = null
    private val tracker = SpeakerTracker()
    private var lastSpeakerId: Int? = null
    private var nextSegmentId = 1L
    private var smartSuccesses = 0
    private var smartSuspicious = 0
    private var smartHardErrors = 0
    private var ttsSpeaking = false

    val voicePresets: List<VoiceBank.Preset> get() = voices.presets

    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        store = SettingsStore(ctx)
        settings = store.load()
        listeningLang = settings.language ?: Lang.HINDI
        tracker.sensitivity = settings.speakerSensitivity
        worker = Executors.newSingleThreadExecutor()
        offline = MlKitTranslator()
        translators = TranslatorChain(GoogleFreeTranslator(), offline)
        voices = VoiceBank(ctx) { speaking -> onTtsSpeaking(speaking) }
        audioManager = ctx.getSystemService(AudioManager::class.java)
        audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refreshHeadphones()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refreshHeadphones()
        }, main)
        refreshHeadphones()
    }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }
    private fun changed() = listeners.toList().forEach { it.onEngineChanged() }

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        val ctx = appContext ?: return
        if (running) return
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            status = "No speech recognition service found. Install or enable \"Speech Services by Google\"."
            changed()
            return
        }
        running = true
        smartSuccesses = 0; smartSuspicious = 0; smartHardErrors = 0
        smartMode = settings.speakerDetection && Build.VERSION.SDK_INT >= 33
        if (smartMode) startSmartAudio()
        recognizer = RecognizerLoop(ctx, recognizerCallbacks).also {
            configureRecognizer(it)
            it.start()
        }
        status = "Starting…"
        changed()
    }

    fun stop() {
        if (!running) return
        running = false
        recognizer?.stop()
        recognizer = null
        stopSmartAudio()
        voices.stopSpeaking()
        partial = ""
        status = "Stopped"
        changed()
    }

    private fun restartIfRunning() {
        if (!running) return
        stop()
        start()
    }

    private fun configureRecognizer(r: RecognizerLoop) {
        r.locale = listeningLang.locale
        r.autoDetect = settings.language == null
        r.useAudioSource = smartMode
        r.onDevice = settings.onDeviceRecognition
        r.preferOffline = !isOnline()
    }

    private fun startSmartAudio() {
        val acc = VoiceFeatureAccumulator()
        val p = PcmPipe()
        features = acc
        pipe = p
        mic = MicCapture(
            strongNoiseSuppression = settings.strongNoiseSuppression,
            onFrame = { buf, n ->
                acc.addFrame(buf, n)
                p.push(buf, n)
            },
            onError = { msg -> main.post { fallbackToSimple("Microphone problem ($msg)") } },
        ).also { it.start() }
        p.muted = ttsSpeaking && !headphonesConnected
    }

    private fun stopSmartAudio() {
        mic?.stop(); mic = null
        pipe?.release(); pipe = null
        features = null
    }

    /** The phone's recogniser refused our audio stream: let it use the mic directly. */
    private fun fallbackToSimple(reason: String) {
        if (!smartMode || !running) return
        smartMode = false
        recognizer?.stop()
        stopSmartAudio()
        status = "$reason — switched to Simple mode (speakers grouped by language)."
        recognizer?.let {
            configureRecognizer(it)
            it.start()
        }
        changed()
    }

    // ---------------------------------------------------------------- recogniser callbacks

    private val recognizerCallbacks = object : RecognizerLoop.Callbacks {
        override fun openAudioSource(): ParcelFileDescriptor? = pipe?.openSession()

        override fun onSessionEnd() {
            pipe?.closeSession()
        }

        override fun onListening() {
            val mode = if (smartMode) "Smart" else "Simple"
            val lang = if (settings.language == null) "Auto (${listeningLang.label})" else listeningLang.label
            status = "Listening · $lang · $mode mode"
            changed()
        }

        override fun onPartial(text: String) {
            partial = text
            changed()
        }

        override fun onFinal(text: String, detectedLocale: String?) = handleFinal(text, detectedLocale)

        override fun onLanguageDetected(locale: String) {
            if (settings.language != null) return
            val lang = Lang.fromLocaleTag(locale) ?: return
            if (lang != Lang.ENGLISH) switchListeningLanguage(lang)
        }

        override fun onError(code: Int, consecutive: Int) {
            if (smartMode && smartSuccesses == 0) {
                when (code) {
                    SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_CLIENT -> smartHardErrors++
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        if ((features?.voicedFrames() ?: 0) > 40) smartSuspicious++
                }
                if (smartHardErrors >= 3 || smartSuspicious >= 3) {
                    main.post { fallbackToSimple("Speech service ignored Gossi's audio stream") }
                    return
                }
            }
            when (code) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (partial.isNotEmpty()) { partial = ""; changed() }
                }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    status = "${listeningLang.label} isn't available in this speech service. " +
                        "Turn off on-device recognition or download the language in Android's speech settings."
                    changed()
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    status = "Microphone permission missing"
                    stop()
                }
                else -> if (consecutive >= 2) {
                    status = "Recogniser: ${RecognizerLoop.errorName(code)} — retrying…" +
                        if (!isOnline()) " (offline: download offline speech for your languages)" else ""
                    changed()
                }
            }
        }
    }

    private fun switchListeningLanguage(lang: Lang) {
        if (lang == listeningLang) return
        listeningLang = lang
        recognizer?.locale = lang.locale
        changed()
    }

    private fun handleFinal(text: String, detectedLocale: String?) {
        partial = ""
        if (smartMode) smartSuccesses++
        val scriptLang = Lang.detectFromScript(text)
        val lang = scriptLang ?: Lang.fromLocaleTag(detectedLocale) ?: listeningLang
        if (settings.language == null && scriptLang != null && scriptLang != Lang.ENGLISH) switchListeningLanguage(scriptLang)

        val speaker = resolveSpeaker(lang)
        speaker.segmentCount++
        val seg = Segment(nextSegmentId++, speaker.id, lang, text)
        segments.add(seg)
        while (segments.size > MAX_SEGMENTS) segments.removeAt(0)
        changed()

        if (lang == Lang.ENGLISH) {
            seg.english = text
            seg.engine = "already English"
            if (settings.speakEnglish && !speaker.muted) voices.speak(text, speaker.presetIndex, settings.speechRate)
            changed()
            return
        }
        val mode = settings.translationMode
        worker.execute {
            try {
                val result = translators.translate(text, lang, mode)
                main.post {
                    seg.english = result.text
                    seg.engine = result.engine
                    val s = speakers[seg.speakerId]
                    if (running && s != null && !s.muted) voices.speak(result.text, s.presetIndex, settings.speechRate)
                    changed()
                }
            } catch (e: Exception) {
                main.post {
                    seg.error = e.message ?: "Translation failed"
                    changed()
                }
            }
        }
    }

    private fun resolveSpeaker(lang: Lang): Speaker {
        if (smartMode) {
            val feature = features?.take()
            val id = if (feature != null) {
                val (id, isNew) = tracker.assign(feature)
                if (isNew || speakers[id] == null) createSpeaker(id, feature.pitchHz, "Speaker ${letter(speakers.size)}")
                if (feature.pitchHz > 0) speakers[id]?.pitchHz = feature.pitchHz
                id
            } else {
                // Too short to fingerprint — assume the last person kept talking.
                lastSpeakerId ?: createSpeaker(0, 0.0, "Speaker ${letter(speakers.size)}").id
            }
            lastSpeakerId = id
            return speakers.getValue(id)
        }
        // Simple mode: one "speaker" (and voice) per language.
        val id = 100 + lang.ordinal
        return speakers[id] ?: createSpeaker(id, 0.0, "${lang.label} speaker")
    }

    private fun createSpeaker(id: Int, pitchHz: Double, name: String): Speaker {
        speakers[id]?.let { return it }
        val used = speakers.values.map { it.presetIndex }.toSet()
        val s = Speaker(
            id = id,
            name = name,
            presetIndex = voices.choosePreset(pitchHz, used),
            colorIndex = speakers.size,
            pitchHz = pitchHz,
        )
        speakers[id] = s
        return s
    }

    private fun letter(i: Int) = if (i < 26) ('A' + i).toString() else (i + 1).toString()

    // ---------------------------------------------------------------- audio routing / echo

    private fun refreshHeadphones() {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val headphoneTypes = mutableSetOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        if (Build.VERSION.SDK_INT >= 31) headphoneTypes.add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        val now = outputs.any { it.type in headphoneTypes }
        if (now != headphonesConnected) {
            headphonesConnected = now
            applyEchoGate()
            changed()
        }
    }

    private fun onTtsSpeaking(speaking: Boolean) {
        ttsSpeaking = speaking
        applyEchoGate()
    }

    /** Without earbuds the mic would hear our own English voice, so stop listening while talking. */
    private fun applyEchoGate() {
        val gate = ttsSpeaking && !headphonesConnected
        if (smartMode) {
            pipe?.muted = gate
        } else {
            recognizer?.let { if (gate) it.pause() else it.resume() }
        }
    }

    private fun isOnline(): Boolean {
        val cm = appContext?.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // ---------------------------------------------------------------- UI actions

    fun setLanguage(lang: Lang?) {
        updateSettings(settings.copy(language = lang))
        listeningLang = lang ?: listeningLang
        recognizer?.let {
            it.locale = listeningLang.locale
            it.autoDetect = lang == null
        }
        changed()
    }

    fun updateSettings(new: Settings) {
        val old = settings
        settings = new
        store.save(new)
        tracker.sensitivity = new.speakerSensitivity
        val needsRestart = old.speakerDetection != new.speakerDetection ||
            old.strongNoiseSuppression != new.strongNoiseSuppression ||
            old.onDeviceRecognition != new.onDeviceRecognition
        if (needsRestart) restartIfRunning() else changed()
    }

    fun renameSpeaker(id: Int, name: String) {
        speakers[id]?.name = name.ifBlank { speakers[id]?.name ?: "" }
        changed()
    }

    fun setSpeakerVoice(id: Int, presetIndex: Int) {
        speakers[id]?.presetIndex = presetIndex
        changed()
    }

    fun toggleMute(id: Int) {
        speakers[id]?.let { it.muted = !it.muted }
        changed()
    }

    fun previewVoice(presetIndex: Int) = voices.preview(presetIndex, settings.speechRate)

    fun clearConversation() {
        segments.clear()
        speakers.clear()
        tracker.clear()
        lastSpeakerId = null
        features?.reset()
        voices.stopSpeaking()
        changed()
    }

    fun downloadOfflinePacks(onDone: (String) -> Unit) {
        worker.execute {
            val summary = offline.downloadAll()
            main.post { onDone(summary) }
        }
    }
}
