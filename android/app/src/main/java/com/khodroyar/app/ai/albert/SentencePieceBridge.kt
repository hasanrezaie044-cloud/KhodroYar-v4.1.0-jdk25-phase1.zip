package com.khodroyar.app.ai.albert

/** Official pinned SentencePiece processor, not a hand-written approximate tokenizer. */
internal class SentencePieceBridge(model: ByteArray) : AutoCloseable {
    companion object { init { System.loadLibrary("khodroyar_tokenizer") } }
    private var handle = createNative(model)
    fun encode(normalized: String): IntArray {
        check(handle != 0L)
        // Standard UTF-8, not JNI modified UTF-8 (important for supplementary characters).
        return encodeNative(handle, normalized.toByteArray(Charsets.UTF_8))
    }
    override fun close() {
        if (handle != 0L) { destroyNative(handle); handle = 0 }
    }
    private external fun createNative(model: ByteArray): Long
    private external fun encodeNative(handle: Long, utf8: ByteArray): IntArray
    private external fun destroyNative(handle: Long)
}
