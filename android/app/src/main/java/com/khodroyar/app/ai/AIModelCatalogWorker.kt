package com.khodroyar.app.ai

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Six-hour, constrained metadata refresh only. No inference, evaluation, conversation or DB access. */
class AIModelCatalogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = AiSettingsStore(applicationContext)
        val settings = store.load()
        if (!settings.enabled || settings.mode != AiMode.OPENROUTER || !settings.consented) return Result.success()
        val usage = AndroidAIUsageStore(applicationContext)
        // Even an expired 429 cooldown is not permission for an automatic metadata retry.
        if (!usage.read().permitsBackgroundMetadata(settings.modelAccountScope)) return Result.success()
        try {
            val client = OpenRouterClient(store::load, OpenRouterApiKeyStore(applicationContext), runtime = AIRequestRuntime(OpenRouterHttp(), { store.load().usage }, { store.load().modelAccountScope }, usage))
            val adapter = OpenRouterModelAdapter(client)
            // Discovery includes both public pricing and the key-scoped /models/user guardrail view.
            when (val found = adapter.discover(force = true)) {
                is AIModelDiscovery.Found -> {
                    AIModelCatalogEpoch.value.incrementAndGet()
                    val receipts = AndroidModelEvaluationStore(applicationContext)
                    val lastActive = receipts.active(settings.modelAccountScope)
                    val selected = found.models.firstOrNull { it.key == lastActive && AIRequestPolicy.eligibleMetadata(it, System.currentTimeMillis()) }
                    if (selected != null) {
                        if (!adapter.availability(selected, force = true).available) receipts.setActive(settings.modelAccountScope, null)
                    } else receipts.setActive(settings.modelAccountScope, null)
                }
                is AIModelDiscovery.Failed -> { AIModelCatalogEpoch.value.incrementAndGet() } // No rapid retry, no paid/other endpoint fallback.
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* No raw exception or sensitive request is logged. Next periodic refresh can try metadata again. */ }
        return Result.success()
    }
}
object AIModelCatalogScheduler {
    const val WORK_NAME = "khodroyar_ai_model_metadata"
    fun sync(context: Context) {
        val settings = AiSettingsStore(context).load()
        val work = WorkManager.getInstance(context)
        if (!settings.enabled || settings.mode != AiMode.OPENROUTER || !settings.consented) { work.cancelUniqueWork(WORK_NAME); return }
        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AIModelCatalogWorker>(AIModelHealthMonitor.BACKGROUND_INTERVAL_HOURS, TimeUnit.HOURS)
                .setInitialDelay(AIModelHealthMonitor.BACKGROUND_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()).build())
    }
}
