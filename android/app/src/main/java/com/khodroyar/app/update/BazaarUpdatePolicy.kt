package com.khodroyar.app.update

/** A failed/offline check is UNKNOWN, never an invented 'update available'. */
sealed interface BazaarUpdateResult {
    data class Available(val versionCode: Long) : BazaarUpdateResult
    data object Current : BazaarUpdateResult
    data object NotInstalled : BazaarUpdateResult
    data object Unavailable : BazaarUpdateResult
}

object BazaarUpdatePolicy {
    const val PACKAGE = "com.farsitel.bazaar"
    const val BIND_ACTION = "com.farsitel.bazaar.service.UpdateCheckService.BIND"
    const val REMOTE_VERSION_SUPPORTED = 2_400_700L
    const val REMIND_AFTER_MS = 24 * 60 * 60 * 1000L

    fun result(installed: Long, remote: Long): BazaarUpdateResult = when {
        installed <= 0 || remote <= 0 -> BazaarUpdateResult.Unavailable
        remote > installed -> BazaarUpdateResult.Available(remote)
        else -> BazaarUpdateResult.Current
    }

    fun shouldPrompt(remote: Long, installed: Long, dismissedVersion: Long, dismissedAt: Long, now: Long): Boolean =
        remote > installed && remote > 0 && (remote != dismissedVersion || now < dismissedAt || now - dismissedAt >= REMIND_AFTER_MS)

    fun modalUri(packageName: String): String = "bazaar://details/modal?id=${validPackage(packageName)}"
    fun detailsUri(packageName: String): String = "bazaar://details?id=${validPackage(packageName)}"
    fun webUri(packageName: String): String = "https://cafebazaar.ir/app/${validPackage(packageName)}"
    private fun validPackage(value: String): String = value.also { require(Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+").matches(it)) }
}
