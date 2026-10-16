package app.gossi.translate

import app.gossi.core.Lang
import app.gossi.core.TranslationMode
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Blocking translator into English. Called on a worker thread. */
interface Translator {
    val name: String
    fun supports(from: Lang): Boolean
    fun translate(text: String, from: Lang): String
}

/**
 * Google Translate's free web endpoint (no API key). Best quality for Indian languages,
 * needs internet. Unofficial — it may rate-limit heavy use, in which case Gossi falls back
 * to the offline translator.
 */
class GoogleFreeTranslator : Translator {
    override val name = "Google (online)"

    override fun supports(from: Lang) = true

    override fun translate(text: String, from: Lang): String {
        val url = URL(
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=${from.code}&tl=en&dt=t&q=" +
                URLEncoder.encode(text, "UTF-8"),
        )
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Gossi")
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("Google Translate HTTP $code")
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val parts = JSONArray(body).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val piece = parts.optJSONArray(i)?.optString(0)
                if (!piece.isNullOrEmpty() && piece != "null") sb.append(piece)
            }
            return sb.toString().trim().ifEmpty { throw IllegalStateException("Empty translation") }
        } finally {
            conn.disconnect()
        }
    }
}

/** Tries the preferred translator, then the other one. */
class TranslatorChain(private val online: Translator, private val offline: Translator) {

    class Result(val text: String, val engine: String)

    fun translate(text: String, from: Lang, mode: TranslationMode): Result {
        val order = when (mode) {
            TranslationMode.ONLINE_FIRST -> listOf(online, offline)
            TranslationMode.OFFLINE_FIRST -> listOf(offline, online)
            TranslationMode.OFFLINE_ONLY -> listOf(offline)
        }
        var lastError: Exception? = null
        for (t in order) {
            if (!t.supports(from)) continue
            try {
                return Result(t.translate(text, from), t.name)
            } catch (e: Exception) {
                lastError = e
            }
        }
        val reason = when {
            lastError != null -> lastError.message ?: lastError.javaClass.simpleName
            mode == TranslationMode.OFFLINE_ONLY -> "${from.label} has no offline translator — allow online translation in Settings"
            else -> "No translator available"
        }
        throw IllegalStateException(reason, lastError)
    }
}
