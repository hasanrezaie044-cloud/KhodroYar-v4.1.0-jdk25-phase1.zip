package com.khodroyar.app.ui

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore

/** Only generated emulator fixtures/evidence. UTP deletes app-specific files after a run. */
internal fun publishSyntheticEvidence(context: Context, directory: String, name: String, mime: String, bytes: ByteArray) {
    check(Build.VERSION.SDK_INT >= 29)
    check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.RELATIVE_PATH, "Download/$directory")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
    try {
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    } catch (error: Throwable) {
        resolver.delete(uri, null, null)
        throw error
    }
}
