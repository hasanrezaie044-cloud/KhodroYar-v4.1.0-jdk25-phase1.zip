package com.khodroyar.app.ai.gemma

import java.io.File
import java.security.MessageDigest

/**
 * Downloaded-model integrity checks. Pure JVM — the unit tests exercise this directly.
 *
 * Two independent gates:
 *  1. exact byte size (pinned from Google's AI Edge Gallery allowlist);
 *  2. SHA-256 against the digest published by the official Hugging Face repo metadata
 *     (or the harder [pinnedSha256] if a future app build ships one).
 *
 * Gate 2 is the *trusted checksum*: it protects against truncated, corrupted or swapped
 * files. When no digest could be obtained we say so ([Verification.digestTrusted] = false)
 * and the caller must NOT claim cryptographic verification happened.
 */
object ModelVerifier {

    data class Verification(
        val sizeOk: Boolean,
        val actualSize: Long,
        val digestHex: String,
        val digestOk: Boolean,
        /** false = no trusted digest was available; only size was checked. */
        val digestTrusted: Boolean,
    ) {
        val ok: Boolean get() = sizeOk && digestOk
    }

    fun verify(file: File, expectedSize: Long, expectedSha256: String): Verification {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val buf = ByteArray(1 shl 20)
        file.inputStream().buffered().use { ins ->
            while (true) {
                val read = ins.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
                total += read
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        val expected = expectedSha256.trim().lowercase()
        val trusted = expected.matches(Regex("^[0-9a-f]{64}$"))
        return Verification(
            sizeOk = total == expectedSize,
            actualSize = total,
            digestHex = hex,
            digestOk = !trusted || hex == expected,
            digestTrusted = trusted,
        )
    }
}
