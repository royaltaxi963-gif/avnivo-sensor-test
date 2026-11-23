// Greedy NLLB-200 translation on top of the ONNX Runtime that the sherpa-onnx AAR already ships.
// ONNX Runtime is resolved with dlopen so this library never duplicates (or version-conflicts with)
// the packaged libonnxruntime.so, and needs no extra dependency.
//
// Sessions are created once per engine (nativeCreate) and reused by every translation. Opening them
// per call re-read ONNX weights on each request and briefly held two full copies, which is what
// pushed the process over the device memory limit.
//
// The decoder export in this model has a broken KV-cache branch: the merged `If` graph fails when
// past tensors are empty, so each step re-runs the whole decoder sequence instead of caching. That
// is correct but quadratic in output length, hence the output cap.
#include <dlfcn.h>
#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <memory>
#include <thread>
#include <string>
#include <vector>

#include "onnxruntime/onnxruntime_c_api.h"

namespace {

constexpr int64_t kEosId = 2;
constexpr int kLayers = 12;
constexpr int kHeads = 16;
constexpr int kHeadDim = 64;

/**
 * The decoder is memory-bound, so unlike Whisper it still benefits from every core it can get.
 * The floor is 2 rather than 1: on the 2-core emulator, hardware_concurrency() - 1 = 1 thread
 * measured 1581 ms for a short sentence against 1282 ms at 2 threads, because a single thread
 * cannot overlap the quantized weight loads with the matmuls.
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

/** Everything a translation needs, created once and reused. */
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
        OrtStatus* status = api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "avnivo-nllb", &env);
        if (status) {
            error = takeError(api, status);
            return false;
        }
        if ((status = api->CreateSessionOptions(&options)) ||
            (status = api->SetIntraOpNumThreads(options, inferenceThreads())) ||
            (status = api->SetSessionGraphOptimizationLevel(options, ORT_ENABLE_ALL)) ||
            // The CPU arena and the memory-pattern planner both pre-allocate for the worst case.
            // Measured on this 600M INT8 model they add ~1 GB of resident memory, which is what got
            // the process killed by the low-memory killer; disabling them costs a little speed.
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

}  // namespace

