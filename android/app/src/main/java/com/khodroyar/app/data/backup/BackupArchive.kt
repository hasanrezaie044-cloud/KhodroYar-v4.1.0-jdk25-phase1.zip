package com.khodroyar.app.data.backup

import android.content.Context
import com.khodroyar.app.data.db.RecordAttachmentEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable ZIP backup: records/settings JSON plus app-private record photos. */
object BackupArchive {
    private const val JSON_ENTRY = "backup.json"
    private const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 32L * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val credentialKey = Regex("(?i)(api.?key|token|secret|credential|password|authorization|access.?key)")

    fun create(context: Context, backupJson: String): ByteArray {
        val root = json.parseToJsonElement(backupJson).jsonObject
        val safe = sanitize(root)
        val images = linkedMapOf<String, ByteArray>()
        val rewritten = rewritePaths(safe) { path ->
            if (path.isBlank() || !RecordAttachmentFiles.isOwned(context, path)) ""
            else {
                val file = File(path)
                if (file.length() !in 1..MAX_IMAGE_BYTES) ""
                else {
                    val bytes = file.readBytes()
                    val name = "images/${sha256(bytes)}.${file.extension.ifBlank { "img" }}"
                    images.putIfAbsent(name, bytes)
                    "backup-image:$name"
                }
            }
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(JSON_ENTRY))
            zip.write(rewritten.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            images.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val archive = out.toByteArray()
        require(archive.size.toLong() <= MAX_ARCHIVE_BYTES) { "Backup archive exceeds the size limit" }
        return archive
    }

    /** Accepts this ZIP format or the previous JSON backups; writes images only under private storage. */
    fun open(context: Context, bytes: ByteArray): String {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            return bytes.toString(Charsets.UTF_8)
        }
        require(bytes.size.toLong() <= MAX_ARCHIVE_BYTES) { "Backup archive is too large" }
        var manifest: String? = null
        val extracted = mutableMapOf<String, String>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory) { "Unexpected directory entry" }
                val name = entry.name
                require(!name.startsWith('/') && !name.contains("..") && '\\' !in name) { "Unsafe archive path" }
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(32 * 1024)
                while (true) {
                    val n = zip.read(chunk)
                    if (n < 0) break
                    total += n
                    require(total <= MAX_ARCHIVE_BYTES) { "Backup archive expands beyond the limit" }
                    buffer.write(chunk, 0, n)
                }
                val data = buffer.toByteArray()
                when {
                    name == JSON_ENTRY -> {
                        require(manifest == null) { "Duplicate backup manifest" }
                        manifest = data.toString(Charsets.UTF_8)
                    }
                    name.startsWith("images/") -> {
                        require(data.size <= MAX_IMAGE_BYTES) { "Image entry is too large" }
                        val leaf = name.removePrefix("images/")
                        require(leaf.matches(Regex("^[a-f0-9]{64}\\.[a-zA-Z0-9]{1,8}$"))) { "Invalid image entry" }
                        val dir = File(context.filesDir, "record-attachments").apply { mkdirs() }
                        val target = File(dir, "restore-${UUID.randomUUID()}-${leaf.substringAfter('.')}")
                        target.writeBytes(data)
                        extracted["backup-image:$name"] = target.absolutePath
                    }
                    else -> error("Unknown backup entry: $name")
                }
                zip.closeEntry()
            }
        }
        val parsed = json.parseToJsonElement(requireNotNull(manifest) { "Backup manifest is missing" }).jsonObject
        return rewritePaths(parsed) { value ->
            if (value.startsWith("backup-image:")) extracted[value] ?: "" else value
        }.toString()
    }

    private fun sanitize(root: JsonObject): JsonObject = buildJsonObject {
        root.forEach { (key, value) ->
            if (!credentialKey.containsMatchIn(key)) put(key, sanitizeElement(value))
        }
    }

    private fun sanitizeElement(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> sanitize(value)
        is JsonArray -> JsonArray(value.map(::sanitizeElement))
        else -> value
    }

    private fun rewritePaths(root: JsonObject, map: (String) -> String): JsonObject {
        val pathKeys = setOf("attachmentPath", "filePath")
        fun walk(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> buildJsonObject {
                value.forEach { (key, child) ->
                    if (key in pathKeys && child is JsonPrimitive && child.isString) put(key, map(child.content))
                    else put(key, walk(child))
                }
            }
            is JsonArray -> JsonArray(value.map(::walk))
            else -> value
        }
        return walk(root).jsonObject
    }

    private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(data).joinToString("") { "%02x".format(it) }
}
