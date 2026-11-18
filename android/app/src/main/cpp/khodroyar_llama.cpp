// JNI layer for com.khodroyar.app.ai.offline.llama.LlamaNative. Marshalling only: every inference
// decision lives in khodroyar_engine.cpp, which the ARM64 evaluation runs unchanged on real files.

#include <jni.h>

#include <cstdint>
#include <string>
#include <vector>

#include "khodroyar_engine.h"

namespace {

std::string to_std(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}

// NewStringUTF expects *modified* UTF-8 and aborts on 4-byte sequences (emoji); go through UTF-16.
jstring to_java(JNIEnv * env, const std::string & s) {
    std::u16string u16;
    u16.reserve(s.size());
    for (size_t i = 0; i < s.size();) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        uint32_t cp; size_t len;
        if (c < 0x80)              { cp = c; len = 1; }
        else if ((c >> 5) == 0x6)  { cp = c & 0x1F; len = 2; }
        else if ((c >> 4) == 0xE)  { cp = c & 0x0F; len = 3; }
        else if ((c >> 3) == 0x1E) { cp = c & 0x07; len = 4; }
        else { u16.push_back(0xFFFD); i++; continue; }
        if (i + len > s.size()) { u16.push_back(0xFFFD); break; }
        bool ok = true;
        for (size_t k = 1; k < len; k++) {
            const unsigned char cc = static_cast<unsigned char>(s[i + k]);
            if ((cc >> 6) != 0x2) { ok = false; break; }
            cp = (cp << 6) | (cc & 0x3F);
        }
        if (!ok) { u16.push_back(0xFFFD); i++; continue; }
        if (cp >= 0x10000) {
            cp -= 0x10000;
            u16.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
            u16.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
        } else {
            u16.push_back(static_cast<char16_t>(cp));
        }
        i += len;
    }
    return env->NewString(reinterpret_cast<const jchar *>(u16.data()), static_cast<jsize>(u16.size()));
}

khodroyar::Engine * engine(jlong handle) { return reinterpret_cast<khodroyar::Engine *>(handle); }

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_khodroyar_app_ai_offline_llama_LlamaNative_initBackends(JNIEnv * env, jobject, jstring native_lib_dir) {
    return to_java(env, khodroyar::init_backends(to_std(env, native_lib_dir)));
}

JNIEXPORT jlong JNICALL
Java_com_khodroyar_app_ai_offline_llama_LlamaNative_load(JNIEnv * env, jobject, jstring model_path,
        jint n_ctx, jint n_threads, jint n_batch, jboolean kv_q8) {
    return reinterpret_cast<jlong>(khodroyar::load(to_std(env, model_path), n_ctx, n_threads, n_batch, kv_q8 == JNI_TRUE));
}

JNIEXPORT jboolean JNICALL
Java_com_khodroyar_app_ai_offline_llama_LlamaNative_attachVision(JNIEnv * env, jobject, jlong handle,
        jstring mmproj_path, jint image_max_tokens) {
    return khodroyar::attach_vision(engine(handle), to_std(env, mmproj_path), image_max_tokens) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_khodroyar_app_ai_offline_llama_LlamaNative_detachVision(JNIEnv *, jobject, jlong handle) {
    khodroyar::detach_vision(engine(handle));
}

JNIEXPORT void JNICALL
Java_com_khodroyar_app_ai_offline_llama_LlamaNative_abort(JNIEnv *, jobject, jlong handle) {
    khodroyar::abort(engine(handle));
}

JNIEXPORT void JNICALL
Java_com_khodroyar_app_ai_offline_llama_LlamaNative_free(JNIEnv *, jobject, jlong handle) {
    khodroyar::free(engine(handle));
}

JNIEXPORT jstring JNICALL
Java_com_khodroyar_app_ai_offline_llama_LlamaNative_info(JNIEnv * env, jobject, jlong handle) {
    return to_java(env, khodroyar::info(engine(handle)));
}

JNIEXPORT jstring JNICALL
Java_com_khodroyar_app_ai_offline_llama_LlamaNative_chat(JNIEnv * env, jobject, jlong handle, jstring request_json,
        jobjectArray images, jintArray widths, jintArray heights, jobjectArray ids, jobject listener) {
    std::vector<khodroyar::Image> decoded;
    const jsize n = images ? env->GetArrayLength(images) : 0;
    if (n > 0) {
        if (!widths || !heights || !ids || env->GetArrayLength(widths) != n || env->GetArrayLength(heights) != n ||
            env->GetArrayLength(ids) != n) {
            return to_java(env, R"({"ok":false,"error":"bad_image","message":"image arrays differ in length"})");
        }
        std::vector<jint> w(n), h(n);
        env->GetIntArrayRegion(widths, 0, n, w.data());
        env->GetIntArrayRegion(heights, 0, n, h.data());
        decoded.resize(n);
        for (jsize i = 0; i < n; i++) {
            auto pixels = static_cast<jbyteArray>(env->GetObjectArrayElement(images, i));
            const jsize len = pixels ? env->GetArrayLength(pixels) : 0;
            decoded[i].rgb.resize(static_cast<size_t>(len));
            if (len > 0) env->GetByteArrayRegion(pixels, 0, len, reinterpret_cast<jbyte *>(decoded[i].rgb.data()));
            env->DeleteLocalRef(pixels);
            decoded[i].width = w[i];
            decoded[i].height = h[i];
            auto id = static_cast<jstring>(env->GetObjectArrayElement(ids, i));
            decoded[i].id = to_std(env, id);
            env->DeleteLocalRef(id);
        }
    }

    // Look the callback up on the object's own class (a lambda/SAM class), not on the interface.
    jclass listener_class = listener ? env->GetObjectClass(listener) : nullptr;
    jmethodID on_content = listener_class ? env->GetMethodID(listener_class, "onContent", "(Ljava/lang/String;)Z") : nullptr;
    if (!on_content) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return to_java(env, R"({"ok":false,"error":"bad_listener","message":"listener has no onContent"})");
    }
    const std::string result = khodroyar::chat(engine(handle), to_std(env, request_json), decoded,
        [&](const std::string & visible) {
            jstring js = to_java(env, visible);
            const jboolean keep_going = env->CallBooleanMethod(listener, on_content, js);
            env->DeleteLocalRef(js);
            if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
            return keep_going == JNI_TRUE;
        });
    env->DeleteLocalRef(listener_class);
    return to_java(env, result);
}

} // extern "C"
