package com.khodroyar.app.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.khodroyar.app.data.prefs.SettingsStore
import java.util.concurrent.TimeUnit

object TrafficCheckScheduler {
    const val WORK_NAME = "car_manager_traffic_check"

    fun sync(context: Context) {
        val s = SettingsStore.get(context).current
        if (!s.trafficNotifyEnabled || s.neshanApiKey.isBlank()) {
            cancel(context)
            return
        }
        schedule(context)
    }

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<TrafficCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag(WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
