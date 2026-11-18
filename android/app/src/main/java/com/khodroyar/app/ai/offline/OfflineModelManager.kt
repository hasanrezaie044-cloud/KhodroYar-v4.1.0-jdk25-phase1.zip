package com.khodroyar.app.ai.offline

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/** What the «مدیریت هوش مصنوعی آفلاین» section renders. */
data class OfflineModelUiState(
    val phase: Phase = Phase.NOT_INSTALLED,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = OfflineModelSpec.GEMMA_4_E2B.approxBytes,
    val bytesPerSecond: Long = 0L,
    val remainingSeconds: Long? = null,
    val trust: ModelTrust? = null,
    val error: String? = null,
    val freeBytes: Long = -1L,
    val deviceRamBytes: Long = -1L,
    val modelTitle: String = OfflineModelSpec.GEMMA_4_E2B.title,
    val checksumPinned: Boolean = OfflineModelSpec.GEMMA_4_E2B.expectedSha256 != null,
    val recommendedRamBytes: Long = OfflineModelSpec.GEMMA_4_E2B.recommendedRamBytes,
    /** LiteRT-LM is packaged for 64-bit ARM only. */
    val deviceSupported: Boolean = true,
) {
    enum class Phase { NOT_INSTALLED, DOWNLOADING, VERIFYING, PAUSED, READY, ERROR }

    val ready: Boolean get() = phase == Phase.READY
    val active: Boolean get() = phase == Phase.DOWNLOADING || phase == Phase.VERIFYING
    val fraction: Float get() = if (totalBytes <= 0L) 0f else (downloadedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
    val lowRam: Boolean get() = deviceRamBytes in 1 until (recommendedRamBytes * 85 / 100)
}

/**
 * Process-wide owner of the on-device model package: one download at a time, shared by every
 * screen/ViewModel. Only Hugging Face is contacted, only after an explicit button press; no AI
 * provider is ever called from here. The download is resumable (HTTP Range), so if Android stops
 * the process the next «ادامه» continues from the saved byte offset.
 */
class OfflineModelManager private constructor(context: Context) {
    private val app = context.applicationContext
    val store = OfflineModelStore(File(app.noBackupFilesDir, MODEL_DIRECTORY))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val releaseHooks = CopyOnWriteArraySet<suspend () -> Unit>()
    private val _state = MutableStateFlow(OfflineModelUiState())
    val state: StateFlow<OfflineModelUiState> = _state.asStateFlow()

    @Volatile private var downloader: OfflineModelDownloader? = null
    @Volatile private var job: Job? = null
    @Volatile private var lastError: String? = null
    /** True when the LiteRT-LM runtime was actually installed for this device (packaged for arm64-v8a only, extracted). */
    private val deviceSupported: Boolean = runCatching {
        app.applicationInfo.nativeLibraryDir?.let { java.io.File(it, "liblitertlm_jni.so").isFile } ?: false
    }.getOrDefault(false) || (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" && app.applicationInfo.nativeLibraryDir == null)

    init { scope.launch { refresh() } }

    /** Inference engines register here so the model file is never deleted while mapped by LiteRT-LM. */
    fun addReleaseHook(hook: suspend () -> Unit) { releaseHooks += hook }
    fun removeReleaseHook(hook: suspend () -> Unit) { releaseHooks -= hook }

    val modelReady: Boolean get() = store.state() is OfflineModelStore.State.Present

    fun isMeteredNetwork(): Boolean = runCatching {
        (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    }.getOrDefault(false)

    private fun hasInternet(): Boolean = runCatching {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    private fun freeBytes(): Long = runCatching {
        store.root.mkdirs()
        StatFs(store.root.absolutePath).availableBytes
    }.getOrDefault(-1L)

    private fun deviceRam(): Long = runCatching {
        val info = ActivityManager.MemoryInfo()
        (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        info.totalMem
    }.getOrDefault(-1L)

    /** Rebuild the idle state from disk (cheap: no hashing). */
    fun refresh() {
        if (job?.isActive == true) return
        val base = OfflineModelUiState(freeBytes = freeBytes(), deviceRamBytes = deviceRam(), deviceSupported = deviceSupported,
            modelTitle = store.spec.title, checksumPinned = store.spec.expectedSha256 != null,
            recommendedRamBytes = store.spec.recommendedRamBytes, totalBytes = store.spec.approxBytes)
        _state.value = when (val s = store.state()) {
            is OfflineModelStore.State.Present -> base.copy(phase = OfflineModelUiState.Phase.READY,
                downloadedBytes = s.bytes, totalBytes = s.bytes, trust = s.trust)
            is OfflineModelStore.State.Interrupted -> base.copy(
                phase = if (lastError != null) OfflineModelUiState.Phase.ERROR else OfflineModelUiState.Phase.PAUSED,
                downloadedBytes = s.bytes, totalBytes = s.total ?: store.spec.approxBytes, error = lastError)
            is OfflineModelStore.State.Invalid -> base.copy(phase = OfflineModelUiState.Phase.ERROR,
                downloadedBytes = s.bytes, error = "فایل مدل معتبر نیست (${s.reason})؛ «دانلود دوباره» را بزنید.")
            OfflineModelStore.State.Missing -> base.copy(
                phase = if (lastError != null) OfflineModelUiState.Phase.ERROR else OfflineModelUiState.Phase.NOT_INSTALLED,
                error = lastError)
        }
    }

    /** Start or resume. Returns false if a download is already running. */
    @Synchronized
    fun download(): Boolean {
        if (job?.isActive == true) return false
        lastError = null
        if (!deviceSupported) {
            lastError = "این گوشی پردازندهٔ ۶۴ بیتی ARM ندارد و موتور هوش مصنوعی آفلاین روی آن اجرا نمی‌شود؛ دانلود انجام نشد."
            refresh(); return false
        }
        if (!hasInternet()) {
            lastError = "اینترنت در دسترس نیست. برای دانلود مدل به اینترنت وصل شوید؛ بعد از نصب، دستیار بدون اینترنت کار می‌کند."
            refresh(); return false
        }
        val worker = OfflineModelDownloader(store, freeBytes = ::freeBytes)
        downloader = worker
        val partial = store.partialFile.takeIf { it.isFile }?.length() ?: 0L
        _state.update { it.copy(phase = OfflineModelUiState.Phase.DOWNLOADING, downloadedBytes = partial, error = null,
            bytesPerSecond = 0L, remainingSeconds = null) }
        job = scope.launch {
            val result = try {
                worker.download { p ->
                    _state.update {
                        it.copy(phase = if (p.verifying) OfflineModelUiState.Phase.VERIFYING else OfflineModelUiState.Phase.DOWNLOADING,
                            downloadedBytes = p.downloaded, totalBytes = p.total, bytesPerSecond = p.bytesPerSecond,
                            remainingSeconds = p.remainingSeconds, error = null)
                    }
                }
            } catch (e: Exception) {
                OfflineModelDownloader.Result.Failed(OfflineModelDownloader.Reason.Network("خطای غیرمنتظره در دانلود: ${e.javaClass.simpleName}"))
            }
            lastError = (result as? OfflineModelDownloader.Result.Failed)?.let { describe(it.reason) }
            downloader = null
            job = null
            refresh()
        }
        return true
    }

    /** Stop now and keep the partial file for «ادامه». */
    fun pause() { downloader?.pause() }

    /** Stop and delete the partial file. */
    fun cancel() {
        val running = downloader
        if (running != null && job?.isActive == true) { running.cancel(); return }
        store.removeInterruptedDownload()
        lastError = null
        refresh()
    }

    /** Delete the installed model and any partial download; releases loaded engines first. */
    fun delete(onDone: (Long) -> Unit = {}) {
        scope.launch {
            downloader?.cancel()
            job?.join()
            releaseHooks.forEach { hook -> runCatching { hook() } }
            val freed = store.deleteAll()
            lastError = null
            refresh()
            onDone(freed)
        }
    }

    /** Delete, then download a fresh copy. */
    fun redownload() {
        scope.launch {
            downloader?.cancel()
            job?.join()
            releaseHooks.forEach { hook -> runCatching { hook() } }
            store.deleteAll()
            lastError = null
            refresh()
            download()
        }
    }

    private fun describe(reason: OfflineModelDownloader.Reason): String = when (reason) {
        is OfflineModelDownloader.Reason.Storage ->
            "فضای خالی کافی نیست: حدود ${gb(reason.required)} گیگابایت لازم است و ${gb(reason.available.coerceAtLeast(0))} گیگابایت آزاد است."
        OfflineModelDownloader.Reason.MetadataInvalid -> "اطلاعات فایل مدل در Hugging Face معتبر نبود؛ کمی بعد دوباره تلاش کنید."
        OfflineModelDownloader.Reason.InvalidResponse -> "پاسخ سرور دانلود نامعتبر بود؛ دوباره تلاش کنید (در صورت لزوم از ابتدا)."
        is OfflineModelDownloader.Reason.AccessDenied ->
            "Hugging Face اجازهٔ دانلود نداد (HTTP ${reason.httpStatus}). ممکن است دسترسی از منطقهٔ شما محدود باشد یا پذیرش مجوز مدل لازم باشد."
        is OfflineModelDownloader.Reason.HttpError -> "سرور دانلود خطای HTTP ${reason.httpStatus} داد؛ کمی بعد دوباره تلاش کنید."
        OfflineModelDownloader.Reason.Incomplete -> "دانلود کامل نشد؛ «ادامه» را بزنید."
        OfflineModelDownloader.Reason.ChecksumMismatch -> "اثر انگشت SHA-256 فایل با مقدار اعلام‌شده یکی نبود؛ فایل خراب حذف شد. دوباره دانلود کنید."
        OfflineModelDownloader.Reason.PromoteFailed -> "ذخیرهٔ نهایی فایل مدل ناموفق بود؛ فضای حافظه را بررسی و دوباره تلاش کنید."
        is OfflineModelDownloader.Reason.Network -> reason.safeMessage
    }

    companion object {
        const val MODEL_DIRECTORY = "offline-ai-models"
        @Volatile private var instance: OfflineModelManager? = null
        fun get(context: Context): OfflineModelManager =
            instance ?: synchronized(this) { instance ?: OfflineModelManager(context).also { instance = it } }

        private fun gb(bytes: Long): String = String.format(java.util.Locale.US, "%.1f", bytes / 1_000_000_000.0)
    }
}
