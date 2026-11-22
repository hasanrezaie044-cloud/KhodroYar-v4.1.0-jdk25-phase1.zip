package com.khodroyar.app.data.backup

import android.content.Context
import android.util.Base64
import com.khodroyar.app.data.attachments.Attachments
import com.khodroyar.app.data.db.APP_DATABASE_SCHEMA_VERSION
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Versioned, integrity-checked attachment payload for the backwards-compatible JSON backup.
 * Files are content-addressed inside the manifest; device-specific absolute paths are never
 * exported. API credentials/settings are not handled by this codec.
 */
object BackupAttachmentCodec {
    private const val MANIFEST = "backupManifest"
    private const val DATA = "attachmentData"
    private const val PREFIX = "backup-attachment:"
    private const val FORMAT = "com.khodroyar.app.full-backup"
    private const val VERSION = 3
    private val READABLE_VERSIONS = setOf(2, 3)

    /** Every (table, column) that may reference an app-private file. */
    private val FILE_FIELDS = listOf(
        "services" to "attachmentPath",
        "maintenances" to "attachmentPath",
        "recordAttachments" to "filePath",
    )
    private const val MAX_FILES = 512
    private const val MAX_FILE_BYTES = 25L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    class PreparedRestore internal constructor(
        val json: String,
        private val createdFiles: List<File>,
    ) : AutoCloseable {
        private var retained = false
        /** Call only after the database restore has committed. */
        fun keep() { retained = true }
        /** Remove newly materialized files if validation or the database transaction failed. */
        override fun close() {
            if (!retained) createdFiles.forEach { runCatching { it.delete() } }
        }
    }

    /** Embeds all attached service/maintenance files and adds a versioned manifest. */
    fun pack(context: Context, sourceJson: String): String {
        val root = json.parseToJsonElement(sourceJson).jsonObject
        require(MANIFEST !in root && DATA !in root) { "این فایل قبلاً بسته‌بندی شده است." }
        val content = linkedMapOf<String, ByteArray>()
        val referenced = linkedSetOf<String>()
        var total = 0L
        var omittedAttachmentCount = 0

        fun rewrite(key: String, field: String): JsonArray {
            val rows = root[key]?.jsonArray ?: return JsonArray(emptyList())
            // A gallery row without its file is meaningless, so it is dropped (and counted).
            val dropMissing = key == "recordAttachments"
            return JsonArray(rows.mapNotNull { element ->
                val row = element.jsonObject
                val path = row[field]?.let { if (it == JsonNull) "" else it.jsonPrimitive.content }?.trim().orEmpty()
                if (path.isEmpty()) element.takeUnless { dropMissing } else {
                    val file = File(path)
                    // Keep the database record, but don't let a stale or inaccessible
                    // attachment path prevent backing up every other record. The actual
                    // missing file cannot be restored, so clear only that path in the
                    // exported copy and record a visible warning in the manifest.
                    val owned = Attachments.isOwn(context, path) || RecordAttachmentFiles.isOwned(context, path)
                    if (!owned || !file.isFile || !file.canRead() || file.length() == 0L) {
                        omittedAttachmentCount++
                        if (dropMissing) null else JsonObject(row + (field to JsonPrimitive("")))
                    } else {
                        val length = file.length()
                        require(length in 1..MAX_FILE_BYTES) { "اندازهٔ یکی از پیوست‌ها خارج از محدودهٔ پشتیبانی است." }
                        val bytes = runCatching { file.readBytes() }.getOrNull()
                        if (bytes == null) {
                            omittedAttachmentCount++
                            if (dropMissing) null else JsonObject(row + (field to JsonPrimitive("")))
                        } else {
                            require(bytes.size.toLong() == length) { "فایل پیوست هنگام پشتیبان‌گیری تغییر کرد؛ دوباره تلاش کنید." }
                            val digest = sha256(bytes)
                            if (digest !in content) {
                                total += bytes.size
                                require(total <= MAX_TOTAL_BYTES) { "حجم پیوست‌ها بیش از حد مجاز پشتیبان JSON است؛ فایل‌ها را جداگانه ذخیره کنید." }
                                require(content.size < MAX_FILES) { "تعداد پیوست‌ها بیش از حد مجاز است." }
                                content[digest] = bytes
                            }
                            referenced += digest
                            JsonObject(row + (field to JsonPrimitive(PREFIX + digest)))
                        }
                    }
                }
            })
        }

        val payload = LinkedHashMap(root)
        FILE_FIELDS.forEach { (key, field) -> if (key in root) payload[key] = rewrite(key, field) }
        val payloadJson = canonical(JsonObject(payload))
        val data = buildJsonObject {
            content.forEach { (digest, bytes) -> put(digest, JsonPrimitive(Base64.encodeToString(bytes, Base64.NO_WRAP))) }
        }
        val manifest = buildJsonObject {
            put("format", JsonPrimitive(FORMAT))
            put("formatVersion", JsonPrimitive(VERSION))
            put("databaseVersion", JsonPrimitive(APP_DATABASE_SCHEMA_VERSION))
            put("createdAt", JsonPrimitive(Instant.now().toString()))
            put("payloadSha256", JsonPrimitive(sha256(payloadJson.toByteArray(Charsets.UTF_8))))
            put("attachmentCount", JsonPrimitive(referenced.size))
            put("omittedAttachmentCount", JsonPrimitive(omittedAttachmentCount))
            put("attachments", JsonArray(referenced.sorted().map { digest ->
                val bytes = content.getValue(digest)
                buildJsonObject {
                    put("id", JsonPrimitive(digest))
                    put("size", JsonPrimitive(bytes.size))
                    put("sha256", JsonPrimitive(digest))
                }
            }))
        }
        val output = LinkedHashMap(payload)
        output[MANIFEST] = manifest
        output[DATA] = data
        return JsonObject(output).toString()
    }

