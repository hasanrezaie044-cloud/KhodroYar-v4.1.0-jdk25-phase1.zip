// KhodroYar on-device engine over official llama.cpp v1.0.0 (see khodroyar_engine.h).
//
// Uses only the public llama.cpp APIs that llama.cpp's own tools use:
//   * libllama           model / context / decode
//   * libllama-common    jinja chat templates + the native tool-call parser (common_chat_session),
//                        the sampler chain with the lazy tool-call grammar
//   * libmtmd            the vision projector: RGB pixels -> image embeddings -> decoder
// The request is an OpenAI-style transcript (messages + tools) so the Kotlin side stays a thin,
// testable adapter. Images are real RGB pixel buffers decoded on Android; the transcript only marks
// where each image sits (a "media_marker" content part). Nothing executes tools here: parsed tool
// calls are returned to PersianToolAgent, which validates, asks for approval, and runs them.
//
// Prompt caching: Qwen3.5 is a hybrid model (Gated-DeltaNet recurrent layers + attention), whose
// recurrent state cannot be truncated. Like llama-server, we keep "context checkpoints" of the
// partial (recurrent) state at the end of each prompt and restore the newest one that is a prefix of
// the next request, so an agent step only pre-fills the new tool result instead of the whole chat.

#include "khodroyar_engine.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"
#include "common.h"
#include "chat.h"
#include "sampling.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "KhodroYarLlama"
#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOG_TEXT(prio, text) __android_log_write(prio, TAG, text)
#define PRIO_ERROR ANDROID_LOG_ERROR
#define PRIO_WARN ANDROID_LOG_WARN
#define PRIO_INFO ANDROID_LOG_INFO
#else
#include <cstdio>
#define LOGI(...) (std::fprintf(stderr, "I " TAG ": " __VA_ARGS__), std::fputc('\n', stderr))
#define LOGE(...) (std::fprintf(stderr, "E " TAG ": " __VA_ARGS__), std::fputc('\n', stderr))
#define LOG_TEXT(prio, text) std::fputs(text, stderr)
#define PRIO_ERROR 0
#define PRIO_WARN 0
#define PRIO_INFO 0
#endif

namespace {

using clock_type = std::chrono::steady_clock;

double ms_since(clock_type::time_point t0) {
    return std::chrono::duration<double, std::milli>(clock_type::now() - t0).count();
}

std::mutex g_log_mutex;
std::string g_backend_log; // "load_backend: ..." lines, for the diagnostics screen

void log_callback(ggml_log_level level, const char * text, void *) {
    if (text == nullptr) return;
    if (std::strstr(text, "load_backend") != nullptr || std::strstr(text, "loaded CPU backend") != nullptr) {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        if (g_backend_log.size() < 4096) g_backend_log += text;
    }
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: LOG_TEXT(PRIO_ERROR, text); break;
        case GGML_LOG_LEVEL_WARN:  LOG_TEXT(PRIO_WARN, text); break;
        case GGML_LOG_LEVEL_INFO:  LOG_TEXT(PRIO_INFO, text); break;
        default: break; // DEBUG / CONT are too chatty for logcat
    }
}

struct Entry {
    llama_token token = LLAMA_TOKEN_NULL; // text token, or LLAMA_TOKEN_NULL for an image
    std::string media_id;                 // SHA-256 of the RGB pixels for an image
    llama_pos   n_pos = 1;                // decoder positions this entry occupies
    bool operator==(const Entry & o) const { return token == o.token && media_id == o.media_id; }
};

struct Checkpoint {
    size_t n_entries = 0;
    llama_pos pos = 0;
    std::vector<uint8_t> data;
};

struct EngineImpl {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    mtmd_context * vision = nullptr;
    common_chat_templates_ptr templates;
    std::unique_ptr<common_batch> batch;
    int n_batch = 512;
    int n_threads = 4;
    std::string model_path;
    std::string vision_path;

    std::vector<Entry> cached;            // exactly what the KV / recurrent memory holds for seq 0
    llama_pos n_past = 0;
    std::vector<Checkpoint> checkpoints;  // newest last
    bool needs_checkpoints = false;       // recurrent / hybrid / SWA memory

    std::mutex mutex;
    std::atomic<bool> abort{false};

