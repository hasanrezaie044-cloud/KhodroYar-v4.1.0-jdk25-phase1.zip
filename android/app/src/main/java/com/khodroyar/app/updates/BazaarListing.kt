package com.khodroyar.app.updates

import android.content.Context
import android.content.Intent
import android.net.Uri

object BazaarListing {
    /** Explicit user tap only. The app never downloads/replaces its own APK. */
    fun open(context: Context): Boolean = open { context.startActivity(it) }

    internal fun open(launch: (Intent) -> Unit): Boolean {
        val store = Intent(Intent.ACTION_VIEW, Uri.parse(BazaarUpdatePolicy.LISTING_URI))
            .setPackage(BazaarUpdatePolicy.BAZAAR_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { launch(store); return true } catch (_: android.content.ActivityNotFoundException) { }
        catch (_: SecurityException) { }
        val web = Intent(Intent.ACTION_VIEW, Uri.parse(BazaarUpdatePolicy.LISTING_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { launch(web); true } catch (_: android.content.ActivityNotFoundException) { false }
        catch (_: SecurityException) { false }
    }
}
