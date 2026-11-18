// KhodroYar on-device engine over official llama.cpp v1.0.0 (libllama + libmtmd + llama-common).
// Plain C++ so the exact code that ships in the APK (through khodroyar_llama.cpp, the JNI layer) is also
// what tools/offline-eval runs on an ARM64 machine with the real model files and document photos.
#pragma once

#include <functional>
#include <string>
#include <vector>

namespace khodroyar {

struct Engine;

/** An upright RGB888 image (width * height * 3 bytes) and the SHA-256 of its pixels (prompt-cache key). */
struct Image {
    std::vector<unsigned char> rgb;
    int width = 0;
    int height = 0;
    std::string id;
};

/** Once per process: logging + the ggml CPU backend variant that matches this CPU. Returns diagnostics JSON. */
std::string init_backends(const std::string & native_lib_dir);

/** nullptr when the model or the context cannot be created. */
Engine * load(const std::string & model_path, int n_ctx, int n_threads, int n_batch, bool kv_q8);
bool attach_vision(Engine * engine, const std::string & mmproj_path, int image_max_tokens);
void detach_vision(Engine * engine);

/**
 * request_json: {"messages":[OpenAI-style], "tools":[OpenAI-style], "max_tokens", "temperature", "top_k",
 * "top_p", "seed", "enable_thinking"}; an image position is a {"type":"media_marker","text":<marker>} part.
 * on_content receives the visible reply so far (complete UTF-8); returning false stops generation.
 * Returns {"ok":true,"content","reasoning","tool_calls":[{name,arguments,id}],"stop","timings":{...}}
 * or {"ok":false,"error","message"}.
 */
std::string chat(Engine * engine, const std::string & request_json, const std::vector<Image> & images,
                 const std::function<bool(const std::string &)> & on_content);

void abort(Engine * engine);
void free(Engine * engine);
std::string info(Engine * engine);
const char * media_marker();

} // namespace khodroyar