    ~EngineImpl() {
        if (vision) mtmd_free(vision);
        batch.reset();
        templates.reset();
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
    }

    void reset_cache() {
        llama_memory_clear(llama_get_memory(ctx), true);
        cached.clear();
        checkpoints.clear();
        n_past = 0;
    }
};

constexpr size_t MAX_CHECKPOINTS = 4; // ~50 MB each for Qwen3.5-4B (recurrent state only)
constexpr size_t CHECKPOINT_TAIL = 4; // tokens left after a checkpoint so a repeated prompt still decodes

bool abort_callback(void * data) {
    return static_cast<EngineImpl *>(data)->abort.load(std::memory_order_relaxed);
}

// Streaming may stop in the middle of a multi-byte character; only forward complete UTF-8.
size_t utf8_complete_prefix(const std::string & s) {
    size_t n = s.size();
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) != 0x80) {
            size_t need = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
            return (n - (i - 1)) >= need ? n : i - 1;
        }
        i--; back++;
    }
    return n;
}

std::string error_json(const std::string & code, const std::string & message) {
    common_json out = common_json::object();
    out["ok"] = false;
    out["error"] = code;
    out["message"] = message;
    return out.dump_safe();
}

struct Segment {
    std::string text;
    bool parse_special = true;
    int image = -1; // index into the request's images when this segment is an image
};

// Rendered template parts -> text/image segments. User/tool input parts never parse special tokens,
// so text read from a document or typed by the user cannot inject control tokens into the prompt.
std::vector<Segment> split_prompt(const common_chat_prompt & prompt, const std::string & marker, int & n_markers) {
    std::vector<Segment> out;
    n_markers = 0;
    for (const auto & part : prompt.parts) {
        size_t start = 0;
        while (true) {
            size_t pos = part.text.find(marker, start);
            std::string piece = part.text.substr(start, pos == std::string::npos ? std::string::npos : pos - start);
            if (!piece.empty()) out.push_back({piece, !part.is_input, -1});
            if (pos == std::string::npos) break;
            out.push_back({"", false, n_markers++});
            start = pos + marker.size();
        }
    }
    return out;
}

struct Tokenized {
    std::vector<Entry> entries;
    std::vector<const mtmd_input_chunk *> chunk_of; // per entry: image chunk, else nullptr
    mtmd::input_chunks chunks{mtmd_input_chunks_init()};
};

bool decode_tokens(EngineImpl & e, const std::vector<llama_token> & tokens, bool logits_last) {
    for (size_t off = 0; off < tokens.size(); off += e.n_batch) {
        const size_t n = std::min(tokens.size() - off, static_cast<size_t>(e.n_batch));
        e.batch->clear();
        for (size_t k = 0; k < n; k++) {
            const bool last = logits_last && off + k == tokens.size() - 1;
            e.batch->add(tokens[off + k], e.n_past + static_cast<llama_pos>(k), 0, last);
        }
        if (llama_process(e.ctx, LLAMA_PROCESS_TYPE_DECODE, e.batch->get()) != 0) return false;
        e.n_past += static_cast<llama_pos>(n);
    }
    return true;
}

