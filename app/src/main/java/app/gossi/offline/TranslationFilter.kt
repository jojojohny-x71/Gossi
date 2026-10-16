package app.gossi.offline

/** Cleans whisper output and drops what it produces on noise or silence. Shared with the self-test harness. */
object TranslationFilter {
    private val bracketed = Regex("\\[[^\\]]*\\]|\\([^)]*\\)")
    private val spaces = Regex("\\s+")

    private val hallucinations = setOf(
        "you", "thank you", "thanks for watching", "thank you for watching", "bye", "subtitles by the amara.org community",
        "please subscribe", "thank you very much", "music", "applause",
    )

    private val sentenceEnd = Regex("(?<=[.!?।])\\s+")

    fun cleanup(s: String) = dropRepeats(s.replace(bracketed, " ").replace(spaces, " ").trim())

    /** Whisper sometimes loops ("I need coffee. I need coffee."): keep each sentence once in a row. */
    private fun dropRepeats(s: String): String {
        val out = ArrayList<String>()
        for (part in s.split(sentenceEnd)) {
            val key = part.lowercase().trim(' ', '.', '!', '?', ',', '।')
            if (key.isNotEmpty() && out.lastOrNull()?.lowercase()?.trim(' ', '.', '!', '?', ',', '।') == key) continue
            out.add(part)
        }
        return out.joinToString(" ")
    }

    /** @return the cleaned English line, or null if it should be ignored. */
    fun accept(english: String, noSpeechProb: Float): String? {
        val t = cleanup(english)
        if (t.isEmpty() || noSpeechProb > 0.6f) return null
        val key = t.lowercase().trim(' ', '.', '!', '?', ',')
        if (key.length < 2 || key in hallucinations) return null
        return t
    }
}