    /** Reads the lightweight warning count emitted by [pack] without parsing large base64 payloads. */
    fun omittedAttachmentCount(sourceJson: String): Int {
        val manifestIndex = sourceJson.indexOf("\"$MANIFEST\"")
        if (manifestIndex < 0) return 0 // legacy backup, or no omissions
        val key = "\"omittedAttachmentCount\""
        val keyIndex = sourceJson.indexOf(key, manifestIndex + MANIFEST.length + 2)
        if (keyIndex < 0) return 0 // older full-backup manifest
        val colon = sourceJson.indexOf(':', keyIndex + key.length)
        if (colon < 0) return 0
        var start = colon + 1
        while (start < sourceJson.length && sourceJson[start].isWhitespace()) start++
        var end = start
        while (end < sourceJson.length && sourceJson[end].isDigit()) end++
        return sourceJson.substring(start, end).toIntOrNull() ?: 0
    }

    /** Validates manifest, payload and all file hashes before exposing a restore preview. */
    fun validate(context: Context, sourceJson: String) {
        val root = json.parseToJsonElement(sourceJson).jsonObject
        if (MANIFEST !in root) return // legacy JSON backup
        validateAndRewrite(context, root, materialize = false)
    }

    /**
     * Validates the complete archive, writes files atomically into app-private storage and returns
     * JSON whose attachment paths point to those files. Caller must close on failure, or call keep()
     * after the Room restore commits.
     */
    fun prepareRestore(context: Context, sourceJson: String): PreparedRestore {
        val root = json.parseToJsonElement(sourceJson).jsonObject
        if (MANIFEST !in root) return PreparedRestore(sourceJson, emptyList())
        val result = validateAndRewrite(context, root, materialize = true)
        return PreparedRestore(result.first, result.second)
    }