extern "C" {

/** Opens both ONNX sessions. Returns a handle, or 0 with a pending Java exception on failure. */
JNIEXPORT jlong JNICALL Java_com_avnivo_translator_NllbEngine_nativeCreate(JNIEnv* env, jobject /*thiz*/,
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

JNIEXPORT void JNICALL Java_com_avnivo_translator_NllbEngine_nativeFree(JNIEnv* /*env*/, jobject /*thiz*/,
                                                                       jlong handle) {
    delete reinterpret_cast<Runtime*>(handle);
}

/**
 * Encodes `sourceIds` and greedily decodes into `targetBosId`, returning the generated token ids
 * (EOS excluded). An empty result means the model produced nothing, which callers treat as failure.
 */
JNIEXPORT jintArray JNICALL
Java_com_avnivo_translator_NllbEngine_nativeTranslate(JNIEnv* env, jobject /*thiz*/, jlong handle,
                                                     jintArray sourceIds, jint targetBosId,
                                                     jint maxNewTokens) {
    const OrtApi* api = ortApi();
    auto* runtime = reinterpret_cast<Runtime*>(handle);
    if (!api || !runtime || !runtime->encoder.session || !runtime->decoder.session) {
        throwJava(env, "NLLB engine is not initialized");
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
    values.items.push_back(encoderIds);
    values.items.push_back(encoderMask);
    if (!encoderIds || !encoderMask) {
        throwJava(env, "could not allocate encoder inputs");
        return nullptr;
    }

    const char* encoderInputs[] = {"input_ids", "attention_mask"};
    const char* encoderOutputs[] = {"last_hidden_state"};
    OrtValue* encoderInputValues[] = {encoderIds, encoderMask};
    OrtValue* hidden = nullptr;
    OrtStatus* status = api->Run(runtime->encoder.session, nullptr, encoderInputs, encoderInputValues, 2,
                                 encoderOutputs, 1, &hidden);
    if (status) {
        throwJava(env, "encoder run: " + takeError(api, status));
        return nullptr;
    }
    values.items.push_back(hidden);

    OrtTensorTypeAndShapeInfo* hiddenInfo = nullptr;
    if ((status = api->GetTensorTypeAndShape(hidden, &hiddenInfo))) {
        throwJava(env, takeError(api, status));
        return nullptr;
    }
    size_t rank = 0;
    api->GetDimensionsCount(hiddenInfo, &rank);
    std::vector<int64_t> hiddenShape(rank);
    api->GetDimensions(hiddenInfo, hiddenShape.data(), rank);
    api->ReleaseTensorTypeAndShapeInfo(hiddenInfo);
    const int64_t hiddenLength = rank == 3 ? hiddenShape[1] : 0;
    if (hiddenLength <= 0) {
        throwJava(env, "encoder returned an unexpected shape");
        return nullptr;
    }

    // Empty past tensors are required inputs even though the cache branch is unused. The encoder
    // cache is declared with the real sequence length, so it needs backing storage of that size.
    const std::vector<int64_t> pastDecoderShape{1, kHeads, 0, kHeadDim};
    const std::vector<int64_t> pastEncoderShape{1, kHeads, hiddenLength, kHeadDim};
    const size_t encoderPastFloats =
        static_cast<size_t>(kHeads) * static_cast<size_t>(hiddenLength) * kHeadDim;
    std::vector<std::vector<float>> zeroEncoder(static_cast<size_t>(kLayers) * 2);
    std::vector<OrtValue*> past(static_cast<size_t>(kLayers) * 4, nullptr);
    std::vector<std::string> pastNames;
    float decoderPastSentinel = 0.0f;

    for (int layer = 0; layer < kLayers; ++layer) {
        for (int kind = 0; kind < 2; ++kind) {
            const char* kindName = kind == 0 ? "decoder" : "encoder";
            const std::vector<int64_t>& shape = kind == 0 ? pastDecoderShape : pastEncoderShape;
            for (int kv = 0; kv < 2; ++kv) {
                OrtValue* value = nullptr;
                if (kind == 0) {
                    // Zero-length tensor: no past decoder tokens exist on the first pass.
                    if (api->CreateTensorWithDataAsOrtValue(runtime->decoder.memory, &decoderPastSentinel,
                                                            0, shape.data(), shape.size(),
                                                            ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &value)) {
                        throwJava(env, "could not allocate decoder cache");
                        return nullptr;
                    }
                } else {
                    std::vector<float>& storage = zeroEncoder[static_cast<size_t>(layer) * 2 + kv];
                    storage.assign(encoderPastFloats, 0.0f);
                    if (api->CreateTensorWithDataAsOrtValue(
                            runtime->decoder.memory, storage.data(), storage.size() * sizeof(float),
                            shape.data(), shape.size(), ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &value)) {
                        throwJava(env, "could not allocate encoder cache");
                        return nullptr;
                    }
                }
                values.items.push_back(value);
                past[static_cast<size_t>(layer) * 4 + kind * 2 + kv] = value;
                pastNames.push_back(std::string("past_key_values.") + std::to_string(layer) + "." +
                                    kindName + (kv == 0 ? ".key" : ".value"));
            }
        }
    }

    std::vector<std::string> decoderInputNames = {"input_ids", "encoder_hidden_states",
                                                 "encoder_attention_mask", "use_cache_branch"};
    decoderInputNames.insert(decoderInputNames.end(), pastNames.begin(), pastNames.end());

    std::vector<int32_t> generated;
    std::vector<int64_t> sequence{kEosId};
    const int cap = maxNewTokens > 0 ? maxNewTokens : 48;
    const std::vector<int64_t> branchShape{1};
    uint8_t cacheBranchValue = 0;  // false: always take the no-cache branch

    for (int step = 0; step < cap; ++step) {
        std::vector<int64_t> ids(sequence.begin(), sequence.end());
        std::vector<int64_t> seqShape{1, static_cast<int64_t>(ids.size())};

        OrtValue* idsValue = int64Tensor(runtime->decoder.memory, ids, seqShape);
        OrtValue* maskValue = int64Tensor(runtime->decoder.memory, mask, sourceShape);
        OrtValue* branchValue = nullptr;
        const bool branchFailed = api->CreateTensorWithDataAsOrtValue(
            runtime->decoder.memory, &cacheBranchValue, sizeof(cacheBranchValue), branchShape.data(),
            branchShape.size(), ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL, &branchValue) != nullptr;
        if (!idsValue || !maskValue || branchFailed) {
            if (idsValue) api->ReleaseValue(idsValue);
            if (maskValue) api->ReleaseValue(maskValue);
            if (branchValue) api->ReleaseValue(branchValue);
            throwJava(env, "could not allocate decoder inputs");
            return nullptr;
        }

        std::vector<const char*> names;
        std::vector<OrtValue*> inputs = {idsValue, hidden, maskValue, branchValue};
        for (const std::string& name : decoderInputNames) names.push_back(name.c_str());

        OrtValue* logits = nullptr;
        const char* outputNames[] = {"logits"};
        status = api->Run(runtime->decoder.session, nullptr, names.data(), inputs.data(),
                          static_cast<int>(inputs.size()), outputNames, 1, &logits);
        if (status) {
            api->ReleaseValue(idsValue);
            api->ReleaseValue(maskValue);
            api->ReleaseValue(branchValue);
            throwJava(env, "decoder run: " + takeError(api, status));
            return nullptr;
        }

        OrtTensorTypeAndShapeInfo* logitsInfo = nullptr;
        api->GetTensorTypeAndShape(logits, &logitsInfo);
        size_t logitsRank = 0;
        api->GetDimensionsCount(logitsInfo, &logitsRank);
        std::vector<int64_t> logitsShape(logitsRank);
        api->GetDimensions(logitsInfo, logitsShape.data(), logitsRank);
        size_t elementCount = 0;
        api->GetTensorShapeElementCount(logitsInfo, &elementCount);
        api->ReleaseTensorTypeAndShapeInfo(logitsInfo);

        float* raw = nullptr;
        api->GetTensorMutableData(logits, reinterpret_cast<void**>(&raw));
        const int64_t vocab = logitsRank == 3 ? logitsShape[2] : 0;
        if (!raw || vocab <= 0) {
            api->ReleaseValue(logits);
            api->ReleaseValue(idsValue);
            api->ReleaseValue(maskValue);
            api->ReleaseValue(branchValue);
            throwJava(env, "decoder returned an unexpected logits shape");
            return nullptr;
        }
        const float* last = raw + elementCount - static_cast<size_t>(vocab);

        int32_t next = 0;
        if (step == 0) {
            next = targetBosId;  // NLLB requires the target language token to start the sequence.
        } else {
            float best = last[0];
            for (int64_t token = 1; token < vocab; ++token) {
                if (last[token] > best) {
                    best = last[token];
                    next = static_cast<int32_t>(token);
                }
            }
        }

        api->ReleaseValue(logits);
        api->ReleaseValue(idsValue);
        api->ReleaseValue(maskValue);
        api->ReleaseValue(branchValue);

        if (next == kEosId) break;
        // Step 0 forces the target language token to open the decoder sequence. It is decoder
        // *input*, not model output, so it must not be returned: doing so decoded to a literal
        // "rus_Cyrl"-style marker at the front of every translation.
        if (step != 0) generated.push_back(next);
        sequence.push_back(next);
    }

    jintArray result = env->NewIntArray(static_cast<jsize>(generated.size()));
    if (result) env->SetIntArrayRegion(result, 0, static_cast<jsize>(generated.size()), generated.data());
    return result;
}

}  // extern "C"