void save_checkpoint(EngineImpl & e) {
    const size_t size = llama_state_seq_get_size_ext(e.ctx, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
    if (size == 0) return;
    Checkpoint ck;
    ck.n_entries = e.cached.size();
    ck.pos = e.n_past;
    ck.data.resize(size);
    if (llama_state_seq_get_data_ext(e.ctx, ck.data.data(), size, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY) != size) return;
    e.checkpoints.erase(std::remove_if(e.checkpoints.begin(), e.checkpoints.end(),
        [&](const Checkpoint & c) { return c.n_entries == ck.n_entries; }), e.checkpoints.end());
    while (e.checkpoints.size() >= MAX_CHECKPOINTS) {
        // Keep the earliest one: it marks the stable prefix (system prompt) that every request shares.
        auto anchor = std::min_element(e.checkpoints.begin(), e.checkpoints.end(),
            [](const Checkpoint & a, const Checkpoint & b) { return a.n_entries < b.n_entries; });
        e.checkpoints.erase(anchor == e.checkpoints.begin() ? e.checkpoints.begin() + 1 : e.checkpoints.begin());
    }
    e.checkpoints.push_back(std::move(ck));
}

// Keep the longest reusable prefix of the memory. Returns the number of entries kept.
size_t reuse_prefix(EngineImpl & e, const std::vector<Entry> & next, size_t & diverged_at) {
    size_t common = 0;
    while (common < e.cached.size() && common < next.size() && e.cached[common] == next[common]) common++;
    if (common >= next.size()) common = next.size() - 1; // always decode at least one token for logits
    diverged_at = common;
    // (an image is a single entry, so a prefix never ends inside an image)

    auto * mem = llama_get_memory(e.ctx);
    llama_pos pos = 0;
    for (size_t i = 0; i < common; i++) pos += e.cached[i].n_pos;

    if (common == e.cached.size()) return common; // pure extension, nothing to remove
    if (common > 0 && llama_memory_seq_rm(mem, 0, pos, -1)) {
        e.cached.resize(common);
        e.n_past = pos;
        e.checkpoints.erase(std::remove_if(e.checkpoints.begin(), e.checkpoints.end(),
            [&](const Checkpoint & c) { return c.n_entries > common; }), e.checkpoints.end());
        return common;
    }
    // Recurrent/hybrid memory cannot roll back: restore the newest checkpoint inside the prefix.
    for (auto it = e.checkpoints.rbegin(); it != e.checkpoints.rend(); ++it) {
        if (it->n_entries == 0 || it->n_entries > common) continue;
        if (llama_state_seq_set_data_ext(e.ctx, it->data.data(), it->data.size(), 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY) == 0) break;
        if (!llama_memory_seq_rm(mem, 0, it->pos, -1)) break;
        const size_t kept = it->n_entries;
        e.cached.resize(kept);
        e.n_past = it->pos;
        e.checkpoints.erase(std::remove_if(e.checkpoints.begin(), e.checkpoints.end(),
            [&](const Checkpoint & c) { return c.n_entries > kept; }), e.checkpoints.end());
        return kept;
    }
    e.reset_cache();
    return 0;
}

} // namespace

namespace khodroyar {

struct Engine : EngineImpl {};

const char * media_marker() { return mtmd_default_marker(); }

std::string init_backends(const std::string & native_lib_dir) {
    static std::once_flag once;
    std::call_once(once, [&] {
        llama_log_set(log_callback, nullptr);
        mtmd_helper_log_set(log_callback, nullptr);
        if (native_lib_dir.empty()) ggml_backend_load_all();
        else ggml_backend_load_all_from_path(native_lib_dir.c_str());
        llama_backend_init();
    });
    common_json out = common_json::object();
    common_json devices = common_json::array();
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        devices.push_back(std::string(ggml_backend_dev_name(dev)) + ": " + ggml_backend_dev_description(dev));
    }
    out["devices"] = devices;
    out["system_info"] = std::string(llama_print_system_info());
    {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        out["backend_log"] = g_backend_log;
    }
    out["media_marker"] = std::string(mtmd_default_marker());
    return out.dump_safe();
}

Engine * load(const std::string & model_path, int n_ctx, int n_threads, int n_batch, bool kv_q8) {
    try {
        auto e = std::make_unique<Engine>();
        e->model_path = model_path;
        e->n_batch = std::max(32, n_batch);
        e->n_threads = std::max(1, n_threads);

        llama_model_params mp = llama_model_default_params();
        mp.n_gpu_layers = 0;                 // CPU baseline
        // No mmap: on ARM CPUs with i8mm/dotprod nearly every weight is repacked into an anonymous CPU_REPACK
        // buffer, while a few small tensors scattered through the file keep the whole mapping resident, so mmap
        // would hold the weights twice (measured: 6.5 GB RSS vs ~3.9 GB). Plain reads keep one copy.
        mp.load_mode = LLAMA_LOAD_MODE_NONE;
        e->model = llama_model_load_from_file(e->model_path.c_str(), mp);
        if (!e->model) { LOGE("model load failed"); return nullptr; }

        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = static_cast<uint32_t>(n_ctx);
        cp.n_batch = static_cast<uint32_t>(e->n_batch);
        cp.n_ubatch = static_cast<uint32_t>(e->n_batch);
        cp.n_seq_max = 1;
        cp.n_threads = e->n_threads;
        cp.n_threads_batch = e->n_threads;
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
        if (kv_q8) { cp.type_k = GGML_TYPE_Q8_0; cp.type_v = GGML_TYPE_Q8_0; }
        cp.abort_callback = abort_callback;
        cp.abort_callback_data = static_cast<EngineImpl *>(e.get());
        e->ctx = llama_init_from_model(e->model, cp);
        if (!e->ctx) { LOGE("context init failed"); return nullptr; }

        e->vocab = llama_model_get_vocab(e->model);
        e->templates = common_chat_templates_init(e->model, "");
        if (!e->templates) { LOGE("model has no usable chat template"); return nullptr; }
        e->batch = std::make_unique<common_batch>(e->ctx);
        e->needs_checkpoints = llama_model_is_recurrent(e->model) || llama_model_is_hybrid(e->model);
        LOGI("loaded %s: ctx=%u threads=%d batch=%d kv=%s hybrid/recurrent=%d",
             e->model_path.c_str(), llama_n_ctx(e->ctx), e->n_threads, e->n_batch, kv_q8 ? "q8_0" : "f16",
             e->needs_checkpoints ? 1 : 0);
        return e.release();
    } catch (const std::exception & ex) {
        LOGE("load: %s", ex.what());
        return nullptr;
    }
}

