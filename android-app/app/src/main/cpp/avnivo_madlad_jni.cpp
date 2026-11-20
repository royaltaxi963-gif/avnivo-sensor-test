// Greedy MADLAD-400 3B translation on the ONNX Runtime already packaged by the sherpa-onnx AAR.
// ONNX Runtime is resolved with dlopen, exactly as the NLLB bridge does, so this library neither
// duplicates nor version-conflicts with libonnxruntime.so.
//
// The decoder takes only three inputs (input_ids, encoder_hidden_states, encoder_attention_mask):
// the exported graph has no KV-cache branch, so each step re-runs the decoder over the whole
// prefix. That is quadratic in output length, which is why the output is capped, but it keeps the
// graph simple and was measured at ~0.3 s/token on 4 threads with a 2.21 GB peak RSS.
//
// A no-repeat 3-gram guard is applied in the token loop. Without it the INT4 model can fall into a
// short repeat cycle and never emit EOS; with it, decoding matches the fp32 reference output.
#include <dlfcn.h>
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>
#include <string>
#include <thread>
#include <vector>

#include "onnxruntime/onnxruntime_c_api.h"

namespace {

constexpr int64_t kEosId = 2;
constexpr int64_t kDecoderStartId = 0;
constexpr int kNoRepeatNgram = 3;

/**
 * The decoder is memory-bound, so it benefits from every core available. The floor is 2 because a
 * single thread cannot overlap the quantized weight loads with the matmuls.
 */
int inferenceThreads() {
    const unsigned hw = std::thread::hardware_concurrency();
    if (hw == 0) return 2;
    return static_cast<int>(std::min(8u, std::max(2u, hw - 1)));
}

const OrtApi* ortApi() {
    static const OrtApi* api = [] {
        void* handle = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_LOCAL);
        if (!handle) return static_cast<const OrtApi*>(nullptr);
        auto getBase = reinterpret_cast<const OrtApiBase* (*)()>(dlsym(handle, "OrtGetApiBase"));
        if (!getBase) return static_cast<const OrtApi*>(nullptr);
        return getBase()->GetApi(ORT_API_VERSION);
    }();
    return api;
}

void throwJava(JNIEnv* env, const std::string& message) {
    if (jclass cls = env->FindClass("java/lang/IllegalStateException")) {
        env->ThrowNew(cls, message.c_str());
    }
}

std::string takeError(const OrtApi* api, OrtStatus* status) {
    std::string message = status ? api->GetErrorMessage(status) : "unknown ONNX Runtime error";
    if (status) api->ReleaseStatus(status);
    return message;
}

/** Releases every OrtValue handed to it, so no early return can leak. */
struct Values {
    const OrtApi* api;
    std::vector<OrtValue*> items;
    ~Values() {
        for (OrtValue* value : items) {
            if (value) api->ReleaseValue(value);
        }
    }
    void add(OrtValue* value) { items.push_back(value); }
};

struct Session {
    OrtSession* session = nullptr;
    OrtMemoryInfo* memory = nullptr;

    ~Session() {
        const OrtApi* api = ortApi();
        if (session) api->ReleaseSession(session);
        if (memory) api->ReleaseMemoryInfo(memory);
    }

    bool open(const OrtApi* api, OrtEnv* env, OrtSessionOptions* options, const std::string& path,
              std::string& error) {
        OrtStatus* status = api->CreateSession(env, path.c_str(), options, &session);
        if (status) {
            error = takeError(api, status);
            return false;
        }
        if ((status = api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &memory))) {
            error = takeError(api, status);
            return false;
        }
        return true;
    }
};

/** Everything a translation needs, created once and reused across calls. */
struct Runtime {
    OrtEnv* env = nullptr;
    OrtSessionOptions* options = nullptr;
    Session encoder;
    Session decoder;

    ~Runtime() {
        const OrtApi* api = ortApi();
        if (options) api->ReleaseSessionOptions(options);
        if (env) api->ReleaseEnv(env);
    }

    bool open(const OrtApi* api, const std::string& encoderPath, const std::string& decoderPath,
              std::string& error) {
        OrtStatus* status = api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "avnivo-madlad", &env);
        if (status) {
            error = takeError(api, status);
            return false;
        }
        if ((status = api->CreateSessionOptions(&options)) ||
            (status = api->SetIntraOpNumThreads(options, inferenceThreads())) ||
            (status = api->SetSessionGraphOptimizationLevel(options, ORT_ENABLE_ALL)) ||
            // Both the arena and the memory-pattern planner pre-allocate for the worst case, which
            // on a 3B model is the difference between fitting and being killed by the low-memory
            // killer. Disabling them costs a little speed and was measured to keep peak RSS at
            // 2.21 GB.
            (status = api->DisableCpuMemArena(options)) ||
            (status = api->DisableMemPattern(options))) {
            error = takeError(api, status);
            return false;
        }
        return encoder.open(api, env, options, encoderPath, error) &&
               decoder.open(api, env, options, decoderPath, error);
    }
};

