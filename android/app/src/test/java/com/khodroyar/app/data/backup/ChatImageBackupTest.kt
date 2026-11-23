package com.khodroyar.app.data.backup

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class ChatImageBackupTest {
    private val enc: (ByteArray) -> String = { Base64.getEncoder().encodeToString(it) }
    private val dec: (String) -> ByteArray = { Base64.getDecoder().decode(it) }

    private fun jpeg(size: Int, seed: Int = 1): ByteArray =
        ByteArray(size) { i -> ((i * 31 + seed) and 0xFF).toByte() }.also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }

    private fun rejects(array: JSONArray) {
        try { ChatImageBackup.unpack(array, dec); fail("tampered image was accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun roundTripKeepsBytesAndCountsMissingFiles() {
        val files = mapOf("a.jpg" to jpeg(1000, 1), "b.jpg" to jpeg(2000, 2))
        val packed = ChatImageBackup.pack(listOf("a.jpg", "gone.jpg", "b.jpg", "a.jpg"), { files[it] }, enc)
        assertEquals(setOf("a.jpg", "b.jpg"), packed.included)
        assertEquals(1, packed.missing)
        assertEquals(0, packed.omitted)
        val restored = ChatImageBackup.unpack(JSONArray(packed.array.toString()), dec)
        assertEquals(listOf("a.jpg", "b.jpg"), restored.map { it.fileName })
        assertTrue(restored[1].bytes.contentEquals(files.getValue("b.jpg")))
    }

    @Test fun budgetFavoursFirstImagesAndRejectsNonJpegOrBadNames() {
        val big = jpeg((ChatImageBackup.MAX_IMAGE_BYTES + 1).toInt())
        val files = mapOf("new.jpg" to jpeg(10), "huge.jpg" to big, "png.jpg" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), "../x.jpg" to jpeg(10))
        val packed = ChatImageBackup.pack(listOf("new.jpg", "huge.jpg", "png.jpg", "../x.jpg"), { files[it] }, enc)
        assertEquals(setOf("new.jpg"), packed.included)
        assertEquals(1, packed.omitted)
        assertEquals(2, packed.missing)
    }

    @Test fun tamperedDigestSizeOrNameIsRejected() {
        val packed = ChatImageBackup.pack(listOf("a.jpg"), { jpeg(500) }, enc)
        fun copy() = JSONArray(packed.array.toString())
        rejects(copy().also { it.getJSONObject(0).put("sha256", "0".repeat(64)) })
        rejects(copy().also { it.getJSONObject(0).put("size", 499) })
        rejects(copy().also { it.getJSONObject(0).put("fileName", "../../evil.jpg") })
        rejects(copy().also { it.getJSONObject(0).put("data", enc(jpeg(500, 9))) })
        rejects(copy().also { it.put(it.getJSONObject(0)) }) // duplicate name
        assertEquals(emptyList<ChatImageBackup.Image>(), ChatImageBackup.unpack(null, dec))
    }
}
