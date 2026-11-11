package com.khodroyar.app.updates

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.farsitel.bazaar.IUpdateCheckService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/** Official Bazaar bound-service protocol. Never performs IPC on the UI thread. */
class BazaarVersionService(context: Context) : BazaarVersionSource {
    private val app = context.applicationContext

    companion object {
        // If a broken remote Binder never returns, it can occupy only ONE worker.
        // No accumulating queue or unbounded threads on subsequent foreground checks.
        private val ipc = ThreadPoolExecutor(0, 1, 20, TimeUnit.SECONDS, SynchronousQueue(),
            { task -> Thread(task, "bazaar-version-ipc").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    }

    override suspend fun check(): BazaarVersionResult {
        if (app.packageName != BazaarUpdatePolicy.APP_PACKAGE) return unavailable()
        try {
            app.packageManager.getApplicationInfo(BazaarUpdatePolicy.BAZAAR_PACKAGE, 0).let {
                if (!it.enabled) return BazaarVersionResult.Unavailable(BazaarVersionResult.Reason.NOT_INSTALLED)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return BazaarVersionResult.Unavailable(BazaarVersionResult.Reason.NOT_INSTALLED)
        } catch (_: SecurityException) {
            return unavailable()
        }
        var connection: ServiceConnection? = null
        var bound = false
        try {
            return withTimeoutOrNull(4_000) {
                val binder = withContext(Dispatchers.Main.immediate) {
                    suspendCancellableCoroutine<IBinder?> { continuation ->
                        val callback = object : ServiceConnection {
                            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                                if (continuation.isActive) continuation.resume(service)
                            }
                            override fun onServiceDisconnected(name: ComponentName) {
                                if (continuation.isActive) continuation.resume(null)
                            }
                            override fun onNullBinding(name: ComponentName) {
                                if (continuation.isActive) continuation.resume(null)
                            }
                            override fun onBindingDied(name: ComponentName) {
                                if (continuation.isActive) continuation.resume(null)
                            }
                        }
                        connection = callback
                        try {
                            bound = app.bindService(Intent(BazaarUpdatePolicy.BIND_ACTION)
                                .setPackage(BazaarUpdatePolicy.BAZAAR_PACKAGE), callback, Context.BIND_AUTO_CREATE)
                            if (!bound && continuation.isActive) continuation.resume(null)
                        } catch (_: SecurityException) {
                            if (continuation.isActive) continuation.resume(null)
                        } catch (_: IllegalArgumentException) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                } ?: return@withTimeoutOrNull unavailable()
                val code = query(binder) ?: return@withTimeoutOrNull unavailable()
                BazaarVersionResult.Reported(code)
            } ?: BazaarVersionResult.Unavailable(BazaarVersionResult.Reason.TIMEOUT)
        } finally {
            // Runs after cancellation/timeout/rotation too; no Activity is retained.
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (bound) connection?.let { callback ->
                    runCatching { app.unbindService(callback) }
                }
            }
        }
    }

    private suspend fun query(binder: IBinder): Long? = suspendCancellableCoroutine { continuation ->
        val task = AtomicReference<Future<*>?>()
        continuation.invokeOnCancellation { task.get()?.cancel(true) }
        try {
            val future = ipc.submit {
                val result = try {
                    IUpdateCheckService.Stub.asInterface(binder)?.getVersionCode(app.packageName)
                } catch (_: Exception) { null }
                if (continuation.isActive) continuation.resume(result)
            }
            task.set(future)
            if (!continuation.isActive) future.cancel(true)
        } catch (_: RejectedExecutionException) {
            if (continuation.isActive) continuation.resume(null)
        }
    }

    private fun unavailable() = BazaarVersionResult.Unavailable(BazaarVersionResult.Reason.SERVICE_UNAVAILABLE)
}
