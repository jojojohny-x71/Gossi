package app.gossi.core

/** Languages Gossi listens for. Everything is translated into English. */
enum class Lang(val code: String, val locale: String, val label: String) {
    HINDI("hi", "hi-IN", "Hindi"),
    TAMIL("ta", "ta-IN", "Tamil"),
    KANNADA("kn", "kn-IN", "Kannada"),
    MALAYALAM("ml", "ml-IN", "Malayalam"),
    ENGLISH("en", "en-IN", "English");

    companion object {
        val SOURCES = listOf(HINDI, TAMIL, KANNADA, MALAYALAM)
        val ALL_LOCALES = ArrayList(entries.map { it.locale })

        fun fromLocaleTag(tag: String?): Lang? {
            if (tag.isNullOrBlank()) return null
            val code = tag.lowercase().substringBefore('-').substringBefore('_')
            return entries.firstOrNull { it.code == code }
        }

        /**
         * Works out the language from the script the recogniser wrote the text in.
         * Devanagari → Hindi, Tamil script → Tamil, etc. Mostly-Latin text → English.
         */
        fun detectFromScript(text: String): Lang? {
            var hi = 0; var ta = 0; var kn = 0; var ml = 0; var latin = 0
            for (ch in text) {
                when (ch.code) {
                    in 0x0900..0x097F -> hi++
                    in 0x0B80..0x0BFF -> ta++
                    in 0x0C80..0x0CFF -> kn++
                    in 0x0D00..0x0D7F -> ml++
                    in 0x41..0x5A, in 0x61..0x7A -> latin++
                }
            }
            val indic = mapOf(HINDI to hi, TAMIL to ta, KANNADA to kn, MALAYALAM to ml)
            val best = indic.maxByOrNull { it.value }!!
            return when {
                best.value == 0 && latin == 0 -> null
                // Any Indic script at all means it needs translating (code-mixed speech is common).
                best.value > 0 && best.value * 3 >= latin / 2 -> best.key
                else -> ENGLISH
            }
        }
    }
}
