// JNI bridge to slimt, for translating the AI's English answer wording with Mozilla's Firefox
// Translations models. Kotlin side: com.vagujhelyigergely.calculatorm3.ai.SlimtNative.
//
// One handle = one loaded language pair (model + vocabularies + shortlist, memory-mapped) and
// its own blocking translator. Every input string must be a single sentence: slimt does no
// sentence splitting itself, the Kotlin side does.

#include <jni.h>
#include <android/log.h>

#include <cstring>
#include <exception>
#include <memory>
#include <string>
#include <vector>

#include "slimt/Frontend.hh"
#include "slimt/Io.hh"
#include "slimt/Model.hh"
#include "slimt/Response.hh"

#define LOG_TAG "AnswerTranslator"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

thread_local std::string last_error;

void record_error(const char *where, const char *what) {
  last_error.assign(where).append(": ").append(what);
  LOGE("%s: %s", where, what);
}

struct Translator {
  std::shared_ptr<slimt::Model> model;
  slimt::Blocking service;
  Translator(std::shared_ptr<slimt::Model> m, const slimt::Config &config)
      : model(std::move(m)), service(config) {}
};

// Mozilla's models come in several sizes (tiny, base, base-memory) that differ in how many
// encoder and decoder layers they have. slimt can't tell by itself, so count them from the
// parameter names in the model file ("encoder_l6_...", "decoder_l2_...").
void detect_layers(const std::string &model_path, size_t &encoder, size_t &decoder) {
  encoder = 0;
  decoder = 0;
  slimt::io::MmapFile file(model_path);
  for (const auto &item : slimt::io::load_items(file.data())) {
    auto highest = [&](const char *prefix, size_t &out) {
      const size_t length = std::strlen(prefix);
      if (item.name.compare(0, length, prefix) != 0) return;
      size_t index = 0;
      size_t pos = length;
      while (pos < item.name.size() && item.name[pos] >= '0' && item.name[pos] <= '9') {
        index = index * 10 + static_cast<size_t>(item.name[pos] - '0');
        ++pos;
      }
      if (pos > length && index > out) out = index;
    };
    highest("encoder_l", encoder);
    highest("decoder_l", decoder);
  }
}

std::string to_string(JNIEnv *env, jstring value) {
  if (value == nullptr) return {};
  const char *chars = env->GetStringUTFChars(value, nullptr);
  std::string out(chars);
  env->ReleaseStringUTFChars(value, chars);
  return out;
}

// NewStringUTF wants modified UTF-8, which differs from real UTF-8 for characters outside the
// BMP (emoji, some CJK). Build the Java string from UTF-8 bytes instead.
jstring to_jstring(JNIEnv *env, const std::string &utf8) {
  jbyteArray bytes = env->NewByteArray(static_cast<jsize>(utf8.size()));
  env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(utf8.size()),
                          reinterpret_cast<const jbyte *>(utf8.data()));
  jclass string_class = env->FindClass("java/lang/String");
  jmethodID constructor = env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
  jstring charset = env->NewStringUTF("UTF-8");
  auto result = static_cast<jstring>(env->NewObject(string_class, constructor, bytes, charset));
  env->DeleteLocalRef(charset);
  env->DeleteLocalRef(bytes);
  env->DeleteLocalRef(string_class);
  return result;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_vagujhelyigergely_calculatorm3_ai_SlimtNative_load(JNIEnv *env, jobject, jstring model,
                                                            jstring vocabulary,
                                                            jstring target_vocabulary,
                                                            jstring shortlist) {
  try {
    slimt::Package<std::string> package{
        .model = to_string(env, model),
        .vocabulary = to_string(env, vocabulary),
        .target_vocabulary = to_string(env, target_vocabulary),
        .shortlist = to_string(env, shortlist),
        .ssplit = std::string(),
    };
    size_t encoder = 0;
    size_t decoder = 0;
    detect_layers(package.model, encoder, decoder);
    slimt::Model::Config model_config = slimt::preset::tiny();
    if (encoder > 0) model_config.encoder_layers = encoder;
    if (decoder > 0) model_config.decoder_layers = decoder;

    slimt::Config config;
    config.workers = 1;
    config.cache_size = 0;
    auto *translator =
        new Translator(std::make_shared<slimt::Model>(model_config, package), config);
    return reinterpret_cast<jlong>(translator);
  } catch (const std::exception &e) {
    record_error("load", e.what());
  } catch (...) {
    record_error("load", "unknown error");
  }
  return 0;
}

JNIEXPORT jobjectArray JNICALL
Java_com_vagujhelyigergely_calculatorm3_ai_SlimtNative_translate(JNIEnv *env, jobject,
                                                                 jlong handle,
                                                                 jobjectArray sentences) {
  try {
    auto *translator = reinterpret_cast<Translator *>(handle);
    if (translator == nullptr) {
      record_error("translate", "no model loaded");
      return nullptr;
    }
    const jsize count = env->GetArrayLength(sentences);
    std::vector<std::string> sources;
    sources.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
      auto item = static_cast<jstring>(env->GetObjectArrayElement(sentences, i));
      sources.push_back(to_string(env, item));
      env->DeleteLocalRef(item);
    }

    slimt::Options options;
    std::vector<slimt::Response> responses =
        translator->service.translate(translator->model, std::move(sources), options);

    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray out =
        env->NewObjectArray(static_cast<jsize>(responses.size()), string_class, nullptr);
    for (size_t i = 0; i < responses.size(); ++i) {
      jstring text = to_jstring(env, responses[i].target.text);
      env->SetObjectArrayElement(out, static_cast<jsize>(i), text);
      env->DeleteLocalRef(text);
    }
    env->DeleteLocalRef(string_class);
    return out;
  } catch (const std::exception &e) {
    record_error("translate", e.what());
  } catch (...) {
    record_error("translate", "unknown error");
  }
  return nullptr;
}

JNIEXPORT void JNICALL
Java_com_vagujhelyigergely_calculatorm3_ai_SlimtNative_release(JNIEnv *, jobject, jlong handle) {
  delete reinterpret_cast<Translator *>(handle);
}

JNIEXPORT jstring JNICALL
Java_com_vagujhelyigergely_calculatorm3_ai_SlimtNative_lastError(JNIEnv *env, jobject) {
  return to_jstring(env, last_error);
}

}  // extern "C"
