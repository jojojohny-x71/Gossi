package app.gossi.translate

import app.gossi.core.Lang
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.TimeUnit

/**
 * Google ML Kit on-device translation: free, runs fully offline once the ~30 MB language
 * model is downloaded. Covers Hindi, Tamil and Kannada (not Malayalam).
 */
class MlKitTranslator : Translator {
    override val name = "ML Kit (offline)"

    private val clients = HashMap<Lang, com.google.mlkit.nl.translate.Translator>()

    override fun supports(from: Lang) = from != Lang.ENGLISH && TranslateLanguage.fromLanguageTag(from.code) != null

    @Synchronized
    private fun client(from: Lang): com.google.mlkit.nl.translate.Translator {
        return clients.getOrPut(from) {
            val source = TranslateLanguage.fromLanguageTag(from.code)
                ?: throw UnsupportedOperationException("${from.label} has no offline model")
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(TranslateLanguage.ENGLISH)
                    .build(),
            )
        }
    }

    override fun translate(text: String, from: Lang): String {
        val c = client(from)
        // No-op once downloaded; fails fast when offline and the model is missing.
        Tasks.await(c.downloadModelIfNeeded(), 60, TimeUnit.SECONDS)
        return Tasks.await(c.translate(text), 15, TimeUnit.SECONDS)
    }

    /** Downloads every supported model. Blocking; returns a human-readable summary. */
    fun downloadAll(): String {
        val lines = Lang.SOURCES.map { lang ->
            if (!supports(lang)) {
                "${lang.label}: no offline model (uses online translation)"
            } else {
                try {
                    Tasks.await(
                        client(lang).downloadModelIfNeeded(DownloadConditions.Builder().build()),
                        5, TimeUnit.MINUTES,
                    )
                    "${lang.label}: ready offline"
                } catch (e: Exception) {
                    "${lang.label}: download failed (${e.message})"
                }
            }
        }
        return lines.joinToString("\n")
    }
}
