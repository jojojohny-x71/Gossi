package app.gossi.offline

/** Cleans whisper output and drops what it produces on noise or silence. Shared with the self-test harness. */
object TranslationFilter {
    private val bracketed = Regex("\\[[^\\]]*\\]|\\([^)]*\\)")
    private val spaces = Regex("\\s+")

    private val hallucinations = setOf(
        "you", "thank you", "thanks for watching", "thank you for watching", "bye", "subtitles by the amara.org community",
        "please subscribe", "thank you very much", "music", "applause",
    )

    fun cleanup(s: String) = s.replace(bracketed, " ").replace(spaces, " ").trim()

    /** @return the cleaned English line, or null if it should be ignored. */
    fun accept(english: String, noSpeechProb: Float): String? {
        val t = cleanup(english)
        if (t.isEmpty() || noSpeechProb > 0.6f) return null
        val key = t.lowercase().trim(' ', '.', '!', '?', ',')
        if (key.length < 2 || key in hallucinations) return null
        return t
    }
}
