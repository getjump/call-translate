#include <algorithm>
#include <atomic>
#include <ggml-backend.h>
#include <jni.h>
#include <sentencepiece_processor.h>
#include <string>
#include <thread>
#include <vector>
#include <whisper.h>

struct RecognitionContext {
  whisper_context *whisper;
  std::atomic<bool> canceled{false};
};

static std::string utf8(JNIEnv *env, jstring value) {
  if (!value)
    return "";
  jclass cls = env->FindClass("java/lang/String");
  jmethodID method =
      env->GetMethodID(cls, "getBytes", "(Ljava/lang/String;)[B");
  jstring encoding = env->NewStringUTF("UTF-8");
  auto bytes =
      static_cast<jbyteArray>(env->CallObjectMethod(value, method, encoding));
  std::string result(env->GetArrayLength(bytes), '\0');
  env->GetByteArrayRegion(bytes, 0, result.size(),
                          reinterpret_cast<jbyte *>(result.data()));
  env->DeleteLocalRef(bytes);
  env->DeleteLocalRef(encoding);
  env->DeleteLocalRef(cls);
  return result;
}

static jstring java_string(JNIEnv *env, const std::string &text) {
  jclass cls = env->FindClass("java/lang/String");
  jmethodID method = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
  jbyteArray bytes = env->NewByteArray(text.size());
  env->SetByteArrayRegion(bytes, 0, text.size(),
                          reinterpret_cast<const jbyte *>(text.data()));
  jstring encoding = env->NewStringUTF("UTF-8");
  auto result =
      static_cast<jstring>(env->NewObject(cls, method, bytes, encoding));
  env->DeleteLocalRef(bytes);
  env->DeleteLocalRef(encoding);
  env->DeleteLocalRef(cls);
  return result;
}

