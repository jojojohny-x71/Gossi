package app.gossi.offline

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Offline speech → English models (OpenAI Whisper, quantised for phones; MIT licence). */
enum class WhisperModel(val fileName: String, val sizeMb: Int, val label: String) {
    BASE("ggml-base-q5_1.bin", 60, "Fast (60 MB) — quickest, lower accuracy"),
    SMALL("ggml-small-q5_1.bin", 190, "Balanced (190 MB) — recommended"),
    MEDIUM("ggml-medium-q5_0.bin", 540, "Best (540 MB) — most accurate, slow (~2× slower)");

    val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
}

object ModelStore {

    fun dir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }

    fun file(ctx: Context, m: WhisperModel) = File(dir(ctx), m.fileName)

    fun isDownloaded(ctx: Context, m: WhisperModel) = file(ctx, m).let { it.exists() && it.length() > 1_000_000 }

    fun delete(ctx: Context, m: WhisperModel) {
        file(ctx, m).delete()
    }

    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    /** Blocking download with progress (0..100). Resumes a partial download if one exists. */
    fun download(ctx: Context, m: WhisperModel, onProgress: (Int) -> Unit) {
        cancelled = false
        val target = file(ctx, m)
        val part = File(target.path + ".part")
        var url = URL(m.url)
        var redirects = 0
        while (true) {
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            val already = part.length()
            if (already > 0) conn.setRequestProperty("Range", "bytes=$already-")
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw IOException("Redirect without location")
                conn.disconnect()
                url = URL(url, loc)
                if (++redirects > 8) throw IOException("Too many redirects")
                continue
            }
            if (code != 200 && code != 206) {
                conn.disconnect()
                throw IOException("Download failed (HTTP $code)")
            }
            val append = code == 206
            val total = if (append) already + conn.contentLengthLong else conn.contentLengthLong
            var done = if (append) already else 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (cancelled) throw IOException("Cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
            conn.disconnect()
            if (total > 0 && done < total) throw IOException("Download interrupted — tap again to resume")
            if (!part.renameTo(target)) throw IOException("Could not save model")
            return
        }
    }
}
