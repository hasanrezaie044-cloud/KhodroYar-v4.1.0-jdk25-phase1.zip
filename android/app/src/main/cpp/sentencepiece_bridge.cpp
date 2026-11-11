#include <jni.h>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>
#include "sentencepiece_processor.h"

namespace {
void fail(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
std::string bytes(JNIEnv* env, jbyteArray input) {
    const auto size = env->GetArrayLength(input);
    std::string data(static_cast<size_t>(size), '\0');
    env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte*>(data.data()));
    return data;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_khodroyar_app_ai_albert_SentencePieceBridge_createNative(JNIEnv* env, jobject, jbyteArray input) {
    try {
        if (input == nullptr || env->GetArrayLength(input) > 2000000) { fail(env, "Invalid tokenizer asset"); return 0; }
        auto processor = std::make_unique<sentencepiece::SentencePieceProcessor>();
        auto status = processor->LoadFromSerializedProto(bytes(env, input));
        if (!status.ok() || processor->GetPieceSize() != 30000 || processor->PieceToId("[CLS]") != 2 || processor->PieceToId("[SEP]") != 3) {
            fail(env, "Incompatible tokenizer asset"); return 0;
        }
        return reinterpret_cast<jlong>(processor.release());
    } catch (const std::exception&) { fail(env, "Could not load tokenizer"); return 0; }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_khodroyar_app_ai_albert_SentencePieceBridge_encodeNative(JNIEnv* env, jobject, jlong handle, jbyteArray input) {
    try {
        if (handle == 0 || input == nullptr || env->GetArrayLength(input) > 8192) { fail(env, "Invalid tokenizer input"); return nullptr; }
        auto* processor = reinterpret_cast<sentencepiece::SentencePieceProcessor*>(handle);
        std::vector<int> ids;
        const auto status = processor->Encode(bytes(env, input), &ids);
        if (!status.ok()) { fail(env, "Could not tokenize input"); return nullptr; }
        auto result = env->NewIntArray(static_cast<jsize>(ids.size()));
        if (result == nullptr) return nullptr;
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(ids.size()), reinterpret_cast<const jint*>(ids.data()));
        return result;
    } catch (const std::exception&) { fail(env, "Could not encode input"); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_khodroyar_app_ai_albert_SentencePieceBridge_destroyNative(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<sentencepiece::SentencePieceProcessor*>(handle);
}
