package com.khodroyar.app.ai.gemma

import org.json.JSONObject
import java.io.File

/**
 * Where on disk the model lives and how its state is derived. Everything under
 * [rootDir] is the app's private storage (passed in by the Android side), so no
 * storage permission is involved and the files survive app updates but not uninstall.
 *
 * Layout:
 *   <rootDir>/gemma-4-E2B-it.litertlm        installed model (only exists after verification)
 *   <rootDir>/gemma-4-E2B-it.litertlm.part   resumable partial download (deleted on success or reset)
 *   <rootDir>/gemma-4-E2B-it.meta.json       download metadata (expected size + sha256)
 */
class ModelStore(val rootDir: File, private val spec: GemmaModelSpec = GemmaModelSpec.GEMMA_4_E2B) {

    val partialFile: File get() = File(rootDir, "${spec.modelFileName}.part")
    val modelFile: File get() = File(rootDir, spec.modelFileName)
    private val metaFile: File get() = File(rootDir, "${spec.modelFileName.removeSuffix(".litertlm")}.meta.json")

    /** What is persisted next to a partial download so a later session can resume safely. */
    data class Meta(
        val url: String,
        val expectedSize: Long,
        val expectedSha256: String,
    ) {
        fun toJson(): String = JSONObject()
            .put("url", url)
            .put("expectedSize", expectedSize)
            .put("expectedSha256", expectedSha256)
            .toString()

        companion object {
            fun parse(text: String): Meta? = try {
                val o = JSONObject(text)
                Meta(
                    url = o.optString("url", ""),
                    expectedSize = o.optLong("expectedSize", -1),
                    expectedSha256 = o.optString("expectedSha256", ""),
                ).takeIf { it.expectedSize > 0 }
            } catch (_: Exception) {
                null
            }
        }
    }

    sealed interface InstallState {
        data object NotInstalled : InstallState
        /** A partial file exists that can be resumed. */
        data class Interrupted(val downloadedBytes: Long) : InstallState
        /** Final file present with the exact expected size. */
        data class Ready(val sizeBytes: Long, val file: File) : InstallState
        /** Final file present but its size contradicts the pinned model — never load it. */
        data class Corrupt(val actualBytes: Long) : InstallState
    }

    fun state(): InstallState {
        val f = modelFile
        if (f.isFile) {
            return when {
                f.length() == spec.expectedSizeBytes -> InstallState.Ready(f.length(), f)
                else -> InstallState.Corrupt(f.length())
            }
        }
        val p = partialFile
        return if (p.isFile && p.length() > 0) InstallState.Interrupted(p.length()) else InstallState.NotInstalled
    }

    fun readMeta(): Meta? = if (metaFile.isFile) runCatching { Meta.parse(metaFile.readText(Charsets.UTF_8)) }.getOrNull() else null

    fun writeMeta(meta: Meta) {
        rootDir.mkdirs()
        val tmp = File(rootDir, metaFile.name + ".tmp")
        tmp.writeText(meta.toJson(), Charsets.UTF_8)
        if (!tmp.renameTo(metaFile)) {
            metaFile.writeText(meta.toJson(), Charsets.UTF_8)
            tmp.delete()
        }
    }

    /** Called only after verification succeeded; moves the partial into its final place atomically. */
    fun promotePartial(): Boolean {
        val ok = partialFile.renameTo(modelFile)
        if (ok) runCatching { metaFile.delete() }
        return ok
    }

    /** Removes EVERYTHING (model, partial, meta) — the user's "delete model and reclaim storage". */
    fun deleteAll(): Long {
        var freed = 0L
        for (f in listOf(modelFile, partialFile, metaFile)) {
            if (f.isFile) {
                freed += f.length()
                if (!f.delete()) freed -= f.length()
            }
        }
        return freed
    }

    /** Drops a failed resume (corrupt partial or changed expected size) but keeps meta free to be re-written. */
    fun resetPartial() {
        runCatching { partialFile.delete() }
        runCatching { metaFile.delete() }
    }
}
