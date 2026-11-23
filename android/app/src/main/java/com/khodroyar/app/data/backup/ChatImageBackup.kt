package com.khodroyar.app.data.backup

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * AI chat images inside the assistant section of a full backup (assistant format v2, 5.9.0).
 *
 * Each image is embedded once with its size and SHA-256. Export never claims more than it
 * wrote: images whose file is gone are counted as missing, oversized ones as omitted, and
 * the conversation keeps an image reference only when the image itself is in the backup.
 * Restore verifies name, size, digest and JPEG signature of every image before anything is
 * written; one bad image rejects the backup instead of restoring silently-broken chats.
 *
 * Pure Kotlin (Base64 is injected) so the format is JVM-tested.
 */
object ChatImageBackup {
    const val MAX_IMAGES = 40
    const val MAX_IMAGE_BYTES = 6L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 16L * 1024 * 1024
    private val NAME = Regex("[A-Za-z0-9_-]{1,80}\\.jpg")

    class Image(val fileName: String, val bytes: ByteArray, val sha256: String)

    data class Packed(val array: JSONArray, val included: Set<String>, val missing: Int, val omitted: Int)

    /**
     * @param fileNames referenced images, most recent conversation first (they win the size budget).
     * @param read returns the file bytes, or null when the file no longer exists.
     */
    fun pack(fileNames: List<String>, read: (String) -> ByteArray?, encode: (ByteArray) -> String): Packed {
        val array = JSONArray()
        val included = linkedSetOf<String>()
        var missing = 0
        var omitted = 0
        var total = 0L
        for (name in fileNames.distinct()) {
            if (!NAME.matches(name)) { missing++; continue }
            val bytes = runCatching { read(name) }.getOrNull()
            if (bytes == null || bytes.isEmpty()) { missing++; continue }
            if (!isJpeg(bytes)) { missing++; continue }
            if (bytes.size > MAX_IMAGE_BYTES || total + bytes.size > MAX_TOTAL_BYTES || included.size >= MAX_IMAGES) { omitted++; continue }
            total += bytes.size
            included += name
            array.put(JSONObject().put("fileName", name).put("size", bytes.size).put("sha256", sha256(bytes)).put("data", encode(bytes)))
        }
        return Packed(array, included, missing, omitted)
    }

    /** Validates every entry; throws with a Persian message on the first inconsistency. */
    fun unpack(array: JSONArray?, decode: (String) -> ByteArray): List<Image> {
        if (array == null) return emptyList()
        require(array.length() <= MAX_IMAGES) { "تعداد تصاویر گفتگو در پشتیبان بیش از حد مجاز است." }
        var total = 0L
        val seen = hashSetOf<String>()
        return (0 until array.length()).map { i ->
            val o = requireNotNull(array.optJSONObject(i)) { "تصویر گفتگوی شمارهٔ ${i + 1} معتبر نیست." }
            val name = o.optString("fileName")
            require(NAME.matches(name) && seen.add(name)) { "نام تصویر گفتگوی شمارهٔ ${i + 1} معتبر نیست." }
            val size = o.optLong("size", -1)
            require(size in 1..MAX_IMAGE_BYTES) { "اندازهٔ تصویر گفتگوی «$name» معتبر نیست." }
            total += size
            require(total <= MAX_TOTAL_BYTES) { "حجم تصاویر گفتگو در پشتیبان بیش از حد مجاز است." }
            val bytes = runCatching { decode(o.optString("data")) }.getOrNull()
            require(bytes != null && bytes.size.toLong() == size) { "دادهٔ تصویر گفتگوی «$name» ناقص است." }
            val digest = sha256(bytes)
            require(digest == o.optString("sha256").lowercase()) { "اثر SHA-256 تصویر گفتگوی «$name» مطابقت ندارد." }
            require(isJpeg(bytes)) { "تصویر گفتگوی «$name» JPEG معتبر نیست." }
            Image(name, bytes, digest)
        }
    }

    fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
