package com.khodroyar.app.ai.offline

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.max

/**
 * How strongly an installed model file was checked.
 *  - PINNED: SHA-256 equals the checksum compiled into the app.
 *  - PUBLISHED: SHA-256 equals the LFS checksum Hugging Face publishes for the file.
 *  - UNVERIFIED: the checksum list was unreachable; only the exact transfer size was checked.
 */
enum class ModelTrust { PINNED, PUBLISHED, UNVERIFIED }

/** Model package description. The package is never bundled in the APK; it is downloaded on demand. */
data class OfflineModelSpec(
    val id: String,
    val title: String,
    val repository: String,
    val fileName: String,
    /** `main` or an immutable 40-hex commit. */
    val revision: String,
    /** Size published for the current file; used for the storage preflight and UI before metadata arrives. */
    val approxBytes: Long,
    val minBytes: Long,
    val maxBytes: Long,
    val recommendedRamBytes: Long,
    val advertisedImageInput: Boolean,
    val advertisedToolUse: Boolean,
    /** Optional pinned checksum. When null the Hugging Face published checksum is used (with a user warning). */
    val expectedSha256: String? = null,
) {
    init {
        require(id.matches(Regex("[a-z0-9._-]{2,80}")))
        require(repository.matches(Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")) && repository.split('/').none { it == "." || it == ".." })
        require(fileName.matches(Regex("[A-Za-z0-9._-]{1,180}")) && fileName != "." && fileName != ".." && ".." !in fileName)
        require(revision == "main" || revision.matches(Regex("[0-9a-f]{40}")))
        require(approxBytes in minBytes..maxBytes && minBytes > 0 && recommendedRamBytes > 0)
        require(expectedSha256 == null || expectedSha256.matches(SHA256))
    }

    val downloadUrl: String get() = "https://huggingface.co/$repository/resolve/$revision/$fileName"
    val metadataUrl: String get() = "https://huggingface.co/api/models/$repository/tree/$revision"
    val pageUrl: String get() = "https://huggingface.co/$repository"

    companion object {
        internal val SHA256 = Regex("[0-9a-f]{64}")

        /** litert-community/gemma-4-E2B-it-litert-lm — text + image input, native tool calling. */
        val GEMMA_4_E2B = OfflineModelSpec(
            id = "gemma-4-e2b-it",
            title = "Gemma 4 E2B",
            repository = "litert-community/gemma-4-E2B-it-litert-lm",
            fileName = "gemma-4-E2B-it.litertlm",
            revision = "main",
            approxBytes = 2_588_147_712L,
            minBytes = 1_000_000_000L,
            maxBytes = 4_500_000_000L,
            recommendedRamBytes = 8L * 1024 * 1024 * 1024,
            advertisedImageInput = true,
            advertisedToolUse = true,
        )
    }
}

/** Expected (partial download) or actual (installed) artifact facts. */
data class ModelArtifact(val revision: String, val bytes: Long?, val sha256: String?, val trust: ModelTrust) {
    init {
        require(revision.isNotBlank() && revision.length <= 64)
        require(bytes == null || bytes > 0)
        require(sha256 == null || sha256.matches(OfflineModelSpec.SHA256))
        require(trust == ModelTrust.UNVERIFIED || sha256 != null)
    }

    fun toJson(): String = JSONObject()
        .put("revision", revision)
        .put("bytes", bytes ?: JSONObject.NULL)
        .put("sha256", sha256 ?: JSONObject.NULL)
        .put("trust", trust.name)
        .toString()

    companion object {
        fun fromJson(text: String): ModelArtifact? = runCatching {
            val json = JSONObject(text)
            ModelArtifact(
                json.getString("revision"),
                if (json.isNull("bytes")) null else json.getLong("bytes"),
                if (json.isNull("sha256")) null else json.getString("sha256"),
                ModelTrust.valueOf(json.getString("trust")),
            )
        }.getOrNull()
    }
}

/** Reads the exact file's LFS size + SHA-256 from the Hugging Face tree API. */
object OfflineModelMetadataParser {
    fun parse(jsonText: String, spec: OfflineModelSpec): ModelArtifact? = runCatching {
        val entries = JSONArray(jsonText)
        val entry = (0 until entries.length())
            .mapNotNull(entries::optJSONObject)
            .firstOrNull { it.optString("path") == spec.fileName }
            ?: return null
        val lfs = entry.optJSONObject("lfs") ?: return null
        val size = lfs.optLong("size", entry.optLong("size", -1L))
        val sha = lfs.optString("oid", "").lowercase(Locale.ROOT).removePrefix("sha256:")
        if (size !in spec.minBytes..spec.maxBytes || !sha.matches(OfflineModelSpec.SHA256)) return null
        ModelArtifact(spec.revision, size, sha, if (spec.expectedSha256 != null) ModelTrust.PINNED else ModelTrust.PUBLISHED)
    }.getOrNull()
}

/** App-private, no-backup model storage; no shared-storage permission is needed. */
class OfflineModelStore(val root: File, val spec: OfflineModelSpec = OfflineModelSpec.GEMMA_4_E2B) {
    val modelFile: File get() = File(root, spec.fileName)
    val partialFile: File get() = File(root, "${spec.fileName}.part")
    private val installedFile: File get() = File(root, "${spec.fileName}.installed.json")
    private val partialMetaFile: File get() = File(root, "${spec.fileName}.download.json")

    sealed interface State {
        data object Missing : State
        data class Interrupted(val bytes: Long, val total: Long?) : State
        data class Present(val bytes: Long, val sha256: String, val trust: ModelTrust) : State
        data class Invalid(val bytes: Long, val reason: String) : State
    }

    fun state(): State {
        if (modelFile.isFile) {
            val installed = readInstalledMetadata()
            val length = modelFile.length()
            return when {
                installed?.sha256 == null || installed.bytes == null -> State.Invalid(length, "اطلاعات نصب مدل موجود نیست")
                installed.bytes != length -> State.Invalid(length, "اندازهٔ فایل مدل تغییر کرده است")
                spec.expectedSha256 != null && installed.sha256 != spec.expectedSha256 -> State.Invalid(length, "اثر انگشت فایل با نسخهٔ تأییدشده یکی نیست")
                else -> State.Present(length, installed.sha256, installed.trust)
            }
        }
        val partial = partialFile
        return if (partial.isFile && partial.length() > 0L) State.Interrupted(partial.length(), readPartialMetadata()?.bytes) else State.Missing
    }

    fun readInstalledMetadata(): ModelArtifact? =
        if (installedFile.isFile) runCatching { ModelArtifact.fromJson(installedFile.readText(Charsets.UTF_8)) }.getOrNull() else null

    fun readPartialMetadata(): ModelArtifact? =
        if (partialMetaFile.isFile) runCatching { ModelArtifact.fromJson(partialMetaFile.readText(Charsets.UTF_8)) }.getOrNull() else null

    fun writePartialMetadata(value: ModelArtifact): Boolean = writeAtomically(partialMetaFile, value.toJson())

    /**
     * Cheap pre-load gate: installed marker, exact size and (if pinned) the pinned digest. The full
     * SHA-256 is computed once at install; [deep] re-hashes every byte (slow for a 2.5 GB file).
     */
    fun verifyInstalled(deep: Boolean = false): Boolean {
        val present = state() as? State.Present ?: return false
        if (!deep) return true
        return runCatching { sha256(modelFile) == present.sha256 }.getOrDefault(false)
    }

    /** Atomically move a fully hashed partial file into place. */
    fun promote(installed: ModelArtifact): Boolean {
        val bytes = installed.bytes ?: return false
        if (installed.sha256 == null || !partialFile.isFile || partialFile.length() != bytes) return false
        if (spec.expectedSha256 != null && installed.sha256 != spec.expectedSha256) return false
        root.mkdirs()
        val backup = File(root, "${spec.fileName}.previous")
        if (backup.exists() && !backup.delete()) return false
        val hadPrevious = modelFile.exists()
        if (hadPrevious && !modelFile.renameTo(backup)) return false
        if (!partialFile.renameTo(modelFile)) {
            if (hadPrevious) backup.renameTo(modelFile)
            return false
        }
        if (!writeAtomically(installedFile, installed.toJson())) {
            modelFile.delete()
            if (hadPrevious) backup.renameTo(modelFile)
            return false
        }
        if (hadPrevious) backup.delete()
        partialMetaFile.delete()
        return true
    }

    fun removeInterruptedDownload(): Boolean {
        val partialOk = !partialFile.exists() || partialFile.delete()
        val metaOk = !partialMetaFile.exists() || partialMetaFile.delete()
        return partialOk && metaOk
    }

    /** Remove the model, partial download and every marker (including the earlier `.verified.json`). */
    fun deleteAll(): Long {
        var bytes = 0L
        listOf(
            modelFile, partialFile, installedFile, partialMetaFile,
            File(root, "${spec.fileName}.previous"), File(root, "${spec.fileName}.verified.json"),
            File(root, "${installedFile.name}.tmp"), File(root, "${installedFile.name}.previous"),
            File(root, "${partialMetaFile.name}.tmp"), File(root, "${partialMetaFile.name}.previous"),
        ).forEach {
            val length = if (it.isFile) it.length() else 0L
            if (it.exists() && it.delete()) bytes += length
        }
        return bytes
    }

    private fun writeAtomically(target: File, text: String): Boolean = try {
        root.mkdirs()
        val tmp = File(root, target.name + ".tmp")
        val backup = File(root, target.name + ".previous")
        if (!target.exists() && backup.isFile && !backup.renameTo(target)) { tmp.delete(); return false }
        java.io.FileOutputStream(tmp).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (backup.exists() && !backup.delete()) { tmp.delete(); return false }
        val hadTarget = target.exists()
        if (hadTarget && !target.renameTo(backup)) { tmp.delete(); return false }
        if (!tmp.renameTo(target)) {
            if (hadTarget) backup.renameTo(target)
            tmp.delete()
            return false
        }
        if (hadTarget) backup.delete()
        true
    } catch (_: Exception) {
        false
    }

    companion object {
        /** Returns null when [shouldStop] interrupts hashing. */
        fun sha256(file: File, shouldStop: () -> Boolean = { false }, onBytes: (Long) -> Unit = {}): String? {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1024 * 1024)
            var total = 0L
            file.inputStream().buffered(1024 * 1024).use { input ->
                while (true) {
                    if (shouldStop()) return null
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) { digest.update(buffer, 0, count); total += count; onBytes(total) }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun sha256(file: File): String = requireNotNull(sha256(file, { false }))
    }
}

/** Resumable, user-triggered downloader. Call only from an explicit UI action on an I/O thread. */
class OfflineModelDownloader(
    private val store: OfflineModelStore,
    private val transport: Transport = HttpsTransport(),
    private val freeBytes: () -> Long,
) {
    interface Connection : AutoCloseable {
        val statusCode: Int
        val contentLength: Long?
        val contentRange: String?
        val body: InputStream
    }

    interface Transport {
        fun metadata(url: String): String
        fun open(url: String, offset: Long): Connection
    }

    data class Progress(
        val downloaded: Long,
        val total: Long,
        val bytesPerSecond: Long,
        val remainingSeconds: Long?,
        val verifying: Boolean = false,
    ) {
        val percent: Int get() = ((downloaded * 100L) / total.coerceAtLeast(1L)).toInt().coerceIn(0, 100)
    }

    sealed interface Result {
        data class Installed(val bytes: Long, val sha256: String, val trust: ModelTrust) : Result
        data class Paused(val downloaded: Long, val total: Long?) : Result
        data object Cancelled : Result
        data object Busy : Result
        data class Failed(val reason: Reason) : Result
    }

    sealed interface Reason {
        data class Storage(val required: Long, val available: Long) : Reason
        data object MetadataInvalid : Reason
        data object InvalidResponse : Reason
        data class AccessDenied(val httpStatus: Int) : Reason
        data class HttpError(val httpStatus: Int) : Reason
        data object Incomplete : Reason
        data object ChecksumMismatch : Reason
        data object PromoteFailed : Reason
        data class Network(val safeMessage: String) : Reason
    }

    private enum class Request { NONE, PAUSE, CANCEL }
    private val request = AtomicReference(Request.NONE)

    fun pause() { request.compareAndSet(Request.NONE, Request.PAUSE) }
    fun cancel() { request.set(Request.CANCEL) }

    fun download(onProgress: (Progress) -> Unit = {}): Result {
        val lock = Locks.forKey(store.root.absolutePath + "/" + store.spec.id)
        if (!lock.tryLock()) return Result.Busy
        try {
            val spec = store.spec
            store.root.mkdirs()
            (store.state() as? OfflineModelStore.State.Present)?.let { return Result.Installed(it.bytes, it.sha256, it.trust) }
            stopRequested()?.let { return it }

            // 1) Published facts. Unreachable metadata is not fatal unless a checksum is pinned and conflicts.
            val metadataText = runCatching { transport.metadata(spec.metadataUrl) }.getOrNull()
            val published = metadataText?.let { OfflineModelMetadataParser.parse(it, spec) }
            if (spec.expectedSha256 != null && published != null && published.sha256 != spec.expectedSha256) {
                return Result.Failed(Reason.ChecksumMismatch)
            }
            val fresh = published
                ?: ModelArtifact(spec.revision, null, spec.expectedSha256, if (spec.expectedSha256 != null) ModelTrust.PINNED else ModelTrust.UNVERIFIED)

            // 2) Reconcile with an earlier partial download (never mix bytes of two different files).
            val old = store.readPartialMetadata()
            val partialExists = store.partialFile.isFile && store.partialFile.length() > 0L
            val compatible = old != null &&
                (fresh.sha256 == null || old.sha256 == null || fresh.sha256 == old.sha256) &&
                (fresh.bytes == null || old.bytes == null || fresh.bytes == old.bytes)
            if (partialExists && !compatible && !store.removeInterruptedDownload()) return Result.Failed(Reason.InvalidResponse)
            val target = if (compatible && old != null) ModelArtifact(
                spec.revision,
                fresh.bytes ?: old.bytes,
                fresh.sha256 ?: old.sha256,
                if (fresh.sha256 != null) fresh.trust else old.trust,
            ) else fresh
            if (target.bytes != null && store.partialFile.length() > target.bytes && !store.removeInterruptedDownload()) {
                return Result.Failed(Reason.InvalidResponse)
            }
            if (!store.writePartialMetadata(target)) return Result.Failed(Reason.InvalidResponse)

            // 3) Storage preflight.
            val needed = max(0L, (target.bytes ?: spec.approxBytes) - store.partialFile.length()) + STORAGE_RESERVE_BYTES
            val available = freeBytes()
            if (available in 0 until needed) return Result.Failed(Reason.Storage(needed, available))

            return fetch(target, onProgress)
        } finally {
            request.set(Request.NONE)
            lock.unlock()
        }
    }

    private fun stopRequested(): Result? = when (request.get()) {
        Request.PAUSE -> Result.Paused(store.partialFile.length(), store.readPartialMetadata()?.bytes)
        Request.CANCEL -> { store.removeInterruptedDownload(); Result.Cancelled }
        else -> null
    }

    private fun fetch(target: ModelArtifact, onProgress: (Progress) -> Unit): Result {
        val spec = store.spec
        val partial = store.partialFile
        var offset = if (partial.isFile) partial.length() else 0L
        val connection = try { transport.open(spec.downloadUrl, offset) }
        catch (_: Exception) { return Result.Failed(Reason.Network("اتصال به Hugging Face برقرار نشد؛ بخش دانلودشده برای ادامه نگه داشته شد.")) }
        val total: Long
        connection.use { response ->
            when (response.statusCode) {
                HttpURLConnection.HTTP_OK -> {
                    if (offset > 0L) {
                        // Range unsupported: restart from zero; never append a full body to a partial file.
                        if (!partial.delete()) return Result.Failed(Reason.InvalidResponse)
                        offset = 0L
                    }
                    val length = response.contentLength
                    if (target.bytes != null && length != null && length != target.bytes) return Result.Failed(Reason.InvalidResponse)
                    total = target.bytes ?: length ?: return Result.Failed(Reason.InvalidResponse)
                }
                HttpURLConnection.HTTP_PARTIAL -> {
                    val range = parseContentRange(response.contentRange) ?: return Result.Failed(Reason.InvalidResponse)
                    if (range.first != offset || (target.bytes != null && range.third != target.bytes) || range.second !in offset until range.third) {
                        return Result.Failed(Reason.InvalidResponse)
                    }
                    total = range.third
                }
                416 -> {
                    val reported = UNSATISFIED_RANGE.matchEntire(response.contentRange.orEmpty())?.groupValues?.get(1)?.toLongOrNull()
                    if (reported == null || offset != reported || (target.bytes != null && reported != target.bytes)) {
                        store.removeInterruptedDownload()
                        return Result.Failed(Reason.InvalidResponse)
                    }
                    return verifyAndPromote(target.copy(bytes = reported), onProgress)
                }
                401, 403 -> return Result.Failed(Reason.AccessDenied(response.statusCode))
                else -> return Result.Failed(Reason.HttpError(response.statusCode))
            }
            if (total !in spec.minBytes..spec.maxBytes) return Result.Failed(Reason.InvalidResponse)
            if (target.bytes == null && !store.writePartialMetadata(target.copy(bytes = total))) return Result.Failed(Reason.InvalidResponse)

            val startBytes = offset
            val startedNanos = System.nanoTime()
            var lastReportNanos = 0L
            var downloaded = offset
            onProgress(Progress(downloaded, total, 0L, null))
            val random = try { java.io.RandomAccessFile(partial, "rw") }
            catch (_: Exception) { return Result.Failed(Reason.Storage(total, freeBytes())) }
            random.use { output ->
                output.setLength(offset)
                output.seek(offset)
                val buffer = ByteArray(CHUNK_BYTES)
                try {
                    response.body.use { input ->
                        while (true) {
                            when (request.get()) {
                                Request.PAUSE -> { output.fd.sync(); return Result.Paused(downloaded, total) }
                                Request.CANCEL -> { runCatching { output.close() }; store.removeInterruptedDownload(); return Result.Cancelled }
                                Request.NONE -> Unit
                            }
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (downloaded + count > total) return Result.Failed(Reason.InvalidResponse)
                            output.write(buffer, 0, count)
                            downloaded += count
                            val now = System.nanoTime()
                            if (now - lastReportNanos >= PROGRESS_INTERVAL_NANOS) {
                                lastReportNanos = now
                                val elapsedMs = max(1L, (now - startedNanos) / 1_000_000L)
                                val speed = (downloaded - startBytes) * 1000L / elapsedMs
                                val left = (total - downloaded).coerceAtLeast(0L)
                                onProgress(Progress(downloaded, total, speed, if (speed > 0) left / speed else null))
                            }
                        }
                    }
                    output.fd.sync()
                } catch (_: Exception) {
                    runCatching { output.fd.sync() }
                    return Result.Failed(Reason.Network("دانلود قطع شد؛ بخش دانلودشده نگه داشته شد و می‌توانید ادامه دهید."))
                }
            }
            if (downloaded != total) return Result.Failed(Reason.Incomplete)
        }
        return verifyAndPromote(target.copy(bytes = total), onProgress)
    }

    private fun verifyAndPromote(target: ModelArtifact, onProgress: (Progress) -> Unit): Result {
        val file = store.partialFile
        val total = target.bytes ?: return Result.Failed(Reason.Incomplete)
        if (!file.isFile || file.length() != total) return Result.Failed(Reason.Incomplete)
        onProgress(Progress(0L, total, 0L, null, verifying = true))
        var lastReport = 0L
        val hashed: String? = try {
            OfflineModelStore.sha256(file, shouldStop = { request.get() != Request.NONE }) { count ->
                if (count - lastReport >= VERIFY_REPORT_BYTES) { lastReport = count; onProgress(Progress(count, total, 0L, null, verifying = true)) }
            }
        } catch (_: Exception) { return Result.Failed(Reason.Incomplete) }
        val digest = hashed ?: return (stopRequested() ?: Result.Paused(file.length(), total))
        val expected = store.spec.expectedSha256 ?: target.sha256
        if (expected != null && digest != expected) {
            store.removeInterruptedDownload()
            return Result.Failed(Reason.ChecksumMismatch)
        }
        val trust = when {
            store.spec.expectedSha256 != null -> ModelTrust.PINNED
            target.sha256 != null -> ModelTrust.PUBLISHED
            else -> ModelTrust.UNVERIFIED
        }
        val installed = ModelArtifact(store.spec.revision, total, digest, trust)
        if (!store.promote(installed)) return Result.Failed(Reason.PromoteFailed)
        onProgress(Progress(total, total, 0L, 0L))
        return Result.Installed(total, digest, trust)
    }

    private fun parseContentRange(header: String?): Triple<Long, Long, Long>? {
        val match = CONTENT_RANGE.matchEntire(header.orEmpty().trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        return Triple(start, end, total)
    }

    private object Locks {
        private val locks = ConcurrentHashMap<String, ReentrantLock>()
        fun forKey(key: String) = locks.computeIfAbsent(key) { ReentrantLock() }
    }

    /** HTTPS only, Hugging Face hosts only (including its LFS/Xet CDN redirects). */
    class HttpsTransport : Transport {
        override fun metadata(url: String): String {
            val connection = connect(url, 0L, "application/json")
            try {
                if (connection.responseCode !in 200..299) throw java.io.IOException("metadata HTTP error")
                return connection.inputStream.use { input ->
                    val bytes = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_METADATA_BYTES) throw java.io.IOException("metadata too large")
                        bytes.write(buffer, 0, read)
                    }
                    bytes.toByteArray().toString(Charsets.UTF_8)
                }
            } finally { connection.disconnect() }
        }

        override fun open(url: String, offset: Long): Connection {
            val connection = connect(url, offset, "application/octet-stream")
            return object : Connection {
                override val statusCode: Int = connection.responseCode
                override val contentLength: Long? = connection.contentLengthLong.takeIf { it >= 0L }
                override val contentRange: String? = connection.getHeaderField("Content-Range")
                override val body: InputStream = if (statusCode in 200..299) connection.inputStream else connection.errorStream ?: ByteArrayInputStream(ByteArray(0))
                override fun close() { connection.disconnect() }
            }
        }

        private fun connect(startUrl: String, offset: Long, accept: String): HttpURLConnection {
            var current = URL(startUrl)
            repeat(MAX_REDIRECTS + 1) { redirects ->
                if (!current.protocol.equals("https", ignoreCase = true) || !allowedHost(current.host)) throw java.io.IOException("blocked host")
                val connection = (current.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("User-Agent", "KhodroYar-Offline-AI")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Accept", accept)
                    if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
                }
                val code = connection.responseCode
                if (code in REDIRECT_CODES) {
                    val location = connection.getHeaderField("Location") ?: run { connection.disconnect(); throw java.io.IOException("redirect without location") }
                    connection.disconnect()
                    if (redirects == MAX_REDIRECTS) throw java.io.IOException("too many redirects")
                    current = URL(current, location)
                } else return connection
            }
            throw java.io.IOException("unreachable redirect state")
        }

        private fun allowedHost(host: String): Boolean {
            val normalized = host.lowercase(Locale.ROOT)
            return normalized == "huggingface.co" || normalized.endsWith(".huggingface.co") || normalized == "hf.co" || normalized.endsWith(".hf.co")
        }

        companion object {
            private const val MAX_REDIRECTS = 6
            private const val CONNECT_TIMEOUT_MS = 20_000
            private const val READ_TIMEOUT_MS = 45_000
            private const val MAX_METADATA_BYTES = 512 * 1024
            private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        }
    }

    companion object {
        private const val CHUNK_BYTES = 256 * 1024
        private const val PROGRESS_INTERVAL_NANOS = 300_000_000L
        private const val VERIFY_REPORT_BYTES = 64L * 1024 * 1024
        const val STORAGE_RESERVE_BYTES = 256L * 1024 * 1024
        private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
        private val UNSATISFIED_RANGE = Regex("bytes \\*/(\\d+)")
    }
}
