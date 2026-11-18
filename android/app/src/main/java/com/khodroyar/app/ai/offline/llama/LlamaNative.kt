package com.khodroyar.app.ai.offline.llama

/**
 * JNI surface of `libkhodroyar_llama.so` (src/main/cpp): official llama.cpp v1.0.0 — libllama for the
 * language model, libmtmd for the vision projector, and llama-common's jinja chat templates + native
 * tool-call parser. CPU only. All calls except [abort] are serialized per handle on the native side.
 */
internal object LlamaNative {
    /** Streams the visible reply so far; return false to stop generation (e.g. app output limit). */
    fun interface Listener { fun onContent(text: String): Boolean }

    /** Loads the ggml CPU backend variant that matches this CPU from [nativeLibDir]; returns diagnostics JSON. */
    external fun initBackends(nativeLibDir: String): String

    /** Returns 0 when the model or context cannot be created. */
    external fun load(modelPath: String, nCtx: Int, nThreads: Int, nBatch: Int, kvQ8: Boolean): Long

    external fun attachVision(handle: Long, mmprojPath: String, imageMaxTokens: Int): Boolean
    external fun detachVision(handle: Long)

    /**
     * [requestJson]: OpenAI-style `{messages, tools, max_tokens, temperature, top_k, top_p}`; each image
     * position is a `{"type":"media_marker","text":<marker>}` content part. [images] are RGB888 pixel
     * buffers in transcript order with their SHA-256 [ids]. Returns the result JSON (see khodroyar_llama.cpp).
     */
    external fun chat(
        handle: Long,
        requestJson: String,
        images: Array<ByteArray>,
        widths: IntArray,
        heights: IntArray,
        ids: Array<String>,
        listener: Listener,
    ): String

    external fun abort(handle: Long)
    external fun free(handle: Long)
    external fun info(handle: Long): String
}