static void fail(JNIEnv *env, const char *message) {
  env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_getjump_calltranslate_ml_NativeEngine_asrLanguages(JNIEnv *env,
                                                            jclass) {
  int count = whisper_lang_max_id() + 1;
  jobjectArray output =
      env->NewObjectArray(count, env->FindClass("java/lang/String"), nullptr);
  for (int i = 0; i < count; i++) {
    jstring code = java_string(env, whisper_lang_str(i));
    env->SetObjectArrayElement(output, i, code);
    env->DeleteLocalRef(code);
  }
  return output;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_getjump_calltranslate_ml_NativeEngine_systemInfo(JNIEnv *env, jclass) {
  return java_string(env, std::string("CPU; ") + whisper_print_system_info());
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_getjump_calltranslate_ml_NativeEngine_loadWhisper(JNIEnv *env, jclass,
                                                           jstring path,
                                                           jboolean gpu) {
  ggml_backend_load_all();
  if (gpu && !ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_GPU) &&
      !ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_IGPU)) {
    fail(env, "GPU backend unavailable in this build");
    return 0;
  }
  auto params = whisper_context_default_params();
  params.use_gpu = gpu;
  auto *ctx =
      whisper_init_from_file_with_params(utf8(env, path).c_str(), params);
  if (ctx && !whisper_is_multilingual(ctx)) {
    whisper_free(ctx);
    fail(env, "A multilingual Whisper model is required");
    return 0;
  }
  return ctx ? reinterpret_cast<jlong>(new RecognitionContext{ctx}) : 0;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_getjump_calltranslate_ml_NativeEngine_runWhisper(JNIEnv *env, jclass,
                                                          jlong handle,
                                                          jfloatArray audio,
                                                          jstring language,
                                                          jint audio_context) {
  auto *state = reinterpret_cast<RecognitionContext *>(handle);
  auto *ctx = state->whisper;
  if (state->canceled.load()) {
    fail(env, "Whisper inference canceled");
    return nullptr;
  }
  std::string code = utf8(env, language);
  if (!code.empty() && code != "auto" && whisper_lang_id(code.c_str()) < 0) {
    fail(env, "Unsupported recognition language");
    return nullptr;
  }
  std::vector<float> samples(env->GetArrayLength(audio));
  env->GetFloatArrayRegion(audio, 0, samples.size(), samples.data());
  auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
  params.abort_callback = [](void *data) {
    return static_cast<RecognitionContext *>(data)->canceled.load();
  };
  params.abort_callback_user_data = state;
  params.n_threads =
      std::min(6u, std::max(1u, std::thread::hardware_concurrency()));
  params.language = code.empty() || code == "auto" ? nullptr : code.c_str();
  params.detect_language = false;
  params.translate = false;
  params.no_context = true;
  params.no_timestamps = true;
  params.print_progress = false;
  params.print_realtime = false;
  params.print_timestamps = false;
  params.print_special = false;
  params.greedy.best_of = 1;
  params.temperature_inc = 0;
  params.max_tokens = 128;
  if (audio_context < 0 || audio_context > whisper_n_audio_ctx(ctx)) {
    fail(env, "Invalid audio context size");
    return nullptr;
  }
  params.audio_ctx = audio_context;
  if (whisper_full(ctx, params, samples.data(), samples.size()) != 0) {
    fail(env, "Whisper inference failed");
    return nullptr;
  }
  std::string text;
  for (int i = 0; i < whisper_full_n_segments(ctx); i++)
    text += whisper_full_get_segment_text(ctx, i);
  const char *detected = whisper_lang_str(whisper_full_lang_id(ctx));
  jobjectArray output =
      env->NewObjectArray(2, env->FindClass("java/lang/String"), nullptr);
  jstring transcript = java_string(env, text);
  jstring result_language = java_string(env, detected ? detected : code);
  env->SetObjectArrayElement(output, 0, transcript);
  env->SetObjectArrayElement(output, 1, result_language);
  env->DeleteLocalRef(transcript);
  env->DeleteLocalRef(result_language);
  return output;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_getjump_calltranslate_ml_NativeEngine_freeWhisper(JNIEnv *, jclass,
                                                           jlong handle) {
  auto *state = reinterpret_cast<RecognitionContext *>(handle);
  whisper_free(state->whisper);
  delete state;
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_getjump_calltranslate_ml_NativeEngine_loadTokenizer(JNIEnv *env,
                                                             jclass,
                                                             jstring path) {
  auto *processor = new sentencepiece::SentencePieceProcessor();
  if (!processor->Load(utf8(env, path)).ok()) {
    delete processor;
    fail(env, "SentencePiece model could not be loaded");
    return 0;
  }
  return reinterpret_cast<jlong>(processor);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_getjump_calltranslate_ml_NativeEngine_encode(JNIEnv *env, jclass,
                                                      jlong handle,
                                                      jstring text) {
  std::vector<int> tokens;
  auto *processor =
      reinterpret_cast<sentencepiece::SentencePieceProcessor *>(handle);
  if (!processor->Encode(utf8(env, text), &tokens).ok()) {
    fail(env, "SentencePiece encoding failed");
    return nullptr;
  }
  jintArray result = env->NewIntArray(tokens.size());
  env->SetIntArrayRegion(result, 0, tokens.size(), tokens.data());
  return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_getjump_calltranslate_ml_NativeEngine_decode(JNIEnv *env, jclass,
                                                      jlong handle,
                                                      jintArray input) {
  std::vector<int> tokens(env->GetArrayLength(input));
  env->GetIntArrayRegion(input, 0, tokens.size(), tokens.data());
  std::string text;
  auto *processor =
      reinterpret_cast<sentencepiece::SentencePieceProcessor *>(handle);
  if (!processor->Decode(tokens, &text).ok()) {
    fail(env, "SentencePiece decoding failed");
    return nullptr;
  }
  return java_string(env, text);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_getjump_calltranslate_ml_NativeEngine_freeTokenizer(JNIEnv *, jclass,
                                                             jlong handle) {
  delete reinterpret_cast<sentencepiece::SentencePieceProcessor *>(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_getjump_calltranslate_ml_NativeEngine_gpuAvailable(JNIEnv *, jclass) {
  ggml_backend_load_all();
  return ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_GPU) ||
         ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_IGPU);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_getjump_calltranslate_ml_NativeEngine_cancelWhisper(JNIEnv *, jclass,
                                                             jlong handle) {
  reinterpret_cast<RecognitionContext *>(handle)->canceled.store(true);
}
