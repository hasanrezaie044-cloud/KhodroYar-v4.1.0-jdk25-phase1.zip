package com.khodroyar.app.ai

/** Shared data hygiene, independent of any provider's HTTP or model format. */
object AiTextSafety {
    fun containsCredential(text: String): Boolean = Regex("(?i)(?:sk-(?:or-|[a-z0-9]{16})|AIza[A-Za-z0-9_-]{20,}|gsk_[A-Za-z0-9_-]{20,}|hf_[A-Za-z0-9_-]{20,})").containsMatchIn(text)
    fun redact(text: String): String = Regex("(?i)(?:sk-(?:or-[A-Za-z0-9_-]*|[A-Za-z0-9_-]{16,})|AIza[A-Za-z0-9_-]{20,}|gsk_[A-Za-z0-9_-]{20,}|hf_[A-Za-z0-9_-]{20,})").replace(text, "[کلید پنهان شد]")
}