    private fun validateAndRewrite(
        context: Context,
        root: JsonObject,
        materialize: Boolean,
    ): Pair<String, List<File>> {
        val manifest = root[MANIFEST]?.jsonObject ?: error("مانیفست پشتیبان نامعتبر است.")
        require(manifest["format"]?.jsonPrimitive?.content == FORMAT) { "قالب پشتیبان ناشناخته است." }
        require(manifest["formatVersion"]?.jsonPrimitive?.int in READABLE_VERSIONS) { "نسخهٔ قالب پشتیبان پشتیبانی نمی‌شود." }
        val dbVersion = manifest["databaseVersion"]?.jsonPrimitive?.int ?: error("نسخهٔ پایگاه‌داده در مانیفست نیست.")
        require(dbVersion in 1..APP_DATABASE_SCHEMA_VERSION) { "این پشتیبان با نسخهٔ جدیدتری از پایگاه‌داده ساخته شده است." }

        val data = root[DATA]?.jsonObject ?: error("دادهٔ پیوست‌های پشتیبان وجود ندارد.")
        val descriptors = manifest["attachments"]?.jsonArray ?: error("فهرست پیوست‌های پشتیبان نامعتبر است.")
        require(descriptors.size <= MAX_FILES) { "تعداد پیوست‌ها بیش از حد مجاز است." }
        val ids = linkedMapOf<String, Pair<Long, String>>()
        var total = 0L
        for (item in descriptors) {
            val descriptor = item.jsonObject
            val id = descriptor["id"]?.jsonPrimitive?.content ?: error("شناسهٔ پیوست نامعتبر است.")
            val checksum = descriptor["sha256"]?.jsonPrimitive?.content ?: error("چکیدهٔ پیوست موجود نیست.")
            val size = descriptor["size"]?.jsonPrimitive?.long ?: error("اندازهٔ پیوست موجود نیست.")
            require(Regex("[a-f0-9]{64}").matches(id) && id == checksum) { "شناسه یا چکیدهٔ پیوست نامعتبر است." }
            require(size in 1..MAX_FILE_BYTES) { "اندازهٔ پیوست نامعتبر است." }
            require(ids.put(id, size to checksum) == null) { "پیوست تکراری در مانیفست است." }
            total += size
            require(total <= MAX_TOTAL_BYTES) { "حجم پیوست‌های پشتیبان بیش از حد مجاز است." }
        }
        require(manifest["attachmentCount"]?.jsonPrimitive?.int == ids.size) { "تعداد پیوست‌های مانیفست با محتوا یکی نیست." }
        require(data.keys == ids.keys) { "فهرست فایل‌های پیوست با مانیفست سازگار نیست." }

        val bytesById = linkedMapOf<String, ByteArray>()
        for ((id, details) in ids) {
            val encoded = data[id]?.jsonPrimitive?.content ?: error("محتوای یک پیوست وجود ندارد.")
            val bytes = try { Base64.decode(encoded, Base64.DEFAULT) } catch (_: IllegalArgumentException) { error("کدگذاری یکی از پیوست‌ها خراب است.") }
            require(bytes.size.toLong() == details.first && sha256(bytes) == details.second) { "اعتبارسنجی یکی از پیوست‌ها ناموفق بود." }
            bytesById[id] = bytes
        }

        val payload = LinkedHashMap(root)
        payload.remove(MANIFEST)
        payload.remove(DATA)
        val payloadHash = manifest["payloadSha256"]?.jsonPrimitive?.content ?: error("چکیدهٔ داده‌های پشتیبان موجود نیست.")
        require(sha256(canonical(JsonObject(payload)).toByteArray(Charsets.UTF_8)) == payloadHash) {
            "اعتبارسنجی داده‌های پشتیبان ناموفق بود؛ فایل ناقص یا دست‌کاری‌شده است."
        }

        val referenced = linkedSetOf<String>()
        fun rewrite(key: String, field: String): JsonArray {
            val rows = payload[key]?.jsonArray ?: return JsonArray(emptyList())
            return JsonArray(rows.map { element ->
                val row = element.jsonObject
                val path = row[field]?.let { if (it == JsonNull) "" else it.jsonPrimitive.content }?.trim().orEmpty()
                if (path.isEmpty()) element else {
                    require(path.startsWith(PREFIX)) { "مسیر پیوست در پشتیبان جدید نامعتبر است." }
                    val id = path.removePrefix(PREFIX)
                    require(id in ids) { "یکی از رکوردها به پیوست ثبت‌نشده اشاره می‌کند." }
                    referenced += id
                    JsonObject(row + (field to JsonPrimitive(path)))
                }
            })
        }
        FILE_FIELDS.forEach { (key, field) -> if (key in payload) payload[key] = rewrite(key, field) }
        require(referenced == ids.keys) { "پیوست بدون رکورد یا ارجاع پیوست ناسازگار در فایل وجود دارد." }

        val created = mutableListOf<File>()
        try {
            val rewritten = LinkedHashMap(payload)
            // One restored file per content hash: identical photos are never duplicated on disk.
            val written = linkedMapOf<String, File>()
            fun materializeRows(key: String, field: String): JsonArray {
                val rows = payload[key]?.jsonArray ?: return JsonArray(emptyList())
                return JsonArray(rows.map { element ->
                    val row = element.jsonObject
                    val path = row[field]?.let { if (it == JsonNull) "" else it.jsonPrimitive.content }?.trim().orEmpty()
                    if (!path.startsWith(PREFIX)) element else {
                        val id = path.removePrefix(PREFIX)
                        val file = if (!materialize) null else written.getOrPut(id) {
                            writeRestoredFile(context, bytesById.getValue(id)).also { created += it }
                        }
                        JsonObject(row + (field to JsonPrimitive(file?.absolutePath ?: path)))
                    }
                })
            }
            FILE_FIELDS.forEach { (key, field) -> if (key in payload) rewritten[key] = materializeRows(key, field) }
            return JsonObject(rewritten).toString() to created
        } catch (e: Exception) {
            created.forEach { runCatching { it.delete() } }
            throw e
        }
    }

    private fun writeRestoredFile(context: Context, bytes: ByteArray): File {
        val directory = Attachments.dir(context)
        val id = UUID.randomUUID().toString()
        val temporary = File(directory, ".restore-$id.tmp")
        val target = File(directory, "restored-$id.jpg")
        try {
            FileOutputStream(temporary).use { out -> out.write(bytes); out.fd.sync() }
            check(temporary.renameTo(target)) { "ذخیرهٔ ایمن یکی از پیوست‌ها انجام نشد." }
            return target
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    /** Stable across JSON whitespace/object key ordering; array order remains significant. */
    private fun canonical(value: kotlinx.serialization.json.JsonElement): String = when (value) {
        is JsonObject -> value.entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}") {
            JsonPrimitive(it.key).toString() + ":" + canonical(it.value)
        }
        is JsonArray -> value.joinToString(prefix = "[", postfix = "]") { canonical(it) }
        else -> value.toString()
    }
}
