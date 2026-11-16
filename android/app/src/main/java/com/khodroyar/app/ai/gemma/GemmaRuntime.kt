package com.khodroyar.app.ai.gemma

import android.content.Context
import java.io.File

/**
 * Process-wide owner of everything the offline assistant needs. Lives at app level so the
 * (expensive) engine survives ViewModel recreation on rotation, and so the screen's
 * `DisposableEffect` can deterministically release RAM when the user navigates away.
 */
class GemmaRuntime private constructor(context: Context) {

    private val appContext = context.applicationContext
    val spec: GemmaModelSpec = GemmaModelSpec.GEMMA_4_E2B
    val deviceProfile = DeviceProfile(appContext)
    val modelStore = ModelStore(File(appContext.filesDir, "gemma_model"), spec)

    /** A NEW downloader per start (a paused manager can't be re-armed). */
    fun makeDownloader(): ModelDownloadManager = ModelDownloadManager(
        store = modelStore,
        spec = spec,
        transport = HttpUrlTransport(),
        freeBytes = { deviceProfile.freeStorageBytes(modelStore.rootDir) },
    )

    private var engineRef: LiteRtLmEngine? = null
    private val engineLock = Any()

    /**
     * The single process-wide engine. Created only on first real use — never as a side
     * effect of housekeeping (so releasing RAM is free when nothing was loaded).
     */
    fun engine(): LiteRtLmEngine = engineRef ?: synchronized(engineLock) {
        engineRef ?: LiteRtLmEngine(
            context = appContext,
            modelPathProvider = {
                val f = modelStore.modelFile
                if (f.isFile && f.length() == spec.expectedSizeBytes) f else null
            },
            deviceProfile = deviceProfile,
        ).also { engineRef = it }
    }

    /** Unmaps the model RAM; if the engine was never created this is a guaranteed no-op. */
    fun releaseEngine() {
        synchronized(engineLock) { engineRef?.release() }
    }

    companion object {
        @Volatile private var instance: GemmaRuntime? = null

        fun get(context: Context): GemmaRuntime =
            instance ?: synchronized(this) {
                instance ?: GemmaRuntime(context.applicationContext).also { instance = it }
            }
    }
}
