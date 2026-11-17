package com.khodroyar.app.update

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import com.farsitel.bazaar.IUpdateCheckService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Official Bazaar IPC; no HTTP scraper, developer token or self-install permission. */
class BazaarUpdateService(context: Context) {
    private val context = context.applicationContext

    suspend fun check(installedVersion: Long): BazaarUpdateResult = withContext(Dispatchers.Main.immediate) {
        val bazaarVersion = try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(BazaarUpdatePolicy.PACKAGE, 0)
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        } catch (_: PackageManager.NameNotFoundException) { return@withContext BazaarUpdateResult.NotInstalled }
        catch (_: Exception) { return@withContext BazaarUpdateResult.Unavailable }
        var bound = false
        var connection: ServiceConnection? = null
        // Binder/network work cannot block the main thread or an app-entry
        // timeout. Its independent worker is cancelled and late answers ignored.
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            withTimeoutOrNull(6_000) {
                suspendCancellableCoroutine<BazaarUpdateResult> { continuation ->
                    fun answer(result: BazaarUpdateResult) {
                        if (continuation.isActive) continuation.resume(result)
                    }
                    val con = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                            val service = IUpdateCheckService.Stub.asInterface(binder)
                            worker.launch {
                                val result = try {
                                    val remote = if (bazaarVersion >= BazaarUpdatePolicy.REMOTE_VERSION_SUPPORTED)
                                        service.getRemoteVersionCode(context.packageName) else service.getVersionCode(context.packageName)
                                    BazaarUpdatePolicy.result(installedVersion, remote)
                                } catch (_: Exception) { BazaarUpdateResult.Unavailable }
                                withContext(Dispatchers.Main.immediate) { answer(result) }
                            }
                        }
                        override fun onServiceDisconnected(name: ComponentName) { answer(BazaarUpdateResult.Unavailable) }
                        override fun onNullBinding(name: ComponentName) { answer(BazaarUpdateResult.Unavailable) }
                        override fun onBindingDied(name: ComponentName) { answer(BazaarUpdateResult.Unavailable) }
                    }
                    connection = con
                    try {
                        bound = context.bindService(Intent(BazaarUpdatePolicy.BIND_ACTION).setPackage(BazaarUpdatePolicy.PACKAGE), con, Context.BIND_AUTO_CREATE)
                        if (!bound) answer(BazaarUpdateResult.Unavailable)
                    } catch (_: Exception) { answer(BazaarUpdateResult.Unavailable) }
                }
            } ?: BazaarUpdateResult.Unavailable
        } finally {
            worker.cancel()
            if (bound) connection?.let { runCatching { context.unbindService(it) } }
        }
    }
}

object BazaarUpdateLinks {
    /** Download/install stays in Bazaar and still requires the user's consent. */
    fun open(context: Context): Boolean {
        val app = context.packageName
        val native = listOf(BazaarUpdatePolicy.modalUri(app), BazaarUpdatePolicy.detailsUri(app))
        for (url in native) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .setPackage(BazaarUpdatePolicy.PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: android.content.ActivityNotFoundException) { /* Try the compatible Bazaar link. */ }
            catch (_: SecurityException) { /* Try the web listing instead. */ }
        }
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(BazaarUpdatePolicy.webUri(app)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: android.content.ActivityNotFoundException) { false }
        catch (_: SecurityException) { false }
    }
}
