// JNI bridge for offline Whisper (ggml/whisper.cpp) speech recognition.
// The model is a single GGML file; inference is fully offline on-device.
#include <jni.h>
#include <string>
#include <vector>
#include "whisper.h"

namespace {

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

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_avnivo_translator_WhisperEngine_nativeInit(JNIEnv *env, jclass, jstring modelPath) {
    const std::string path = jstringToStd(env, modelPath);
    if (path.empty()) return 0;
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), params);
    if (ctx == nullptr) return 0;
    auto *session = new Session();
    session->ctx = ctx;
    session->ready = true;
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_avnivo_translator_WhisperEngine_nativeTranscribe(
        JNIEnv *env, jclass, jlong handle, jfloatArray samples, jstring languageName) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr || !session->ready || session->ctx == nullptr) return env->NewStringUTF("");
    const jsize count = env->GetArrayLength(samples);
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
    params.n_threads = 4;
    params.no_context = true;
    const std::string language = requested.empty() ? "auto" : requested;
    params.language = language.c_str();

    if (whisper_full(session->ctx, params, pcm.data(), count) != 0) return env->NewStringUTF("");

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
