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
    val totalBytes: Long = OfflineModelManager.ACTIVE_PACKAGE.totalBytes,
    val bytesPerSecond: Long = 0L,
    val remainingSeconds: Long? = null,
    val trust: ModelTrust? = null,
    val error: String? = null,
    val freeBytes: Long = -1L,
    val deviceRamBytes: Long = -1L,
    val modelTitle: String = OfflineModelManager.ACTIVE_PACKAGE.title,
    val checksumPinned: Boolean = true,
    val recommendedRamBytes: Long = OfflineModelManager.ACTIVE_PACKAGE.recommendedRamBytes,
    /** The on-device runtimes are packaged for 64-bit ARM only. */
    val deviceSupported: Boolean = true,
    /** 1-based index of the file being fetched and the number of files in the package (e.g. model + vision). */
    val fileIndex: Int = 0,
    val fileCount: Int = OfflineModelManager.ACTIVE_PACKAGE.files.size,
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
    val pkg: OfflineModelPackage = ACTIVE_PACKAGE
    private val root = File(app.noBackupFilesDir, MODEL_DIRECTORY)
    /** One resumable, SHA-256-pinned store per package file; the first is the language model. */
    val stores: List<OfflineModelStore> = pkg.files.map { OfflineModelStore(root, it) }
    val store: OfflineModelStore get() = stores.first()
    /** The separately downloaded vision projector (llama.cpp packages), or null when vision is built into the model. */
    val visionStore: OfflineModelStore? get() = pkg.vision?.let { v -> stores.firstOrNull { it.spec == v } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val releaseHooks = CopyOnWriteArraySet<suspend () -> Unit>()
    private val _state = MutableStateFlow(OfflineModelUiState())
    val state: StateFlow<OfflineModelUiState> = _state.asStateFlow()

    @Volatile private var downloader: OfflineModelDownloader? = null
    @Volatile private var job: Job? = null
    @Volatile private var lastError: String? = null
    /** True when the package's runtime was actually installed for this device (packaged for arm64-v8a only, extracted). */
    /** llama.cpp is built for API 28+ (as in its Android docs); older Android cannot load libkhodroyar_llama.so. */
    private val deviceSupported: Boolean = (pkg.runtime != OfflineRuntime.LLAMA_CPP || Build.VERSION.SDK_INT >= 28) && (runCatching {
        val library = when (pkg.runtime) {
            OfflineRuntime.LITERT_LM -> "liblitertlm_jni.so"
            OfflineRuntime.LLAMA_CPP -> "libkhodroyar_llama.so"
        }
        app.applicationInfo.nativeLibraryDir?.let { java.io.File(it, library).isFile } ?: false
    }.getOrDefault(false) || (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" && app.applicationInfo.nativeLibraryDir == null))

    init { scope.launch { refresh() } }

    /** Inference engines register here so the model file is never deleted while mapped by LiteRT-LM. */
    fun addReleaseHook(hook: suspend () -> Unit) { releaseHooks += hook }
    fun removeReleaseHook(hook: suspend () -> Unit) { releaseHooks -= hook }

    val modelReady: Boolean get() = stores.all { it.state() is OfflineModelStore.State.Present }

    fun isMeteredNetwork(): Boolean = runCatching {
        (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    }.getOrDefault(false)

    private fun hasInternet(): Boolean = runCatching {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    private fun freeBytes(): Long = runCatching {
        root.mkdirs()
        StatFs(root.absolutePath).availableBytes
    }.getOrDefault(-1L)

    private fun deviceRam(): Long = runCatching {
        val info = ActivityManager.MemoryInfo()
        (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        info.totalMem
    }.getOrDefault(-1L)

    /** Rebuild the idle state from disk (cheap: no hashing). Package state = combined state of its files. */
    fun refresh() {
        if (job?.isActive == true) return
        val base = OfflineModelUiState(freeBytes = freeBytes(), deviceRamBytes = deviceRam(), deviceSupported = deviceSupported,
            modelTitle = pkg.title, checksumPinned = pkg.files.all { it.expectedSha256 != null },
            recommendedRamBytes = pkg.recommendedRamBytes, totalBytes = pkg.totalBytes, fileCount = stores.size)
        val states = stores.map { it.state() }
        val invalid = states.withIndex().firstOrNull { it.value is OfflineModelStore.State.Invalid }
        val have = states.sumOf { s ->
            when (s) {
                is OfflineModelStore.State.Present -> s.bytes
                is OfflineModelStore.State.Interrupted -> s.bytes
                else -> 0L
            }
        }
        _state.value = when {
            states.all { it is OfflineModelStore.State.Present } -> {
                val present = states.map { it as OfflineModelStore.State.Present }
                base.copy(phase = OfflineModelUiState.Phase.READY, downloadedBytes = present.sumOf { it.bytes },
                    totalBytes = present.sumOf { it.bytes }, trust = present.maxByOrNull { it.trust.ordinal }?.trust) // weakest file decides
            }
            invalid != null -> base.copy(phase = OfflineModelUiState.Phase.ERROR, downloadedBytes = have,
                error = "فایل «${stores[invalid.index].spec.fileName}» معتبر نیست (${(invalid.value as OfflineModelStore.State.Invalid).reason})؛ «دانلود دوباره» را بزنید.")
            have > 0L -> base.copy(
                phase = if (lastError != null) OfflineModelUiState.Phase.ERROR else OfflineModelUiState.Phase.PAUSED,
                downloadedBytes = have, error = lastError)
            else -> base.copy(
                phase = if (lastError != null) OfflineModelUiState.Phase.ERROR else OfflineModelUiState.Phase.NOT_INSTALLED,
                error = lastError)
        }
    }

    /** Start or resume. Files are fetched one after another; finished files are kept and skipped. */
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
        val already = currentBytes()
        _state.update { it.copy(phase = OfflineModelUiState.Phase.DOWNLOADING, downloadedBytes = already, error = null,
            bytesPerSecond = 0L, remainingSeconds = null) }
        job = scope.launch {
            var failure: String? = null
            for ((index, fileStore) in stores.withIndex()) {
                if (fileStore.state() is OfflineModelStore.State.Present) continue
                // bytes of the files before this one (installed), so the bar covers the whole package
                val before = stores.take(index).sumOf { (it.state() as? OfflineModelStore.State.Present)?.bytes ?: it.spec.approxBytes }
                // bytes still to fetch after this file, for the storage preflight of the whole package
                val after = stores.drop(index + 1).sumOf { s ->
                    if (s.state() is OfflineModelStore.State.Present) 0L else s.spec.approxBytes - (s.partialFile.takeIf { it.isFile }?.length() ?: 0L)
                }
                val worker = OfflineModelDownloader(fileStore, freeBytes = { freeBytes() - after })
                downloader = worker
                val result = try {
                    worker.download { p ->
                        _state.update {
                            it.copy(phase = if (p.verifying) OfflineModelUiState.Phase.VERIFYING else OfflineModelUiState.Phase.DOWNLOADING,
                                downloadedBytes = before + p.downloaded, totalBytes = pkg.totalBytes,
                                bytesPerSecond = p.bytesPerSecond,
                                remainingSeconds = if (p.bytesPerSecond > 0) (pkg.totalBytes - before - p.downloaded).coerceAtLeast(0L) / p.bytesPerSecond else null,
                                error = null, fileIndex = index + 1)
                        }
                    }
                } catch (e: Exception) {
                    OfflineModelDownloader.Result.Failed(OfflineModelDownloader.Reason.Network("خطای غیرمنتظره در دانلود: ${e.javaClass.simpleName}"))
                }
                downloader = null
                if (result is OfflineModelDownloader.Result.Installed) continue
                failure = (result as? OfflineModelDownloader.Result.Failed)?.let { describe(it.reason) }
                break // paused, cancelled, busy or failed: stop the package here
            }
            lastError = failure
            job = null
            refresh()
        }
        return true
    }

    private fun currentBytes(): Long = stores.sumOf { s ->
        when (val st = s.state()) {
            is OfflineModelStore.State.Present -> st.bytes
            is OfflineModelStore.State.Interrupted -> st.bytes
            else -> 0L
        }
    }

    /** Stop now and keep the partial file for «ادامه». */
    fun pause() { downloader?.pause() }

    /** Stop and delete the partial file. Completely downloaded and verified files are kept. */
    fun cancel() {
        val running = downloader
        if (running != null && job?.isActive == true) { running.cancel(); return }
        stores.forEach { it.removeInterruptedDownload() }
        lastError = null
        refresh()
    }

    /** Files of other (earlier or not selected) model packages in the same directory, e.g. after a model switch. */
    private fun otherPackageStores(): List<OfflineModelStore> = OfflineModelPackage.ALL
        .flatMap { it.files }.distinctBy { it.fileName }
        .filter { spec -> pkg.files.none { it.fileName == spec.fileName } }
        .map { OfflineModelStore(root, it) }

    /** Delete the installed model and any partial download; releases loaded engines first. */
    fun delete(onDone: (Long) -> Unit = {}) {
        scope.launch {
            downloader?.cancel()
            job?.join()
            releaseHooks.forEach { hook -> runCatching { hook() } }
            val freed = (stores + otherPackageStores()).sumOf { it.deleteAll() }
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
            (stores + otherPackageStores()).forEach { it.deleteAll() }
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

        /**
         * The model package the app installs and runs offline. Changed only on measured evidence
         * (tools/offline-eval: identical Persian prompts, document photos, tools, speed and RAM).
         */
        val ACTIVE_PACKAGE: OfflineModelPackage = OfflineModelPackage.GEMMA_4_E2B_LITERT
        @Volatile private var instance: OfflineModelManager? = null
        fun get(context: Context): OfflineModelManager =
            instance ?: synchronized(this) { instance ?: OfflineModelManager(context).also { instance = it } }

        private fun gb(bytes: Long): String = String.format(java.util.Locale.US, "%.1f", bytes / 1_000_000_000.0)
    }
}
