package com.khodroyar.app.data.backup

import android.content.ContentUris
import android.content.ContentValues
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.data.prefs.SettingsStore
import com.khodroyar.app.data.repo.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Automatic daily backup — the work behind [com.khodroyar.app.notifications.AutoBackupWorker].
 *
 * Where it writes, in order:
 *  1. **Download/KhodroYar** through MediaStore (Android 10+). No permission and no
 *     picker, and — unlike the app's private folder — these files stay on the phone
 *     if the app is uninstalled, which is exactly when a backup is needed. Restore
 *     with Settings → "بازگردانی از فایل".
 *  2. The app's own folder (`VehicleManager`) when MediaStore is unavailable (Android 9
 *     and older, or the insert failed). That copy is removed with the app.
 *
 * One file per day, `khodroyar-auto-<jalali date>.json`; the newest [KEEP] are kept.
 * [AppSettings.autoBackupLastDate] is the dedup guard so a retry or a catch-up run can
 * never produce a second backup for the same day (unless [run] is forced).
 */
object AutoBackup {

    const val FOLDER = "KhodroYar"
    private const val PREFIX = "khodroyar-auto-"
    private const val KEEP = 14

    /** @return true when a backup exists for today (written now or earlier today). */
    suspend fun run(context: Context, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val store = SettingsStore.get(context)
        val today = Jalali.todayString()
        if (!force && store.current.autoBackupLastDate == today) return@withContext true

        val json = AppRepository(context.applicationContext).exportBackupJson()
        val name = PREFIX + today.replace("/", "-") + ".json"
        val place = saveToDownloads(context, json, name) ?: saveInternal(context, json, name)
            ?: return@withContext false

        store.update { it.copy(autoBackupLastDate = today, autoBackupLastPlace = place) }
        // A real backup now exists, so the "you haven't backed up lately" reminder is moot.
        store.markBackupDone()
        true
    }

    private fun saveToDownloads(context: Context, content: String, name: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            saveToDownloadsQ(context, content, name)
        } catch (e: Exception) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToDownloadsQ(context: Context, content: String, name: String): String? {
        val resolver = context.contentResolver
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER"
        // Same-day rerun (forced, or a retry after a crash) replaces the file instead of
        // letting MediaStore invent "name (1).json".
        resolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(name, "$relative%"),
        )
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, BackupFiles.MIME_JSON)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        if (!BackupFiles.writeToUri(context, uri, content)) {
            resolver.delete(uri, null, null)
            return null
        }
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null, null,
        )
        pruneDownloads(resolver, relative)
        return relative
    }

    /** Keeps the newest [KEEP] backups this app created; anything else is never touched. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun pruneDownloads(resolver: ContentResolver, relative: String) {
        try {
            val ids = mutableListOf<Long>()
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("$PREFIX%", "$relative%"),
                "${MediaStore.MediaColumns.DISPLAY_NAME} DESC",
            )?.use { c ->
                while (c.moveToNext()) ids.add(c.getLong(0))
            }
            // File names embed the Jalali date, so name order is date order.
            ids.drop(KEEP).forEach { id ->
                resolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null)
            }
        } catch (_: Exception) {
            // Pruning is housekeeping; a failure must not fail the backup itself.
        }
    }

    private fun saveInternal(context: Context, content: String, name: String): String? {
        val file = BackupFiles.writeInternalCopy(context, content, name) ?: return null
        try {
            file.parentFile?.listFiles { f -> f.name.startsWith(PREFIX) }
                ?.sortedByDescending { it.name }
                ?.drop(KEEP)
                ?.forEach { it.delete() }
        } catch (_: Exception) { }
        return "حافظهٔ داخلی برنامه"
    }
}