bool attach_vision(Engine * e, const std::string & path, int image_max_tokens) {
    if (!e) return false;
    std::lock_guard<std::mutex> lock(e->mutex);
    if (e->vision && e->vision_path == path) return true;
    if (e->vision) { mtmd_free(e->vision); e->vision = nullptr; }
    try {
        mtmd_context_params vp = mtmd_context_params_default();
        vp.use_gpu = false;
        vp.print_timings = true;
        vp.n_threads = e->n_threads;
        vp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
        vp.warmup = false; // the first real image pays it; saves a full encode on every load
        if (image_max_tokens > 0) vp.image_max_tokens = image_max_tokens;
        {
            // models with non-causal attention over the image need the whole image in one ubatch
            auto usage = mtmd_get_memory_usage(path.c_str(), vp);
            const int n_ubatch = static_cast<int>(llama_n_ubatch(e->ctx));
            if (usage.use_non_causal && usage.image_max_tokens > n_ubatch) {
                vp.image_max_tokens = n_ubatch;
                vp.image_min_tokens = std::min(vp.image_min_tokens, n_ubatch);
            }
        }
        e->vision = mtmd_init_from_file(path.c_str(), e->model, vp);
        if (!e->vision || !mtmd_support_vision(e->vision)) {
            if (e->vision) mtmd_free(e->vision);
            e->vision = nullptr;
            LOGE("vision projector load failed");
            return false;
        }
        e->vision_path = path;
        return true;
    } catch (const std::exception & ex) {
        LOGE("attach_vision: %s", ex.what());
        return false;
    }
}

void detach_vision(Engine * e) {
    if (!e) return;
    std::lock_guard<std::mutex> lock(e->mutex);
    if (e->vision) { mtmd_free(e->vision); e->vision = nullptr; e->vision_path.clear(); }
}

void abort(Engine * e) { if (e) e->abort.store(true); }

void free(Engine * e) {
    if (!e) return;
    e->abort.store(true);
    { std::lock_guard<std::mutex> lock(e->mutex); }
    delete e;
}

std::string info(Engine * e) {
    common_json out = common_json::object();
    if (e) {
        std::lock_guard<std::mutex> lock(e->mutex);
        out["model_bytes"] = static_cast<int64_t>(llama_model_size(e->model));
        out["n_ctx"] = static_cast<int64_t>(llama_n_ctx(e->ctx));
        out["vision"] = e->vision != nullptr;
        out["hybrid"] = e->needs_checkpoints;
        char desc[256] = {0};
        llama_model_desc(e->model, desc, sizeof(desc));
        out["desc"] = std::string(desc);
    }
    return out.dump_safe();
}

