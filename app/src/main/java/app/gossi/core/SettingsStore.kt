package app.gossi.core

import android.content.Context
import app.gossi.offline.WhisperModel

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("gossi", Context.MODE_PRIVATE)

    fun load(): Settings {
        val d = Settings()
        return Settings(
            engine = enumOr(prefs.getString("engine", null), d.engine),
            mic = enumOr(prefs.getString("mic", null), d.mic),
            whisperModel = enumOr(prefs.getString("whisperModel", null), d.whisperModel),
            duckOthers = prefs.getBoolean("duckOthers", d.duckOthers),
            showOriginal = prefs.getBoolean("showOriginal", d.showOriginal),
            language = prefs.getString("language", null)?.let { name -> Lang.entries.firstOrNull { it.name == name } },
            translationMode = prefs.getString("translationMode", null)
                ?.let { name -> TranslationMode.entries.firstOrNull { it.name == name } } ?: d.translationMode,
            speechRate = prefs.getFloat("speechRate", d.speechRate),
            speakEnglish = prefs.getBoolean("speakEnglish", d.speakEnglish),
            speakerDetection = prefs.getBoolean("speakerDetection", d.speakerDetection),
            strongNoiseSuppression = prefs.getBoolean("strongNoiseSuppression", d.strongNoiseSuppression),
            speakerSensitivity = prefs.getFloat("speakerSensitivity", d.speakerSensitivity),
            onDeviceRecognition = prefs.getBoolean("onDeviceRecognition", d.onDeviceRecognition),
        )
    }

    fun save(s: Settings) {
        prefs.edit()
            .putString("engine", s.engine.name)
            .putString("mic", s.mic.name)
            .putString("whisperModel", s.whisperModel.name)
            .putBoolean("duckOthers", s.duckOthers)
            .putBoolean("showOriginal", s.showOriginal)
            .putString("language", s.language?.name)
            .putString("translationMode", s.translationMode.name)
            .putFloat("speechRate", s.speechRate)
            .putBoolean("speakEnglish", s.speakEnglish)
            .putBoolean("speakerDetection", s.speakerDetection)
            .putBoolean("strongNoiseSuppression", s.strongNoiseSuppression)
            .putFloat("speakerSensitivity", s.speakerSensitivity)
            .putBoolean("onDeviceRecognition", s.onDeviceRecognition)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
