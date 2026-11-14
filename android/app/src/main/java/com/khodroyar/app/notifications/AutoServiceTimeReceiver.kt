package com.khodroyar.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Protected system broadcasts only; no alarm, boot, location or foreground-service permission. */
class AutoServiceTimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED)
            AutoServiceScheduler.realign(context)
    }
}
