package com.khodroyar.app.ai.gemma

import org.json.JSONArray

/**
 * Static description of the on-device model. This class is deliberately pure JVM (no
 * Android types) so the download/verification logic around it is unit-testable.
 *
 * Provenance of the pinned values (checked on 2026-11-16):
 *  - Google AI Edge Gallery model allowlist `model_allowlists/1_0_11.json`
 *    (github.com/google-ai-edge/gallery), entry `Gemma-4-E2B-it`:
 *      modelId   = litert-community/gemma-4-E2B-it-litert-lm
 *      modelFile = gemma-4-E2B-it.litertlm
 *      sizeInBytes = 2,583,085,056
 *      minDeviceMemoryInGb = 8
 *      commitHash  = 7fa1d78473894f7e736a21d920c3aa80f950c0db
 *      llmSupportImage/Audio/Thinking = true, up to 32K context, LiteRT-LM runtime.
 *  - Hugging Face `resolve` URL pattern (same as the Gallery app uses):
 *      https://huggingface.co/<repo>/resolve/<commit>/<file>
 *  - Gemma 4 is published under the Apache 2.0 licence, so it is legally
 *    redistributable and the Hugging Face repo is publicly downloadable (no token).
 *
 * The SHA-256 is NOT hard-pinned here on purpose: the authoritative digest is fetched
 * over HTTPS from the Hugging Face metadata API of the same official repository at
 * download time and compared with the streamed digest of the received bytes. The
 * commit and size above ARE hard-pinned, so a swapped file fails both size and digest
 * checks. A future app update can ship a hard pin via [pinnedSha256].
 */
data class GemmaModelSpec(
    val displayName: String,
    val hfRepoId: String,
    val modelFileName: String,
    val expectedSizeBytes: Long,
    val pinnedCommit: String,
    val pinnedSha256: String = "",
    val minDeviceRamBytes: Long = 8L * 1024 * 1024 * 1024,
    /** Extra free space required on top of the final file (partial + final coexist briefly). */
    val minFreeStorageBytes: Long = expectedSizeBytes + 900L * 1024 * 1024,
) {
    /** The exact file URL, pinned to an immutable commit (never a moving branch). */
    val resolveUrl: String
        get() = "https://huggingface.co/$hfRepoId/resolve/$pinnedCommit/$modelFileName"

    /** Hugging Face metadata for the SAME immutable revision as the model download URL. */
    val metadataUrl: String
        get() = "https://huggingface.co/api/models/$hfRepoId/tree/$pinnedCommit"

    val displaySizeMb: Int get() = (expectedSizeBytes / (1024 * 1024)).toInt()

    companion object {
        /** The single model this app ships support for. */
        /**
         * انتخاب مختص این نسخهٔ اپ: کوچک‌ترین و جدیدترین مدل رسمیِ LiteRT-LM با Vision که
         * حداقل رسمی RAM آن (۸GB در allowlist گوگل) دقیقاً برابر RAM گوشی هدف S23 FE است و
         * هیچ مدلِ واجد vision نمی‌تواند الزامی کمتر داشته باشد. E4B = ۱۲GB؛ 3n-E2B بزرگ‌تر و
         * قدیمی‌تر؛ مدل‌های ۶GB-RAM بدون vision هستند.
         */
        val GEMMA_4_E2B = GemmaModelSpec(
            displayName = "Gemma 4 E2B",
            hfRepoId = "litert-community/gemma-4-E2B-it-litert-lm",
            modelFileName = "gemma-4-E2B-it.litertlm",
            expectedSizeBytes = 2_583_085_056L,
            pinnedCommit = "7fa1d78473894f7e736a21d920c3aa80f950c0db",
        )

        /**
         * Reads the expected SHA-256 of [GemmaModelSpec.modelFileName] from the Hugging
         * Face metadata JSON for the exact pinned commit. Returns null when the file or
         * its LFS digest is absent — callers treat that as "size-only verification", never silently.
         */
        fun sha256FromMetadata(jsonText: String, fileName: String): String? {
            val arr = try {
                JSONArray(jsonText)
            } catch (_: Exception) {
                return null
            }
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                if (entry.optString("path") != fileName) continue
                val lfs = entry.optJSONObject("lfs") ?: return null
                val sha = lfs.optString("oid", "")
                return if (sha.matches(Regex("^[0-9a-f]{64}$"))) sha else null
            }
            return null
        }
    }
}
