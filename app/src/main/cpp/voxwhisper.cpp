// JNI bridge: offline speech recognition with automatic language identification
// restricted to the conversation languages (pt / en / zh), using whisper.cpp (MIT).
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "whisper.h"

#define LOG_TAG "VoxWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_com_voxlink_ai_speech_WhisperLib_initContext(JNIEnv *env, jclass, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    LOGI("model loaded: %s", ctx ? "ok" : "FAILED");
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_voxlink_ai_speech_WhisperLib_freeContext(JNIEnv *, jclass, jlong ptr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (ctx) whisper_free(ctx);
}

// Returns UTF-8 bytes "lang\ntext" (bytes, not jstring, so invalid UTF-8 can never crash JNI).
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_voxlink_ai_speech_WhisperLib_transcribe(JNIEnv *env, jclass, jlong ptr, jfloatArray samples,
                                                 jint threads, jobjectArray langs) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    std::string out;
    if (ctx) {
        const jsize n = env->GetArrayLength(samples);
        jfloat *data = env->GetFloatArrayElements(samples, nullptr);

        std::vector<std::string> allowed;
        const jsize nl = env->GetArrayLength(langs);
        for (jsize i = 0; i < nl; i++) {
            auto js = (jstring) env->GetObjectArrayElement(langs, i);
            const char *c = env->GetStringUTFChars(js, nullptr);
            allowed.emplace_back(c);
            env->ReleaseStringUTFChars(js, c);
            env->DeleteLocalRef(js);
        }

        // 1) language identification, restricted to the allowed languages
        std::string lang = allowed.empty() ? "en" : allowed[0];
        if (allowed.size() > 1 && whisper_pcm_to_mel(ctx, data, n, threads) == 0) {
            std::vector<float> probs(whisper_lang_max_id() + 1, 0.0f);
            if (whisper_lang_auto_detect(ctx, 0, threads, probs.data()) >= 0) {
                float best = -1.0f;
                for (const auto &a: allowed) {
                    const int id = whisper_lang_id(a.c_str());
                    if (id >= 0 && probs[id] > best) {
                        best = probs[id];
                        lang = a;
                    }
                }
                LOGI("language: %s (p=%.2f)", lang.c_str(), best);
            }
        }

        // 2) transcription in that language
        whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        wp.n_threads = threads;
        wp.language = lang.c_str();
        wp.detect_language = false;
        wp.translate = false;
        wp.no_context = true;
        wp.single_segment = false;
        wp.print_special = false;
        wp.print_progress = false;
        wp.print_realtime = false;
        wp.print_timestamps = false;
        wp.suppress_blank = true;
        if (lang == "zh") wp.initial_prompt = "以下是普通话的句子，使用简体中文。";
        if (lang == "pt") wp.initial_prompt = "Conversa técnica em português do Brasil, com pontuação.";

        if (whisper_full(ctx, wp, data, n) == 0) {
            std::string text;
            const int segs = whisper_full_n_segments(ctx);
            for (int i = 0; i < segs; i++) text += whisper_full_get_segment_text(ctx, i);
            out = lang + "\n" + text;
        }
        env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    }
    jbyteArray arr = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(arr, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return arr;
}
