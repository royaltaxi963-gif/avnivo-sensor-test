// JNI bridge for offline Whisper (ggml/whisper.cpp) speech recognition.
// The model is a single GGML file; inference is fully offline on-device.
#include <android/log.h>
#include <jni.h>
#include <sys/auxv.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <cstdint>
#include <thread>
#include <vector>
#include "whisper.h"

namespace {

constexpr const char *kLogTag = "AvnivoWhisper";

struct Session {
    whisper_context *ctx = nullptr;
    bool ready = false;
    std::string lastTimings;
};

/**
 * Decodes UTF-8 into a jstring.
 *
 * NewStringUTF takes *Modified* UTF-8, where anything outside the BMP is a surrogate pair; it
 * aborts the process when handed the four-byte form, and every debuggable build runs with CheckJNI
 * on. whisper.cpp hands back plain UTF-8, and a reduced encoder window can produce a transcript
 * that contains an emoji or another supplementary character, so the conversion has to be explicit.
 */
jstring utf8ToJString(JNIEnv *env, const std::string &text) {
    std::u16string utf16;
    utf16.reserve(text.size());
    const auto *bytes = reinterpret_cast<const unsigned char *>(text.data());
    const size_t size = text.size();
    for (size_t i = 0; i < size;) {
        const unsigned char lead = bytes[i];
        uint32_t code = 0xFFFD;
        size_t width = 1;
        if (lead < 0x80) {
            code = lead;
        } else if ((lead & 0xE0) == 0xC0 && i + 1 < size) {
            code = ((lead & 0x1Fu) << 6) | (bytes[i + 1] & 0x3Fu);
            width = 2;
        } else if ((lead & 0xF0) == 0xE0 && i + 2 < size) {
            code = ((lead & 0x0Fu) << 12) | ((bytes[i + 1] & 0x3Fu) << 6) | (bytes[i + 2] & 0x3Fu);
            width = 3;
        } else if ((lead & 0xF8) == 0xF0 && i + 3 < size) {
            code = ((lead & 0x07u) << 18) | ((bytes[i + 1] & 0x3Fu) << 12) |
                   ((bytes[i + 2] & 0x3Fu) << 6) | (bytes[i + 3] & 0x3Fu);
            width = 4;
        }
        i += width;
        if (code < 0x10000) {
            utf16.push_back(static_cast<char16_t>(code));
        } else {
            code -= 0x10000;
            utf16.push_back(static_cast<char16_t>(0xD800 + (code >> 10)));
            utf16.push_back(static_cast<char16_t>(0xDC00 + (code & 0x3FF)));
        }
    }
    return env->NewString(reinterpret_cast<const jchar *>(utf16.data()), static_cast<jsize>(utf16.size()));
}

std::string jstringToStd(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}

/**
 * ggml synchronises its threads with a spin barrier, so a thread that lands on a little core makes
 * every other thread wait for it. On an 8-core big.LITTLE phone `hw - 1` (7) oversubscribed the
 * performance cluster and turned a short decode into a multi-second stall; four is the ceiling
 * whisper.cpp recommends for mobile and still leaves cores for audio and the UI. A requested value
 * wins, so a device benchmark can try 2, 4, 6 and 8 without a rebuild.
 */
int inferenceThreads(int requested) {
    if (requested > 0) return requested;
    const unsigned hw = std::thread::hardware_concurrency();
    if (hw == 0) return 2;
    return static_cast<int>(std::min(4u, std::max(1u, hw - 1)));
}

/** whisper_full progress, so a stalled decode shows the exact percentage it stopped at. */
void onProgress(struct whisper_context *, struct whisper_state *, int progress, void *) {
    if (progress % 10 != 0) return;
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "WHISPER_DECODE_PROGRESS pct=%d", progress);
}

/**
 * whisper_print_timings() writes the per-stage breakdown through the log callback and nowhere
 * else, so the callback is the only way to attribute latency to mel, encoder and decoder.
 */
struct TimingCapture {
    std::mutex mutex;
    std::atomic<bool> active{false};
    double mel_ms = 0;
    double sample_ms = 0;
    double encode_ms = 0;
    double decode_ms = 0;
    double batchd_ms = 0;
    double total_ms = 0;
    int encode_runs = 0;
    int decode_runs = 0;
    int fallbacks_p = 0;
    int fallbacks_h = 0;
};

