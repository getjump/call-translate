#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

#include "onnxruntime_c_api.h"
#include "whisper_mel.h"

namespace {

constexpr int WINDOW = 200;
constexpr int CACHE = 199;
constexpr int LAYERS = 4;
constexpr int HEADS = 20;
constexpr int HEAD_DIM = 64;
constexpr int VOCABULARY = 51866;
constexpr int EOT = 50257;
constexpr int SOT = 50258;
constexpr int TRANSCRIBE = 50360;
constexpr int NO_TIMESTAMPS = 50364;
constexpr size_t KV_BYTES = HEADS * CACHE * HEAD_DIM;

class Canceled : public std::runtime_error {
public:
    Canceled() : std::runtime_error("NPU transcription canceled") {}
};

void check(const OrtApi* api, OrtStatus* status, const char* operation) {
    if (!status) return;
    std::string message = std::string(operation) + ": " + api->GetErrorMessage(status);
    api->ReleaseStatus(status);
    throw std::runtime_error(message);
}

struct Values {
    const OrtApi* api;
    std::vector<OrtValue*> values;

    explicit Values(const OrtApi* api, size_t size = 0) : api(api), values(size, nullptr) {}
    ~Values() {
        for (auto* value : values) {
            if (value) api->ReleaseValue(value);
        }
    }
};

struct Handle {
    void* library = nullptr;
    const OrtApi* api = nullptr;
    OrtEnv* environment = nullptr;
    OrtSession* encoder = nullptr;
    OrtSession* decoder = nullptr;
    OrtMemoryInfo* memory = nullptr;
    std::unique_ptr<WhisperMel> mel;
    std::mutex inference;
    std::atomic<int64_t> canceledThrough{0};
    std::atomic<bool> closing{false};

    ~Handle() {
        if (api) {
            if (encoder) api->ReleaseSession(encoder);
            if (decoder) api->ReleaseSession(decoder);
            if (memory) api->ReleaseMemoryInfo(memory);
            if (environment) api->ReleaseEnv(environment);
        }
        if (library) dlclose(library);
    }

    bool canceled(int64_t request) const {
        return closing.load(std::memory_order_acquire) ||
               canceledThrough.load(std::memory_order_acquire) >= request;
    }

    void checkCanceled(int64_t request) const {
        if (canceled(request)) throw Canceled();
    }

