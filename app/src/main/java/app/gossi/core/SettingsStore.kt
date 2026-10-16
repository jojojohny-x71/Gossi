package app.gossi.core

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("gossi", Context.MODE_PRIVATE)

    fun load(): Settings {
        val d = Settings()
        return Settings(
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
}