std::string toUtf8(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string copy(chars);
    env->ReleaseStringUTFChars(value, chars);
    return copy;
}

/** Marks every token that would complete a banned n-gram as -infinity. */
void applyNoRepeatNgram(const std::vector<int32_t>& sequence, float* logits, int64_t vocab) {
    const size_t n = static_cast<size_t>(kNoRepeatNgram);
    if (sequence.size() + 1 < n) return;
    const size_t tailStart = sequence.size() - (n - 1);
    for (size_t i = 0; i + n <= sequence.size(); ++i) {
        bool samePrefix = true;
        for (size_t k = 0; k < n - 1; ++k) {
            if (sequence[i + k] != sequence[tailStart + k]) {
                samePrefix = false;
                break;
            }
        }
        if (!samePrefix) continue;
        const int32_t banned = sequence[i + n - 1];
        if (banned >= 0 && static_cast<int64_t>(banned) < vocab) {
            logits[banned] = -INFINITY;
        }
    }
}

}  // namespace

extern "C" {

/** Opens both ONNX sessions. Returns a handle, or 0 with a pending Java exception on failure. */
JNIEXPORT jlong JNICALL Java_com_avnivo_translator_MadladEngine_nativeCreate(JNIEnv* env,
                                                                            jobject /*thiz*/,
                                                                            jstring encoderPath,
                                                                            jstring decoderPath) {
    const OrtApi* api = ortApi();
    if (!api) {
        throwJava(env, "ONNX Runtime is not available on this device");
        return 0;
    }
    auto runtime = std::make_unique<Runtime>();
    std::string error;
    if (!runtime->open(api, toUtf8(env, encoderPath), toUtf8(env, decoderPath), error)) {
        throwJava(env, error);
        return 0;
    }
    return reinterpret_cast<jlong>(runtime.release());
}

JNIEXPORT void JNICALL Java_com_avnivo_translator_MadladEngine_nativeFree(JNIEnv* /*env*/,
                                                                         jobject /*thiz*/,
                                                                         jlong handle) {
    delete reinterpret_cast<Runtime*>(handle);
}

/**
 * Encodes `sourceIds` and greedily decodes from decoder_start_token_id, returning the generated
 * token ids with EOS excluded and the start token omitted. An empty result means the model
 * produced nothing, which callers treat as failure.
 */
JNIEXPORT jintArray JNICALL
Java_com_avnivo_translator_MadladEngine_nativeTranslate(JNIEnv* env, jobject /*thiz*/, jlong handle,
                                                        jintArray sourceIds, jint maxNewTokens) {
    const OrtApi* api = ortApi();
    auto* runtime = reinterpret_cast<Runtime*>(handle);
    if (!api || !runtime || !runtime->encoder.session || !runtime->decoder.session) {
        throwJava(env, "MADLAD engine is not initialized");
        return nullptr;
    }

    const jsize sourceLength = env->GetArrayLength(sourceIds);
    if (sourceLength <= 0) {
        throwJava(env, "empty source sequence");
        return nullptr;
    }
    std::vector<int32_t> source(static_cast<size_t>(sourceLength));
    env->GetIntArrayRegion(sourceIds, 0, sourceLength, source.data());

    Values values{api, {}};
    std::vector<int64_t> sourceShape{1, sourceLength};
    std::vector<int64_t> sourceInt64(source.begin(), source.end());
    std::vector<int64_t> mask(static_cast<size_t>(sourceLength), 1);

    auto int64Tensor = [&](OrtMemoryInfo* memory, std::vector<int64_t>& data,
                           std::vector<int64_t>& shape) -> OrtValue* {
        OrtValue* value = nullptr;
        if (api->CreateTensorWithDataAsOrtValue(memory, data.data(), data.size() * sizeof(int64_t),
                                                shape.data(), shape.size(),
                                                ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &value)) {
            return nullptr;
        }
        return value;
    };

    OrtValue* encoderIds = int64Tensor(runtime->encoder.memory, sourceInt64, sourceShape);
    OrtValue* encoderMask = int64Tensor(runtime->encoder.memory, mask, sourceShape);
    values.add(encoderIds);
    values.add(encoderMask);
    if (!encoderIds || !encoderMask) {
        throwJava(env, "could not allocate encoder inputs");
        return nullptr;
    }

    const char* encoderInputs[] = {"input_ids", "attention_mask"};
    const char* encoderOutputs[] = {"last_hidden_state"};
    OrtValue* encoderInputValues[] = {encoderIds, encoderMask};
    OrtValue* hidden = nullptr;
    OrtStatus* status = api->Run(runtime->encoder.session, nullptr, encoderInputs, encoderInputValues,
                                 2, encoderOutputs, 1, &hidden);
    if (status) {
        throwJava(env, "encoder run: " + takeError(api, status));
        return nullptr;
    }
    values.add(hidden);

    std::vector<int32_t> generated;
    std::vector<int32_t> sequence{kDecoderStartId};
    const int cap = maxNewTokens > 0 ? maxNewTokens : 64;

    for (int step = 0; step < cap; ++step) {
        std::vector<int64_t> ids(sequence.begin(), sequence.end());
        std::vector<int64_t> seqShape{1, static_cast<int64_t>(ids.size())};

        OrtValue* idsValue = int64Tensor(runtime->decoder.memory, ids, seqShape);
        OrtValue* maskValue = int64Tensor(runtime->decoder.memory, mask, sourceShape);
        if (!idsValue || !maskValue) {
            if (idsValue) api->ReleaseValue(idsValue);
            if (maskValue) api->ReleaseValue(maskValue);
            throwJava(env, "could not allocate decoder inputs");
            return nullptr;
        }

        const char* decoderInputNames[] = {"input_ids", "encoder_hidden_states",
                                           "encoder_attention_mask"};
        OrtValue* decoderInputValues[] = {idsValue, hidden, maskValue};
        OrtValue* logits = nullptr;
        const char* outputNames[] = {"logits"};
        status = api->Run(runtime->decoder.session, nullptr, decoderInputNames, decoderInputValues, 3,
                          outputNames, 1, &logits);
        if (status) {
            api->ReleaseValue(idsValue);
            api->ReleaseValue(maskValue);
            throwJava(env, "decoder run: " + takeError(api, status));
            return nullptr;
        }

        // Every one of these can fail; a null logitsInfo would be dereferenced immediately below,
        // so the failure is checked rather than assumed away.
        OrtTensorTypeAndShapeInfo* logitsInfo = nullptr;
        size_t logitsRank = 0;
        size_t elementCount = 0;
        std::vector<int64_t> logitsShape;
        int64_t vocab = 0;
        bool shapeOk = api->GetTensorTypeAndShape(logits, &logitsInfo) == nullptr;
        if (shapeOk) shapeOk = api->GetDimensionsCount(logitsInfo, &logitsRank) == nullptr;
        if (shapeOk) {
            logitsShape.resize(logitsRank);
            shapeOk = api->GetDimensions(logitsInfo, logitsShape.data(), logitsRank) == nullptr;
        }
        if (shapeOk) shapeOk = api->GetTensorShapeElementCount(logitsInfo, &elementCount) == nullptr;
        if (logitsInfo) api->ReleaseTensorTypeAndShapeInfo(logitsInfo);
        if (shapeOk && logitsRank == 3) vocab = logitsShape[2];

        float* raw = nullptr;
        if (shapeOk) shapeOk = api->GetTensorMutableData(logits, reinterpret_cast<void**>(&raw)) == nullptr;
        if (!shapeOk || !raw || vocab <= 0) {
            api->ReleaseValue(logits);
            api->ReleaseValue(idsValue);
            api->ReleaseValue(maskValue);
            throwJava(env, "decoder returned an unexpected logits shape");
            return nullptr;
        }
        float* last = raw + elementCount - static_cast<size_t>(vocab);
        applyNoRepeatNgram(sequence, last, vocab);

        int32_t next = 0;
        float best = last[0];
        for (int64_t token = 1; token < vocab; ++token) {
            if (last[token] > best) {
                best = last[token];
                next = static_cast<int32_t>(token);
            }
        }

        api->ReleaseValue(logits);
        api->ReleaseValue(idsValue);
        api->ReleaseValue(maskValue);

        if (next == kEosId) break;
        generated.push_back(next);
        sequence.push_back(next);
    }

    jintArray result = env->NewIntArray(static_cast<jsize>(generated.size()));
    if (result) {
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(generated.size()), generated.data());
    }
    return result;
}

}  // extern "C"
