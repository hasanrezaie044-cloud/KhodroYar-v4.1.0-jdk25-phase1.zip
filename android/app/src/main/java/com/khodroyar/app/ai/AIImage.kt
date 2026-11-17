package com.khodroyar.app.ai

/** Ephemeral wire data. Never written to diagnostics, preferences or chat JSON. */
data class AIImage(val mimeType: String, val base64: String) {
    init {
        require(mimeType in setOf("image/jpeg", "image/png", "image/webp"))
        require(base64.isNotBlank() && base64.length <= 24 * 1024 * 1024 && Regex("[A-Za-z0-9+/=\\r\\n]+").matches(base64))
    }
}

/** A reference to a private, re-encoded local image; contains no grant URI or API credential. */
data class ChatImageRef(val fileName: String, val displayName: String = "تصویر") {
    init { require(Regex("[A-Za-z0-9_-]+\\.jpg").matches(fileName)) }
}
