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
import app.gossi.audio.EarbudMic
import app.gossi.audio.MicCapture
import app.gossi.audio.VoiceFeature
import app.gossi.offline.ModelStore
import app.gossi.offline.OfflinePipeline
import app.gossi.offline.WhisperModel
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
    private lateinit var mlKit: MlKitTranslator
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

    /** Offline model download state, for the settings screen. */
    var downloadingModel: WhisperModel? = null; private set
    var downloadProgress = 0; private set
    var downloadMessage: String? = null; private set
    /** Set when Start was pressed without the offline model; the UI offers the download. */
    var needsModel = false; private set
    /** Utterances waiting for the offline translator. */
    var pendingUtterances = 0; private set
    var usingEarbudMic = false; private set
    val inputLevel: Float get() = offline?.inputLevel ?: 0f

    private var offline: OfflinePipeline? = null
    private lateinit var earbudMic: EarbudMic
    private var earbudInput: AudioDeviceInfo? = null
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
        mlKit = MlKitTranslator()
        translators = TranslatorChain(GoogleFreeTranslator(), mlKit)
        voices = VoiceBank(ctx) { speaking -> onTtsSpeaking(speaking) }
        audioManager = ctx.getSystemService(AudioManager::class.java)
        earbudMic = EarbudMic(audioManager)
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
        needsModel = false
        if (settings.engine == RecognitionEngine.OFFLINE) {
            if (!ModelStore.isDownloaded(ctx, settings.whisperModel)) {
                needsModel = true
                status = "Download the offline model first (Settings → Offline model)."
                changed()
                return
            }
        } else if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            status = "No speech recognition service found. Install or enable \"Speech Services by Google\"."
            changed()
            return
        }
        running = true
        voices.duckOthers = settings.duckOthers
        routeMic()
        if (settings.engine == RecognitionEngine.OFFLINE) {
            startOffline(ctx)
            return
        }
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
        offline?.stop()
        offline = null
        pendingUtterances = 0
        earbudMic.disable()
        usingEarbudMic = false
        earbudInput = null
        voices.setCallAudio(false)
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

    private fun routeMic() {
        earbudInput = if (settings.mic == MicSource.EARBUDS) earbudMic.enable() else null
        usingEarbudMic = earbudInput != null
        voices.setCallAudio(usingEarbudMic)
    }

    private fun micLabel() = when {
        usingEarbudMic -> "earbud mic"
        settings.mic == MicSource.EARBUDS -> "phone mic (earbud mic unavailable)"
        else -> "phone mic"
    }

    private fun startOffline(ctx: Context) {
        val pipeline = OfflinePipeline(
            modelFile = ModelStore.file(ctx, settings.whisperModel),
            strongNoiseSuppression = settings.strongNoiseSuppression,
            inputDevice = earbudInput,
            callbacks = object : OfflinePipeline.Callbacks {
                override fun onReady() {
                    val lang = settings.language?.label ?: "Auto"
                    status = "Listening offline · $lang · ${micLabel()}"
                    changed()
                }

                override fun onWorking(queued: Int) {
                    pendingUtterances = queued
                    changed()
                }

                override fun onResult(lang: Lang, english: String, original: String, feature: VoiceFeature?) =
                    handleTranslated(lang, original, english, feature)

                override fun onError(message: String) {
                    status = message
                    stop()
                    status = message
                    changed()
                }
            },
        )
        pipeline.forcedLang = settings.language
        pipeline.showOriginal = settings.showOriginal
        offline = pipeline
        applyEchoGate()
        pipeline.start()
        status = "Loading offline model (${settings.whisperModel.sizeMb} MB)…"
        changed()
    }

    /** Offline engine result: already in English. */
    private fun handleTranslated(lang: Lang, original: String, english: String, feature: VoiceFeature?) {
        if (!running) return
        val speaker = resolveSpeaker(lang, feature, settings.speakerDetection)
        speaker.segmentCount++
        val seg = Segment(nextSegmentId++, speaker.id, lang, original)
        seg.english = english
        seg.engine = if (lang == Lang.ENGLISH) "already English" else "on-phone"
        segments.add(seg)
        while (segments.size > MAX_SEGMENTS) segments.removeAt(0)
        val speak = !speaker.muted && (lang != Lang.ENGLISH || settings.speakEnglish)
        if (speak) voices.speak(english, speaker.presetIndex, settings.speechRate)
        changed()
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
        val m = MicCapture(
            strongNoiseSuppression = settings.strongNoiseSuppression,
            preferredDevice = earbudInput,
            onFrame = { buf, n ->
                acc.addFrame(buf, n)
                p.push(buf, n)
            },
            onError = { msg -> main.post { fallbackToSimple("Microphone problem ($msg)") } },
        )
        mic = m
        // The Bluetooth call link takes a moment to come up.
        if (earbudInput != null) main.postDelayed({ if (mic === m) m.start() }, 1000) else m.start()
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
            status = "Listening · $lang · $mode mode · ${if (smartMode) micLabel() else "phone mic"}"
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

        val speaker = resolveSpeaker(lang, if (smartMode) features?.take() else null, smartMode)
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

    private fun resolveSpeaker(lang: Lang, feature: VoiceFeature?, detectSpeakers: Boolean): Speaker {
        if (detectSpeakers) {
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
        offline?.muted = gate
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
        offline?.forcedLang = lang
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
        voices.duckOthers = new.duckOthers
        offline?.showOriginal = new.showOriginal
        val needsRestart = old.engine != new.engine || old.mic != new.mic || old.whisperModel != new.whisperModel ||
            old.speakerDetection != new.speakerDetection ||
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
            val summary = mlKit.downloadAll()
            main.post { onDone(summary) }
        }
    }

    fun isModelDownloaded(m: WhisperModel) = appContext?.let { ModelStore.isDownloaded(it, m) } ?: false

    fun downloadModel(m: WhisperModel) {
        val ctx = appContext ?: return
        if (downloadingModel != null) return
        downloadingModel = m
        downloadProgress = 0
        downloadMessage = null
        changed()
        Thread({
            try {
                ModelStore.download(ctx, m) { pct -> main.post { downloadProgress = pct; changed() } }
                main.post {
                    downloadingModel = null
                    downloadMessage = "${m.label.substringBefore(" (")} model ready — works with no internet."
                    needsModel = false
                    updateSettings(settings.copy(whisperModel = m, engine = RecognitionEngine.OFFLINE))
                }
            } catch (e: Exception) {
                main.post {
                    downloadingModel = null
                    downloadMessage = "Download stopped: ${e.message}"
                    changed()
                }
            }
        }, "gossi-download").start()
    }

    fun cancelDownload() = ModelStore.cancel()

    fun deleteModel(m: WhisperModel) {
        val ctx = appContext ?: return
        if (running && settings.whisperModel == m && settings.engine == RecognitionEngine.OFFLINE) stop()
        ModelStore.delete(ctx, m)
        changed()
    }
}
