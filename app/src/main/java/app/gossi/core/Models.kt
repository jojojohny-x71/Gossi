package app.gossi.core

enum class TranslationMode(val label: String) {
    ONLINE_FIRST("Online first (Google, free) — falls back to offline"),
    OFFLINE_FIRST("Offline first (on-phone) — falls back to online"),
    OFFLINE_ONLY("Offline only (never uses internet for translation)"),
}

data class Settings(
    /** null = detect automatically. */
    val language: Lang? = null,
    val translationMode: TranslationMode = TranslationMode.ONLINE_FIRST,
    val speechRate: Float = 1.05f,
    /** Read English speakers out loud too (otherwise they are only shown on screen). */
    val speakEnglish: Boolean = false,
    /** Gossi records the mic itself so it can tell speakers apart (Android 13+). */
    val speakerDetection: Boolean = true,
    /** Uses the phone's call-grade noise suppression mic path. */
    val strongNoiseSuppression: Boolean = false,
    /** 0 = merge voices more readily, 1 = split into more speakers. */
    val speakerSensitivity: Float = 0.5f,
    /** Use Android's on-device recogniser instead of the default (usually online) one. */
    val onDeviceRecognition: Boolean = false,
)

class Speaker(
    val id: Int,
    var name: String,
    var presetIndex: Int,
    val colorIndex: Int,
    var muted: Boolean = false,
    var pitchHz: Double = 0.0,
    var segmentCount: Int = 0,
)

class Segment(
    val id: Long,
    val speakerId: Int,
    val lang: Lang,
    val original: String,
    var english: String? = null,
    var engine: String? = null,
    var error: String? = null,
)