    void cancel(int64_t request) {
        int64_t previous = canceledThrough.load(std::memory_order_relaxed);
        while (previous < request &&
               !canceledThrough.compare_exchange_weak(previous, request, std::memory_order_release,
                                                       std::memory_order_relaxed)) {}
    }
};

std::mutex handlesMutex;
std::unordered_map<int64_t, std::shared_ptr<Handle>> handles;
std::atomic<int64_t> nextHandle{0};

std::shared_ptr<Handle> findHandle(int64_t id) {
    std::lock_guard<std::mutex> lock(handlesMutex);
    auto found = handles.find(id);
    if (found == handles.end()) throw std::runtime_error("NPU session is closed");
    return found->second;
}

std::string string(JNIEnv* environment, jstring input) {
    if (!input) throw std::invalid_argument("NPU path is required");
    const char* value = environment->GetStringUTFChars(input, nullptr);
    if (!value) throw std::runtime_error("cannot read NPU path");
    std::string result(value);
    environment->ReleaseStringUTFChars(input, value);
    return result;
}

void throwJava(JNIEnv* environment, const char* type, const std::exception& error) {
    if (environment->ExceptionCheck()) return;
    jclass exception = environment->FindClass(type);
    if (exception) environment->ThrowNew(exception, error.what());
}

OrtSession* createSession(Handle& handle, const std::string& path, const std::string& libraries) {
    OrtSessionOptions* options = nullptr;
    check(handle.api, handle.api->CreateSessionOptions(&options), "create QNN session options");
    try {
        check(handle.api, handle.api->SetSessionGraphOptimizationLevel(options, ORT_DISABLE_ALL),
              "disable graph optimizations");
        check(handle.api, handle.api->AddSessionConfigEntry(options, "session.disable_cpu_ep_fallback", "1"),
              "disable CPU fallback");
        check(handle.api, handle.api->SetIntraOpNumThreads(options, 1), "set intra-op threads");
        check(handle.api, handle.api->SetInterOpNumThreads(options, 1), "set inter-op threads");
        std::string backend = libraries + "/libQnnHtp.so";
        const char* keys[] = {"backend_path", "htp_performance_mode", "profiling_level"};
        const char* values[] = {backend.c_str(), "high_performance", "off"};
        check(handle.api,
              handle.api->SessionOptionsAppendExecutionProvider(options, "QNN", keys, values, 3),
              "append QNN execution provider");
        OrtSession* session = nullptr;
        check(handle.api, handle.api->CreateSession(handle.environment, path.c_str(), options, &session),
              "load precompiled QNN model");
        handle.api->ReleaseSessionOptions(options);
        return session;
    } catch (...) {
        handle.api->ReleaseSessionOptions(options);
        throw;
    }
}

OrtValue* tensor(Handle& handle, void* data, size_t bytes, const int64_t* shape, size_t dimensions,
                 ONNXTensorElementDataType type, Values& owner) {
    OrtValue* value = nullptr;
    check(handle.api,
          handle.api->CreateTensorWithDataAsOrtValue(handle.memory, data, bytes, shape, dimensions, type, &value),
          "create QNN input tensor");
    owner.values.push_back(value);
    return value;
}

void* tensorData(Handle& handle, OrtValue* tensor) {
    void* data = nullptr;
    check(handle.api, handle.api->GetTensorMutableData(tensor, &data), "read QNN tensor");
    return data;
}

std::vector<const char*> names(const std::vector<std::string>& storage) {
    std::vector<const char*> result;
    for (const auto& value : storage) result.push_back(value.c_str());
    return result;
}

std::vector<int32_t> transcribe(Handle& handle, int64_t request, const std::vector<float>& samples,
                                int language, const std::vector<int32_t>& languageTokens) {
    std::lock_guard<std::mutex> inference(handle.inference);
    handle.checkCanceled(request);
    auto mel = handle.mel->compute(samples, [&] { return handle.canceled(request); });
    handle.checkCanceled(request);
    const int64_t melShape[] = {1, 128, 3000};
    Values encoderInputs(handle.api);
    OrtValue* features = tensor(handle, mel.data(), mel.size() * 2, melShape, 3,
                                ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT16, encoderInputs);
    std::vector<std::string> crossNames;
    for (int layer = 0; layer < LAYERS; layer++) crossNames.push_back("k_cache_cross_" + std::to_string(layer));
    for (int layer = 0; layer < LAYERS; layer++) crossNames.push_back("v_cache_cross_" + std::to_string(layer));
    auto encoderOutputNames = names(crossNames);
    const char* featureNames[] = {"input_features"};
    const OrtValue* featureValues[] = {features};
    Values cross(handle.api, 2 * LAYERS);
    check(handle.api,
          handle.api->Run(handle.encoder, nullptr, featureNames, featureValues, 1,
                          encoderOutputNames.data(), encoderOutputNames.size(), cross.values.data()),
          "run QNN encoder");
    handle.checkCanceled(request);

    std::array<std::vector<uint8_t>, 2 * LAYERS> cache;
    for (auto& value : cache) value.assign(KV_BYTES, 128);
    std::array<uint16_t, WINDOW> mask{};
    int32_t token = SOT;
    int32_t position = 0;
    const int64_t tokenShape[] = {1, 1};
    const int64_t maskShape[] = {1, 1, 1, WINDOW};
    const int64_t keyShape[] = {HEADS, 1, HEAD_DIM, CACHE};
    const int64_t valueShape[] = {HEADS, 1, CACHE, HEAD_DIM};
    const int64_t positionShape[] = {1};
    Values ownedInputs(handle.api);
    std::vector<OrtValue*> inputs;
    std::vector<std::string> inputNames = {"input_ids", "attention_mask"};
    inputs.push_back(tensor(handle, &token, sizeof(token), tokenShape, 2,
                            ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32, ownedInputs));
    inputs.push_back(tensor(handle, mask.data(), mask.size() * 2, maskShape, 4,
                            ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT16, ownedInputs));
    for (int layer = 0; layer < LAYERS; layer++) {
        inputNames.push_back("k_cache_self_" + std::to_string(layer) + "_in");
        inputNames.push_back("v_cache_self_" + std::to_string(layer) + "_in");
        inputs.push_back(tensor(handle, cache[2 * layer].data(), KV_BYTES, keyShape, 4,
                                ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT8, ownedInputs));
        inputs.push_back(tensor(handle, cache[2 * layer + 1].data(), KV_BYTES, valueShape, 4,
                                ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT8, ownedInputs));
    }
    for (int layer = 0; layer < LAYERS; layer++) {
        inputNames.push_back("k_cache_cross_" + std::to_string(layer));
        inputNames.push_back("v_cache_cross_" + std::to_string(layer));
        inputs.push_back(cross.values[layer]);
        inputs.push_back(cross.values[LAYERS + layer]);
    }
    inputNames.push_back("position_ids");
    inputs.push_back(tensor(handle, &position, sizeof(position), positionShape, 1,
                            ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32, ownedInputs));
    std::vector<std::string> outputNames = {"logits"};
    for (int layer = 0; layer < LAYERS; layer++) {
        outputNames.push_back("k_cache_self_" + std::to_string(layer) + "_out");
        outputNames.push_back("v_cache_self_" + std::to_string(layer) + "_out");
    }
    auto inputNamesC = names(inputNames);
    auto outputNamesC = names(outputNames);
    std::vector<int32_t> output(1, language);
    bool ended = false;
    for (int step = 0; step < CACHE; step++) {
        handle.checkCanceled(request);
        position = step;
        mask[WINDOW - 1 - step] = 65535;
        Values decoded(handle.api, 1 + 2 * LAYERS);
        check(handle.api,
              handle.api->Run(handle.decoder, nullptr, inputNamesC.data(),
                              reinterpret_cast<const OrtValue* const*>(inputs.data()), inputs.size(),
                              outputNamesC.data(), outputNamesC.size(), decoded.values.data()),
              "run QNN decoder");
        handle.checkCanceled(request);
        for (int index = 0; index < 2 * LAYERS; index++)
            std::memcpy(cache[index].data(), tensorData(handle, decoded.values[1 + index]), KV_BYTES);
        auto* logits = static_cast<uint16_t*>(tensorData(handle, decoded.values[0]));
        if (step == 0) {
            if (language == -1) {
                language = languageTokens[0];
                for (int candidate : languageTokens) {
                    if (logits[candidate] > logits[language]) language = candidate;
                }
            }
            output[0] = language;
            token = language;
        } else if (step == 1) {
            token = TRANSCRIBE;
        } else if (step == 2) {
            token = NO_TIMESTAMPS;
        } else {
            int best = 0;
            for (int candidate = 1; candidate <= EOT; candidate++) {
                if (logits[candidate] > logits[best]) best = candidate;
            }
            if (best == EOT) {
                ended = true;
                break;
            }
            output.push_back(best);
            token = best;
        }
    }
    handle.checkCanceled(request);
    if (!ended) throw std::runtime_error("NPU transcription exceeded the 195-token output limit");
    return output;
}

}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_getjump_calltranslate_ml_npu_HtpWhisper_nativeInit(
    JNIEnv* environment, jclass, jstring libraries, jstring encoder, jstring decoder, jbyteArray filters) {
    try {
        std::string libraryDirectory = string(environment, libraries);
        if (!filters) throw std::invalid_argument("Whisper mel filters are required");
        std::vector<uint8_t> filterBytes(environment->GetArrayLength(filters));
        environment->GetByteArrayRegion(filters, 0, filterBytes.size(), reinterpret_cast<jbyte*>(filterBytes.data()));
        if (environment->ExceptionCheck()) return 0;
        auto handle = std::make_shared<Handle>();
        handle->mel = std::make_unique<WhisperMel>(filterBytes);
        std::string runtime = libraryDirectory + "/libonnxruntime_qnn.so";
        handle->library = dlopen(runtime.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (!handle->library) throw std::runtime_error(std::string("load QNN ONNX Runtime: ") + dlerror());
        auto apiBase = reinterpret_cast<const OrtApiBase* (*)()>(dlsym(handle->library, "OrtGetApiBase"));
        if (!apiBase) throw std::runtime_error("QNN ONNX Runtime has no C API");
        handle->api = apiBase()->GetApi(ORT_API_VERSION);
        if (!handle->api) throw std::runtime_error("QNN ONNX Runtime C API version mismatch");
        check(handle->api, handle->api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "WhisperNpu", &handle->environment),
              "create QNN environment");
        check(handle->api,
              handle->api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &handle->memory),
              "create host tensor memory");
        handle->encoder = createSession(*handle, string(environment, encoder), libraryDirectory);
        handle->decoder = createSession(*handle, string(environment, decoder), libraryDirectory);
        __android_log_print(ANDROID_LOG_INFO, "WhisperNpu", "ORT %s, QNN HTP81 turbo w8a16, CPU fallback disabled",
                            apiBase()->GetVersionString());
        int64_t id = ++nextHandle;
        std::lock_guard<std::mutex> lock(handlesMutex);
        handles.emplace(id, std::move(handle));
        return id;
    } catch (const std::invalid_argument& error) {
        throwJava(environment, "java/lang/IllegalArgumentException", error);
    } catch (const std::exception& error) {
        throwJava(environment, "java/lang/IllegalStateException", error);
    }
    return 0;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_getjump_calltranslate_ml_npu_HtpWhisper_nativeTranscribe(
    JNIEnv* environment, jclass, jlong id, jlong request, jfloatArray audio, jint language,
    jintArray languages, jint startToken, jint transcribeToken, jint noTimestampsToken) {
    try {
        if (request <= 0 || !audio || !languages || startToken != SOT ||
            transcribeToken != TRANSCRIBE || noTimestampsToken != NO_TIMESTAMPS)
            throw std::invalid_argument("invalid Whisper NPU request");
        auto handle = findHandle(id);
        handle->checkCanceled(request);
        int sampleCount = environment->GetArrayLength(audio);
        if (sampleCount < 16000 || sampleCount > 480000)
            throw std::invalid_argument("Whisper audio must contain 1 to 30 seconds at 16 kHz");
        std::vector<float> samples(sampleCount);
        environment->GetFloatArrayRegion(audio, 0, sampleCount, samples.data());
        std::vector<int32_t> languageTokens(environment->GetArrayLength(languages));
        environment->GetIntArrayRegion(languages, 0, languageTokens.size(), languageTokens.data());
        if (environment->ExceptionCheck()) return nullptr;
        if (languageTokens.size() != 100) throw std::invalid_argument("invalid Whisper language vocabulary");
        bool supported = language == -1;
        for (int index = 0; index < 100; index++) {
            if (languageTokens[index] != 50259 + index)
                throw std::invalid_argument("invalid Whisper language token order");
            if (language == languageTokens[index]) supported = true;
        }
        if (!supported) throw std::invalid_argument("unsupported Whisper language token");
        for (float sample : samples) {
            if (!std::isfinite(sample)) throw std::invalid_argument("non-finite Whisper audio sample");
        }
        auto output = transcribe(*handle, request, samples, language, languageTokens);
        jintArray result = environment->NewIntArray(output.size());
        if (result) environment->SetIntArrayRegion(result, 0, output.size(), output.data());
        return result;
    } catch (const Canceled& error) {
        throwJava(environment, "java/util/concurrent/CancellationException", error);
    } catch (const std::invalid_argument& error) {
        throwJava(environment, "java/lang/IllegalArgumentException", error);
    } catch (const std::exception& error) {
        throwJava(environment, "java/lang/IllegalStateException", error);
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_getjump_calltranslate_ml_npu_HtpWhisper_nativeCancel(JNIEnv*, jclass, jlong id, jlong request) {
    std::shared_ptr<Handle> handle;
    {
        std::lock_guard<std::mutex> lock(handlesMutex);
        auto found = handles.find(id);
        if (found == handles.end()) return;
        handle = found->second;
    }
    handle->cancel(request);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_getjump_calltranslate_ml_npu_HtpWhisper_nativeFree(JNIEnv*, jclass, jlong id) {
    std::shared_ptr<Handle> handle;
    {
        std::lock_guard<std::mutex> lock(handlesMutex);
        auto found = handles.find(id);
        if (found == handles.end()) return;
        handle = std::move(found->second);
        handles.erase(found);
    }
    handle->closing.store(true, std::memory_order_release);
}