std::string chat(Engine * e, const std::string & request_json, const std::vector<Image> & images,
                 const std::function<bool(const std::string &)> & on_content) {
    if (!e) return error_json("not_loaded", "engine not loaded");
    std::lock_guard<std::mutex> lock(e->mutex);
    e->abort.store(false);
    const auto t_start = clock_type::now();

    try {
        const common_json request = common_json::parse(request_json);

        common_chat_templates_inputs inputs;
        inputs.messages = common_chat_msgs_parse_oaicompat(request.at("messages"));
        if (request.contains("tools")) inputs.tools = common_chat_tools_parse_oaicompat(request.at("tools"));
        inputs.tool_choice = inputs.tools.empty() ? COMMON_CHAT_TOOL_CHOICE_NONE : COMMON_CHAT_TOOL_CHOICE_AUTO;
        inputs.parallel_tool_calls = !inputs.tools.empty();
        inputs.add_generation_prompt = true;
        inputs.use_jinja = true;
        inputs.enable_thinking = request.value("enable_thinking", false);
        inputs.reasoning_format = COMMON_REASONING_FORMAT_AUTO; // any <think> goes to reasoning, never to the reply

        common_chat_session session(e->templates.get(), e->vocab, inputs);

        // ---- images: real pixels -> mtmd bitmaps ----
        const int n_images = static_cast<int>(images.size());
        mtmd::bitmaps bitmaps;
        if (n_images > 0) {
            if (!e->vision) return error_json("vision_not_loaded", "vision projector is not loaded");
            for (const Image & img : images) {
                if (img.width <= 0 || img.height <= 0 ||
                    static_cast<int64_t>(img.width) * img.height * 3 != static_cast<int64_t>(img.rgb.size())) {
                    return error_json("bad_image", "image buffer size does not match RGB dimensions");
                }
                mtmd_bitmap * bmp = mtmd_bitmap_init(static_cast<uint32_t>(img.width), static_cast<uint32_t>(img.height), img.rgb.data());
                if (!bmp) return error_json("bad_image", "could not create bitmap");
                mtmd_bitmap_set_id(bmp, img.id.c_str());
                bitmaps.entries.emplace_back(bmp);
            }
        }

        // ---- tokenize (text + image chunks) ----
        const std::string marker = mtmd_default_marker();
        int n_markers = 0;
        std::vector<Segment> segments = split_prompt(session.prompt(), marker, n_markers);
        if (n_markers != n_images) {
            return (error_json("image_mismatch", "media markers do not match the attached images"));
        }

        Tokenized tok;
        if (e->vision) {
            std::vector<mtmd_input_text> texts;
            texts.reserve(segments.size());
            std::vector<mtmd_input_part> parts;
            parts.reserve(segments.size());
            auto bitmap_ptrs = bitmaps.c_ptr();
            for (const auto & s : segments) {
                if (s.image >= 0) {
                    parts.push_back({nullptr, bitmap_ptrs[s.image]});
                } else {
                    texts.push_back({s.text.data(), s.text.size(), false, s.parse_special});
                    parts.push_back({&texts.back(), nullptr});
                }
            }
            std::vector<const mtmd_input_part *> part_ptrs;
            for (const auto & p : parts) part_ptrs.push_back(&p);
            const int32_t rc = mtmd_tokenize_from_parts(e->vision, tok.chunks.ptr.get(), part_ptrs.data(), part_ptrs.size(), true);
            if (rc != 0) return (error_json("tokenize_failed", "mtmd tokenize failed: " + std::to_string(rc)));
            const size_t n_chunks = mtmd_input_chunks_size(tok.chunks.ptr.get());
            for (size_t i = 0; i < n_chunks; i++) {
                const mtmd_input_chunk * chunk = mtmd_input_chunks_get(tok.chunks.ptr.get(), i);
                if (mtmd_input_chunk_get_type(chunk) == MTMD_INPUT_CHUNK_TYPE_TEXT) {
                    size_t n = 0;
                    const llama_token * t = mtmd_input_chunk_get_tokens_text(chunk, &n);
                    for (size_t k = 0; k < n; k++) { tok.entries.push_back({t[k], "", 1}); tok.chunk_of.push_back(nullptr); }
                } else {
                    const char * cid = mtmd_input_chunk_get_id(chunk);
                    tok.entries.push_back({LLAMA_TOKEN_NULL, cid ? cid : "image", mtmd_input_chunk_get_n_pos(chunk)});
                    tok.chunk_of.push_back(chunk);
                }
            }
        } else {
            bool first = true;
            for (const auto & s : segments) {
                auto t = common_tokenize(e->vocab, s.text, first && llama_vocab_get_add_bos(e->vocab), s.parse_special);
                first = false;
                for (auto id : t) { tok.entries.push_back({id, "", 1}); tok.chunk_of.push_back(nullptr); }
            }
        }
        if (tok.entries.empty()) return (error_json("empty_prompt", "empty prompt"));
        llama_pos total_pos = 0;
        for (const auto & en : tok.entries) total_pos += en.n_pos;
        const int max_tokens = std::max(1, request.value("max_tokens", 512));
        if (total_pos + max_tokens > static_cast<llama_pos>(llama_n_ctx(e->ctx))) {
            return (error_json("context_full",
                "prompt (" + std::to_string(total_pos) + ") + reply exceeds context " + std::to_string(llama_n_ctx(e->ctx))));
        }

        // ---- prompt cache ----
        size_t diverged_at = 0;
        const size_t kept = reuse_prefix(*e, tok.entries, diverged_at);
        const size_t n_cached_tokens = kept;
        // Checkpoints (hybrid/recurrent memory can only roll back to a saved state):
        //  * near the end of this prompt, so the next agent step only pre-fills the new tool result;
        //  * where this prompt diverged from the previous one, when that point had to be recomputed. With
        //    progressive tool exposure the tool list (rendered right after the system prompt) changes between
        //    steps; this checkpoint lets the next change reuse the system prompt instead of starting over.
        std::vector<size_t> checkpoint_at;
        if (e->needs_checkpoints) {
            if (diverged_at > kept + CHECKPOINT_TAIL && diverged_at + CHECKPOINT_TAIL < tok.entries.size()) checkpoint_at.push_back(diverged_at);
            if (tok.entries.size() > CHECKPOINT_TAIL + 1) checkpoint_at.push_back(tok.entries.size() - CHECKPOINT_TAIL);
        }
        auto is_checkpoint = [&](size_t at) { return std::find(checkpoint_at.begin(), checkpoint_at.end(), at) != checkpoint_at.end(); };
        auto next_checkpoint = [&](size_t from) {
            for (size_t c : checkpoint_at) if (c > from) return c;
            return tok.entries.size();
        };

        // ---- prefill ----
        const auto t_prefill = clock_type::now();
        double image_ms = 0;
        int images_encoded = 0;
        size_t i = kept;
        while (i < tok.entries.size()) {
            if (e->abort.load()) { e->reset_cache(); return (error_json("aborted", "cancelled")); }
            if (const mtmd_input_chunk * chunk = tok.chunk_of[i]) {
                const auto t0 = clock_type::now();
                if (mtmd_encode_chunk(e->vision, chunk) != 0) { e->reset_cache(); return (error_json("image_encode_failed", "vision encoder failed")); }
                float * embd = mtmd_get_output_embd(e->vision);
                llama_pos new_n_past = e->n_past;
                if (mtmd_helper_decode_image_chunk(e->vision, e->ctx, chunk, embd, e->n_past, 0, e->n_batch, &new_n_past, nullptr, nullptr) != 0) {
                    e->reset_cache();
                    return (error_json("image_decode_failed", "image decode failed"));
                }
                image_ms += ms_since(t0);
                images_encoded++;
                Entry en = tok.entries[i];
                en.n_pos = new_n_past - e->n_past;
                e->n_past = new_n_past;
                e->cached.push_back(en);
                i++;
                if (is_checkpoint(i)) save_checkpoint(*e);
                continue;
            }
            size_t end = i;
            const size_t stop_at = next_checkpoint(i);
            while (end < stop_at && tok.chunk_of[end] == nullptr) end++;
            std::vector<llama_token> run;
            run.reserve(end - i);
            for (size_t k = i; k < end; k++) run.push_back(tok.entries[k].token);
            const bool logits_last = end == tok.entries.size();
            if (!decode_tokens(*e, run, logits_last)) {
                e->reset_cache();
                return (error_json(e->abort.load() ? "aborted" : "decode_failed", "prompt decode failed"));
            }
            for (size_t k = i; k < end; k++) e->cached.push_back(tok.entries[k]);
            i = end;
            if (is_checkpoint(i)) save_checkpoint(*e);
        }
        const double prefill_ms = ms_since(t_prefill);
        const size_t prefilled = tok.entries.size() - kept;

        // ---- generate ----
        common_params_sampling sp;
        sp.temp = static_cast<float>(request.value("temperature", 0.2));
        sp.top_k = request.value("top_k", 40);
        sp.top_p = static_cast<float>(request.value("top_p", 0.9));
        sp.seed = static_cast<uint32_t>(request.value("seed", 42));
        session.apply_sampling(sp);
        common_sampler_ptr sampler(common_sampler_init(e->model, sp));
        if (!sampler) return (error_json("sampler_failed", "sampler init failed (tool grammar)"));

        std::string stop_reason = "length";
        std::string raw;
        std::string last_sent;
        double ttft_ms = -1;
        int n_generated = 0;
        const auto t_decode = clock_type::now();
        const auto & stops = session.additional_stops();
        for (; n_generated < max_tokens; n_generated++) {
            if (e->abort.load()) { stop_reason = "abort"; break; }
            const llama_token id = common_sampler_sample(sampler.get(), e->ctx, -1);
            common_sampler_accept(sampler.get(), id, true);
            if (ttft_ms < 0) ttft_ms = ms_since(t_start);
            if (llama_vocab_is_eog(e->vocab, id)) { stop_reason = "stop"; break; }

            const std::string piece = common_token_to_piece(e->ctx, id, true);
            raw += piece;
            common_chat_input chunk;
            chunk.append(piece, id);
            const common_chat_msg & partial = session.feed(chunk);

            const size_t visible = utf8_complete_prefix(partial.content);
            if (visible > last_sent.size() || partial.content.compare(0, last_sent.size(), last_sent) != 0) {
                last_sent = partial.content.substr(0, visible);
                if (!on_content(last_sent)) { stop_reason = "abort"; break; }
            }

            bool stopped = false;
            for (const auto & s : stops) {
                if (!s.empty() && raw.size() >= s.size() && raw.compare(raw.size() - s.size(), s.size(), s) == 0) { stopped = true; break; }
            }

            e->batch->clear();
            e->batch->add(id, e->n_past, 0, true);
            if (llama_process(e->ctx, LLAMA_PROCESS_TYPE_DECODE, e->batch->get()) != 0) {
                e->reset_cache();
                return (error_json("decode_failed", "token decode failed"));
            }
            e->n_past++;
            e->cached.push_back({id, "", 1});
            if (stopped) { stop_reason = "stop"; n_generated++; break; }
        }
        const double decode_ms = ms_since(t_decode);
        const common_chat_msg & msg = session.finish();

        common_json out = common_json::object();
        out["ok"] = true;
        out["content"] = msg.content;
        out["reasoning"] = msg.reasoning_content;
        common_json calls = common_json::array();
        for (const auto & tc : msg.tool_calls) {
            common_json c = common_json::object();
            c["name"] = tc.name;
            c["arguments"] = tc.arguments;
            c["id"] = tc.id;
            calls.push_back(c);
        }
        out["tool_calls"] = calls;
        out["stop"] = stop_reason;
        common_json t = common_json::object();
        t["prompt_tokens"] = static_cast<int64_t>(tok.entries.size());
        t["cached_tokens"] = static_cast<int64_t>(n_cached_tokens);
        t["diverged_at"] = static_cast<int64_t>(diverged_at);
        t["prefilled_tokens"] = static_cast<int64_t>(prefilled);
        t["prefill_ms"] = prefill_ms;
        t["images"] = images_encoded;
        t["image_ms"] = image_ms;
        t["ttft_ms"] = ttft_ms;
        t["generated_tokens"] = n_generated;
        t["decode_ms"] = decode_ms;
        t["decode_tps"] = decode_ms > 0 ? n_generated * 1000.0 / decode_ms : 0.0;
        t["checkpoints"] = static_cast<int64_t>(e->checkpoints.size());
        size_t ck_bytes = 0;
        for (const auto & c : e->checkpoints) ck_bytes += c.data.size();
        t["checkpoint_bytes"] = static_cast<int64_t>(ck_bytes);
        t["context_bytes"] = static_cast<int64_t>(llama_state_seq_get_size_ext(e->ctx, 0, LLAMA_STATE_SEQ_FLAGS_NONE));
        out["timings"] = t;
        return out.dump_safe();
    } catch (const std::exception & ex) {
        LOGE("chat: %s", ex.what());
        e->reset_cache();
        return (error_json("exception", ex.what()));
    }
}

} // namespace khodroyar
