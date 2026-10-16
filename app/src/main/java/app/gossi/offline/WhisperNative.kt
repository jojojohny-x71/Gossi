package app.gossi.offline

import java.io.Closeable

/** Thin wrapper over the whisper.cpp JNI bridge (app/src/main/cpp/whisper_jni.cpp). Not thread-safe. */
class WhisperNative private constructor(private var ptr: Long) : Closeable {

    class Result(val langCode: String, val english: String, val original: String, val noSpeechProb: Float)

    companion object {
        val available: Boolean by lazy {
            try {
                System.loadLibrary("gossi_whisper")
                true
            } catch (_: Throwable) {
                false
            }
        }

        /** Loads a ggml model file; takes a few seconds. Returns null if the file is invalid. */
        fun load(path: String): WhisperNative? {
            if (!available) return null
            val p = nativeInit(path)
            return if (p == 0L) null else WhisperNative(p)
        }

        @JvmStatic private external fun nativeInit(path: String): Long
        @JvmStatic private external fun nativeFree(ptr: Long)
        @JvmStatic private external fun nativeTranslate(
            ptr: Long, pcm: FloatArray, forcedLang: String?, allowed: Array<String>, threads: Int, alsoTranscribe: Boolean,
        ): Array<ByteArray>?
    }

    /**
     * @param pcm 16 kHz mono samples in -1..1
     * @param forcedLang whisper language code, or null to detect among [allowed]
     */
    fun translate(pcm: FloatArray, forcedLang: String?, allowed: Array<String>, threads: Int, alsoTranscribe: Boolean): Result? {
        check(ptr != 0L) { "closed" }
        val r = nativeTranslate(ptr, pcm, forcedLang, allowed, threads, alsoTranscribe) ?: return null
        fun s(i: Int) = String(r[i], Charsets.UTF_8).trim()
        return Result(s(0), s(1), s(2), s(3).toFloatOrNull() ?: 0f)
    }

    override fun close() {
        if (ptr != 0L) {
            nativeFree(ptr)
            ptr = 0L
        }
    }
}
