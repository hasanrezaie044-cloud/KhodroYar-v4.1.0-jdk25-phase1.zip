package com.khodroyar.app.ai.gemma

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * Answers the two questions that decide whether the offline model may even start:
 * "is there enough RAM?" and "is there enough storage?". All values are real
 * (ActivityManager / StatFs); nothing is estimated.
 */
class DeviceProfile(context: Context) {

    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    data class RamInfo(val totalBytes: Long, val availableBytes: Long, val lowMemory: Boolean)
    data class Verdict(val ok: Boolean, val reasons: List<String>)

    fun ramInfo(): RamInfo {
        val am = activityManager ?: return RamInfo(-1, -1, false)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return RamInfo(mi.totalMem, mi.availMem, mi.lowMemory)
    }

    fun freeStorageBytes(dir: File): Long = try {
        StatFs(dir.absolutePath).availableBytes
    } catch (_: Exception) {
        -1L
    }

    /** Hard gate before DOWNLOADING: storage must suffice (the user is told exactly how much). */
    fun storageVerdict(spec: GemmaModelSpec, dir: File, alreadyDownloaded: Long = 0L): Verdict {
        val free = freeStorageBytes(dir)
        if (free < 0) return Verdict(true, emptyList()) // unknown: let the download fail honestly instead
        val needed = spec.minFreeStorageBytes - alreadyDownloaded
        return if (free >= needed) {
            Verdict(true, emptyList())
        } else {
            Verdict(
                false,
                listOf("فضای آزاد کافی نیست: ${mb(needed)} مگابایت لازم است ولی ${mb(free)} مگابایت آزاد است."),
            )
        }
    }

    /** Advisory gate for RUNNING the model. A device below the pin gets a clear warning, not a crash. */
    fun ramVerdict(spec: GemmaModelSpec): Verdict {
        val info = ramInfo()
        if (info.totalBytes <= 0) return Verdict(true, emptyList())
        val reasons = ArrayList<String>()
        if (info.totalBytes < spec.minDeviceRamBytes) {
            reasons.add("حافظهٔ دستگاه ${gb(info.totalBytes)} گیگابایت است؛ حداقل رسمی ${gb(spec.minDeviceRamBytes)} گیگابایت. ممکن است مدل کند اجرا شود یا بارگذاری نشود.")
        }
        if (info.lowMemory) {
            reasons.add("سیستم اکنون کمبود حافظه گزارش کرده؛ چند برنامهٔ باز را ببندید.")
        }
        return Verdict(reasons.isEmpty(), reasons)
    }

    /** Enough headroom to (re)create the engine: model stays memory-mapped, but prefill spikes. */
    fun hasHeadroomForInference(): Boolean {
        val info = ramInfo()
        return !info.lowMemory && (info.availableBytes < 0 || info.availableBytes >= 700L * 1024 * 1024)
    }

    companion object {
        fun mb(bytes: Long): Long = bytes / (1024 * 1024)
        fun gb(bytes: Long): String = String.format(java.util.Locale.US, "%.1f", bytes / (1024.0 * 1024 * 1024))
    }
}