TimingCapture g_timings;

bool readMilliseconds(const char *text, const char *name, double *out) {
    const char *found = std::strstr(text, name);
    if (found == nullptr) return false;
    const char *equals = std::strchr(found, '=');
    if (equals == nullptr) return false;
    return std::sscanf(equals + 1, "%lf", out) == 1;
}

bool readRuns(const char *text, const char *name, double *ms, int *runs) {
    const char *found = std::strstr(text, name);
    if (found == nullptr) return false;
    const char *equals = std::strchr(found, '=');
    if (equals == nullptr) return false;
    int parsed = 0;
    if (std::sscanf(equals + 1, "%lf ms / %d runs", ms, &parsed) == 2) {
        *runs = parsed;
        return true;
    }
    return std::sscanf(equals + 1, "%lf", ms) == 1;
}

void whisperLogCallback(enum ggml_log_level level, const char *text, void *) {
    if (text == nullptr) return;
    if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN) {
        size_t length = std::strlen(text);
        while (length > 0 && (text[length - 1] == '\n' || text[length - 1] == ' ')) --length;
        __android_log_print(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN,
                            kLogTag, "WHISPER_LOG %.*s", static_cast<int>(length), text);
        return;
    }
    if (!g_timings.active.load(std::memory_order_relaxed)) return;

    std::lock_guard<std::mutex> guard(g_timings.mutex);
    readMilliseconds(text, "mel time", &g_timings.mel_ms);
    readRuns(text, "sample time", &g_timings.sample_ms, &g_timings.encode_runs);
    readRuns(text, "encode time", &g_timings.encode_ms, &g_timings.encode_runs);
    readRuns(text, "decode time", &g_timings.decode_ms, &g_timings.decode_runs);
    readMilliseconds(text, "batchd time", &g_timings.batchd_ms);
    readMilliseconds(text, "total time", &g_timings.total_ms);
    const char *fallbacks = std::strstr(text, "fallbacks");
    if (fallbacks != nullptr) {
        const char *equals = std::strchr(fallbacks, '=');
        int fails_p = 0;
        int fails_h = 0;
        if (equals != nullptr && std::sscanf(equals + 1, "%d p / %d h", &fails_p, &fails_h) == 2) {
            g_timings.fallbacks_p = fails_p;
            g_timings.fallbacks_h = fails_h;
        }
    }
}

std::string formatTimings() {
    std::lock_guard<std::mutex> guard(g_timings.mutex);
    char buffer[512];
    std::snprintf(buffer, sizeof(buffer),
                  "mel_ms=%.1f sample_ms=%.1f encode_ms=%.1f encode_runs=%d decode_ms=%.1f "
                  "decode_runs=%d batchd_ms=%.1f total_ms=%.1f fallbacks_p=%d fallbacks_h=%d",
                  g_timings.mel_ms, g_timings.sample_ms, g_timings.encode_ms, g_timings.encode_runs,
                  g_timings.decode_ms, g_timings.decode_runs, g_timings.batchd_ms, g_timings.total_ms,
                  g_timings.fallbacks_p, g_timings.fallbacks_h);
    return std::string(buffer);
}

void resetTimings() {
    std::lock_guard<std::mutex> guard(g_timings.mutex);
    g_timings.mel_ms = 0;
    g_timings.sample_ms = 0;
    g_timings.encode_ms = 0;
    g_timings.decode_ms = 0;
    g_timings.batchd_ms = 0;
    g_timings.total_ms = 0;
    g_timings.encode_runs = 0;
    g_timings.decode_runs = 0;
    g_timings.fallbacks_p = 0;
    g_timings.fallbacks_h = 0;
}

/**
 * What the binary was compiled for versus what the phone actually has. Without this line a silent
 * fall back to the generic NEON kernels looks exactly like a slow model.
 */
