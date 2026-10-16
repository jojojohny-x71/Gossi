// JNI bridge: Gossi's offline speech → English translator (whisper.cpp).
#include <jni.h>

#include <algorithm>
#include <cstring>
#include <string>
#include <vector>

#include "whisper.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "GossiWhisper", __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) (fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#endif

namespace {

std::string jstr(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

jbyteArray bytes(JNIEnv *env, const std::string &s) {
    jbyteArray a = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(a, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return a;
}

struct Pass {
    std::string text;
    float no_speech = 0.f;
    bool ok = false;
};

Pass run(whisper_context *ctx, const std::vector<float> &pcm, const std::string &lang, bool translate, int threads) {
    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.translate = translate;
    p.language = lang.c_str();
    p.detect_language = false;
    p.no_context = true;
    p.no_timestamps = true;
    p.single_segment = false;
    p.print_special = false;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.suppress_blank = true;
    p.suppress_nst = true;

    Pass out;
    if (whisper_full(ctx, p, pcm.data(), (int) pcm.size()) != 0) return out;
    const int n = whisper_full_n_segments(ctx);
    float nsp = 0.f;
    for (int i = 0; i < n; ++i) {
        out.text += whisper_full_get_segment_text(ctx, i);
        nsp += whisper_full_get_segment_no_speech_prob(ctx, i);
    }
    out.no_speech = n > 0 ? nsp / n : 1.f;
    out.ok = true;
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_gossi_offline_WhisperNative_nativeInit(JNIEnv *env, jclass, jstring jpath) {
    const std::string path = jstr(env, jpath);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), cp);
    LOGI("init %s -> %p", path.c_str(), (void *) ctx);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_app_gossi_offline_WhisperNative_nativeFree(JNIEnv *, jclass, jlong ptr) {
    if (ptr != 0) whisper_free(reinterpret_cast<whisper_context *>(ptr));
}

/**
 * Detects the language (limited to `allowed`, unless `forced` is set), then translates to English.
 * Returns UTF-8 byte arrays {languageCode, english, original (or ""), noSpeechProbability}, or null on
 * failure. Bytes, not jstrings: whisper can emit malformed UTF-8, which crashes NewStringUTF on Android.
 */
JNIEXPORT jobjectArray JNICALL
Java_app_gossi_offline_WhisperNative_nativeTranslate(JNIEnv *env, jclass, jlong ptr, jfloatArray jpcm,
                                                    jstring jforced, jobjectArray jallowed, jint threads,
                                                    jboolean alsoTranscribe) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (ctx == nullptr || jpcm == nullptr) return nullptr;

    const jsize n = env->GetArrayLength(jpcm);
    // Whisper needs at least ~1 s of audio; pad short utterances with silence.
    std::vector<float> pcm(std::max<jsize>(n, WHISPER_SAMPLE_RATE + WHISPER_SAMPLE_RATE / 10), 0.f);
    env->GetFloatArrayRegion(jpcm, 0, n, pcm.data());

    std::string lang = jstr(env, jforced);
    if (lang.empty()) {
        if (whisper_pcm_to_mel(ctx, pcm.data(), (int) pcm.size(), threads) != 0) return nullptr;
        std::vector<float> probs(whisper_lang_max_id() + 1, 0.f);
        if (whisper_lang_auto_detect(ctx, 0, threads, probs.data()) < 0) return nullptr;
        float best = -1.f;
        const jsize na = jallowed ? env->GetArrayLength(jallowed) : 0;
        for (jsize i = 0; i < na; ++i) {
            const std::string code = jstr(env, (jstring) env->GetObjectArrayElement(jallowed, i));
            const int id = whisper_lang_id(code.c_str());
            if (id >= 0 && probs[id] > best) {
                best = probs[id];
                lang = code;
            }
        }
        if (lang.empty()) lang = "hi";
    }

    const bool english = lang == "en";
    Pass tr = run(ctx, pcm, lang, !english, threads);
    if (!tr.ok) return nullptr;
    Pass orig;
    if (alsoTranscribe && !english) orig = run(ctx, pcm, lang, false, threads);

    jobjectArray out = env->NewObjectArray(4, env->FindClass("[B"), nullptr);
    env->SetObjectArrayElement(out, 0, bytes(env, lang));
    env->SetObjectArrayElement(out, 1, bytes(env, tr.text));
    env->SetObjectArrayElement(out, 2, bytes(env, orig.ok ? orig.text : std::string()));
    env->SetObjectArrayElement(out, 3, bytes(env, std::to_string(tr.no_speech)));
    return out;
}

}  // extern "C"
