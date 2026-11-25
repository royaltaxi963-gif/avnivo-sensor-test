// JNI bridge for offline Whisper (ggml/whisper.cpp) speech recognition.
// The model is a single GGML file; inference is fully offline on-device.
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <chrono>
#include <string>
#include <thread>
#include <vector>
#include "whisper.h"

namespace {

constexpr const char *kLogTag = "AvnivoWhisper";

struct Session {
    whisper_context *ctx = nullptr;
    bool ready = false;
};

std::string jstringToStd(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}

/**
 * ggml synchronises its threads with a spin barrier, so a thread that lands on a little core makes
 * every other thread wait for it. On an 8-core big.LITTLE phone the old `hw - 1` (7) oversubscribed
 * the performance cluster and turned a short decode into a multi-second stall; four is the ceiling
 * whisper.cpp recommends for mobile and still leaves cores for audio and the UI.
 */
int inferenceThreads() {
    const unsigned hw = std::thread::hardware_concurrency();
    if (hw == 0) return 2;
    return static_cast<int>(std::min(4u, std::max(1u, hw - 1)));
}

/** whisper_full progress, so a stalled decode shows the exact percentage it stopped at. */
void onProgress(struct whisper_context *, struct whisper_state *, int progress, void *) {
    if (progress % 10 != 0) return;
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "WHISPER_DECODE_PROGRESS pct=%d", progress);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_avnivo_translator_WhisperEngine_nativeInit(JNIEnv *env, jclass, jstring modelPath) {
    const std::string path = jstringToStd(env, modelPath);
    if (path.empty()) return 0;
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    const auto started = std::chrono::steady_clock::now();
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                        "WHISPER_NATIVE_INIT_START path=%s threads=%d", path.c_str(), inferenceThreads());
    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), params);
    const auto init_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                             std::chrono::steady_clock::now() - started).count();
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                        "WHISPER_NATIVE_INIT_END elapsed_ms=%lld ok=%d",
                        static_cast<long long>(init_ms), ctx != nullptr);
    if (ctx == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "WHISPER_INIT_FAILED path=%s", path.c_str());
        return 0;
    }
    auto *session = new Session();
    session->ctx = ctx;
    session->ready = true;
    const char *type = whisper_model_type_readable(ctx);
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                        "WHISPER_INIT_OK path=%s vocab=%d audio_ctx=%d text_ctx=%d multilingual=%d type=%s",
                        path.c_str(), whisper_model_n_vocab(ctx), whisper_model_n_audio_ctx(ctx),
                        whisper_model_n_text_ctx(ctx), whisper_is_multilingual(ctx),
                        type != nullptr ? type : "unknown");
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_avnivo_translator_WhisperEngine_nativeModelInfo(JNIEnv *env, jclass, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr || !session->ready || session->ctx == nullptr) {
        return env->NewStringUTF("");
    }
    const std::string info =
        "vocab=" + std::to_string(whisper_model_n_vocab(session->ctx)) +
        " audio_ctx=" + std::to_string(whisper_model_n_audio_ctx(session->ctx)) +
        " text_ctx=" + std::to_string(whisper_model_n_text_ctx(session->ctx)) +
        " multilingual=" + std::to_string(whisper_is_multilingual(session->ctx)) +
        " type=" + (whisper_model_type_readable(session->ctx) ? whisper_model_type_readable(session->ctx) : "unknown");
    return env->NewStringUTF(info.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_avnivo_translator_WhisperEngine_nativeTranscribe(
        JNIEnv *env, jclass, jlong handle, jfloatArray samples, jstring languageName) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr || !session->ready || session->ctx == nullptr) return env->NewStringUTF("");
    const jsize count = env->GetArrayLength(samples);
    if (count <= 0) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Empty recording");
        return nullptr;
    }
    std::vector<float> pcm(static_cast<size_t>(count));
    env->GetFloatArrayRegion(samples, 0, count, pcm.data());
    const std::string requested = jstringToStd(env, languageName);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.single_segment = false;
    params.n_threads = inferenceThreads();
    params.no_context = true;
    params.progress_callback = onProgress;
    params.progress_callback_user_data = nullptr;
    const std::string language = requested.empty() ? "auto" : requested;
    params.language = language.c_str();

    const auto started = std::chrono::steady_clock::now();
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                        "WHISPER_NATIVE_DECODE_START samples=%d seconds=%.2f threads=%d language=%s",
                        count, static_cast<double>(count) / 16000.0, params.n_threads, language.c_str());
    const int rc = whisper_full(session->ctx, params, pcm.data(), count);
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                             std::chrono::steady_clock::now() - started)
                             .count();
    const double audioSeconds = static_cast<double>(count) / 16000.0;
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                        "WHISPER_NATIVE_DECODE_END latency=%lldms audio=%.2fs threads=%d rt_ratio=%.2fx rc=%d",
                        static_cast<long long>(elapsed), audioSeconds, params.n_threads,
                        audioSeconds > 0 ? (elapsed / 1000.0) / audioSeconds : 0.0, rc);
    if (rc != 0) {
        // Do not return an empty string: that is indistinguishable from valid silence and used to
        // let a failed decoder report READY. Surface the native status to the Kotlin lifecycle.
        const std::string message = "whisper_full failed rc=" + std::to_string(rc);
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message.c_str());
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message.c_str());
        return nullptr;
    }

    std::string text;
    const int segments = whisper_full_n_segments(session->ctx);
    for (int i = 0; i < segments; ++i) {
        const char *segment = whisper_full_get_segment_text(session->ctx, i);
        if (segment != nullptr) text += segment;
    }
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_avnivo_translator_WhisperEngine_nativeRelease(JNIEnv *, jclass, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return;
    if (session->ctx != nullptr) whisper_free(session->ctx);
    delete session;
}