void logCapabilities() {
#ifndef HWCAP_ASIMDDP
#define HWCAP_ASIMDDP (1UL << 20)
#endif
#ifndef HWCAP2_I8MM
#define HWCAP2_I8MM (1UL << 13)
#endif
#if defined(__ARM_NEON)
    const bool cpu_dotprod = (getauxval(AT_HWCAP) & HWCAP_ASIMDDP) != 0;
    const bool cpu_i8mm = (getauxval(AT_HWCAP2) & HWCAP2_I8MM) != 0;
#else
    const bool cpu_dotprod = false;
    const bool cpu_i8mm = false;
#endif
#if defined(__ARM_FEATURE_DOTPROD)
    const bool built_dotprod = true;
#else
    const bool built_dotprod = false;
#endif
#if defined(__ARM_FEATURE_MATMUL_INT8)
    const bool built_i8mm = true;
#else
    const bool built_i8mm = false;
#endif
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                        "WHISPER_CAPABILITY built_dotprod=%d built_i8mm=%d cpu_dotprod=%d cpu_i8mm=%d",
                        built_dotprod, built_i8mm, cpu_dotprod, cpu_i8mm);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_avnivo_translator_WhisperEngine_nativeInit(JNIEnv *env, jclass, jstring modelPath) {
    const std::string path = jstringToStd(env, modelPath);
    if (path.empty()) return 0;
    whisper_log_set(whisperLogCallback, nullptr);
    logCapabilities();
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    const auto started = std::chrono::steady_clock::now();
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                        "WHISPER_NATIVE_INIT_START path=%s threads=%d", path.c_str(),
                        inferenceThreads(0));
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
    return utf8ToJString(env, info);
}

/** Per-stage timings of the last decode, as reported by whisper_print_timings(). */
extern "C" JNIEXPORT jstring JNICALL
Java_com_avnivo_translator_WhisperEngine_nativeLastTimings(JNIEnv *env, jclass, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return env->NewStringUTF("");
    return utf8ToJString(env, session->lastTimings);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_avnivo_translator_WhisperEngine_nativeTranscribe(
        JNIEnv *env, jclass, jlong handle, jfloatArray samples, jstring languageName,
        jint threads, jint audioCtx) {
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

    const int modelAudioCtx = whisper_model_n_audio_ctx(session->ctx);
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.single_segment = false;
    params.debug_mode = false;
    params.n_threads = inferenceThreads(threads);
    params.no_context = true;
    params.progress_callback = onProgress;
    params.progress_callback_user_data = nullptr;
    // Only the encoder positions that carry audio are worth computing; the rest of the 30 s
    // window is padding whisper.cpp otherwise encodes in full. Everything else stays at
    // whisper.cpp defaults, including the temperature ladder that recovers from a repetition
    // loop - it costs nothing when the first pass is clean.
    params.audio_ctx = audioCtx > 0 ? std::min(static_cast<int>(audioCtx), modelAudioCtx) : 0;
    const std::string language = requested.empty() ? "auto" : requested;
    params.language = language.c_str();

    resetTimings();
    whisper_reset_timings(session->ctx);
    const auto started = std::chrono::steady_clock::now();
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                        "WHISPER_NATIVE_DECODE_START samples=%d seconds=%.2f threads=%d language=%s "
                        "audio_ctx=%d/%d",
                        count, static_cast<double>(count) / 16000.0, params.n_threads,
                        language.c_str(), params.audio_ctx, modelAudioCtx);
    g_timings.active.store(true);
    const int rc = whisper_full(session->ctx, params, pcm.data(), count);
    whisper_print_timings(session->ctx);
    g_timings.active.store(false);
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                             std::chrono::steady_clock::now() - started)
                             .count();
    session->lastTimings = formatTimings();
    const double audioSeconds = static_cast<double>(count) / 16000.0;
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                        "WHISPER_STAGE_TIMING %s audio=%.2fs threads=%d audio_ctx=%d",
                        session->lastTimings.c_str(), audioSeconds, params.n_threads,
                        params.audio_ctx);
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
    return utf8ToJString(env, text);
}

extern "C" JNIEXPORT void JNICALL
Java_com_avnivo_translator_WhisperEngine_nativeRelease(JNIEnv *, jclass, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return;
    if (session->ctx != nullptr) whisper_free(session->ctx);
    delete session;
}
