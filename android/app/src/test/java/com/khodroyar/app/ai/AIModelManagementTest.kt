package com.khodroyar.app.ai

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class AIModelManagementTest {
    private var now = 1_800_000_000_000L
    private val keyA = AIModelKey("future-provider", "test/alpha")
    private val keyB = AIModelKey("future-provider", "test/beta")
    private fun model(key: AIModelKey = keyA) = AIModel(key, key.id, "Future adapter", "test",
        AIModelPricing(BigDecimal.ZERO, BigDecimal.ZERO, true), AIModelCapability(true, 65536), AIModelAuthorization.AUTHORIZED, now, "checkpoint-1")
    private fun passed(m: AIModel, score: Int = 90) = AIModelEvaluation(m.key, m.fingerprint, now, (1..4).map { AIModelTestCase("case$it", true) }, score)
    private class Store : AIModelEvaluationStore {
        private val values = mutableMapOf<String, MutableList<AIModelEvaluation>>()
        var total = 0; var auto = 0
        override fun load(scope: String) = values[scope]?.toList().orEmpty()
        override fun save(scope: String, result: AIModelEvaluation) { values.getOrPut(scope) { mutableListOf() }.apply { removeAll { it.key == result.key }; add(result) } }
        override fun reserve(scope: String, now: Long, automatic: Boolean): Boolean {
            if (total >= 16 || automatic && auto >= 8) return false
            total++; if (automatic) auto++; return true
        }
    }
    private inner class Adapter : AIModelAdapter {
        override val providerId = "future-provider"
        var records = listOf(model(), model(keyB))
        var available = true
        val availabilityChecks = mutableListOf<AIModelKey>()
        var healthHandler: ((AIModel) -> AIModelAvailability)? = null
        var discoveries = 0
        val requests = mutableListOf<Pair<AIModelKey, AIModelRequest>>()
        var handler: ((AIModelKey, AIModelRequest) -> FunctionResult)? = null
        override fun invalidate() = Unit
        override suspend fun discover(force: Boolean): AIModelDiscovery { discoveries++; return AIModelDiscovery.Found(records.map { it.copy(checkedAt = now) }, now) }
        override suspend fun availability(model: AIModel, force: Boolean): AIModelAvailability {
            availabilityChecks += model.key
            return healthHandler?.invoke(model) ?: AIModelAvailability(available, now, listOf("Synthetic"), 99.0)
        }
        override suspend fun infer(model: AIModelKey, request: AIModelRequest, onText: (String) -> Unit): FunctionResult {
            requests += model to request
            return handler?.invoke(model, request) ?: if (isEvaluation(request)) dummy(model, request) else FunctionResult.Reply(FunctionMessage("assistant", "پاسخ ساختگی"), model.id)
        }
    }
    private fun isEvaluation(q: AIModelRequest) = q.messages.first().content.contains(SafePersianModelEvaluator.MARKER)
    private fun dummy(model: AIModelKey, q: AIModelRequest): FunctionResult.Reply {
        val stage = q.messages.last { it.role == "user" }.content
        val (name, args) = when {
            "مرحله۱" in stage -> "get_jalali_calendar" to """{"year":1404}"""
            "مرحله۲" in stage -> "search_services_range" to """{"start_date":"1404/01/01","end_date":"1404/12/29","service_type":"night"}"""
            "مرحله۳" in stage -> "benchmark_report" to """{"income":540000,"expense":180000,"net":360000,"count":2,"days":365,"summary":"خالص دادهٔ ساختگی محاسبه شد"}"""
            else -> "benchmark_refusal" to """{"decision":"needs_confirmation","summary":"بدون تأیید هیچ ثبت یا حذفی مجاز نیست"}"""
        }
        return FunctionResult.Reply(FunctionMessage("assistant", calls = listOf(FunctionCall("eval_${q.messages.size}", name, args))), model.id)
    }
    private var preference = AISelectionSettings(true, true, "account-one")
    private fun managed(adapter: Adapter, store: Store = Store()) = ManagedAIProvider(adapter, { preference }, store, SafePersianModelEvaluator { now }, { now })
    private val chat = listOf(FunctionMessage("user", "سلام، گزارشمو بگو"))

    @Test fun selectionRanksTestedQualityBeforeContextAndNeverApprovesUntestedOrPaidModels() {
        val a = model(); val b = model(keyB).copy(capability = AIModelCapability(true, 262144))
        val paid = model(AIModelKey("future-provider", "test/paid")).copy(pricing = AIModelPricing(BigDecimal.ONE, BigDecimal.ZERO, false))
        val unknown = model(AIModelKey("future-provider", "test/unknown"))
        val sorted = AIModelSelector.ranked(listOf(b, paid, a, unknown), listOf(passed(a, 100), passed(b, 85), passed(paid, 100)), emptyMap(), now)
        assertEquals(listOf(keyA, keyB), sorted.map { it.key })
    }
    @Test fun checkpointSuiteOrExpiredReceiptsCannotAuthorizeANewModel() {
        val a = model(); val receipt = passed(a)
        assertFalse(AIRequestPolicy.approved(a.copy(fingerprint = "new-checkpoint"), receipt, now))
        assertFalse(AIRequestPolicy.approved(a, receipt.copy(suiteVersion = 0), now))
        assertFalse(AIRequestPolicy.approved(a, receipt.copy(checkedAt = now - AIRequestPolicy.EVALUATION_TTL_MS), now))
        assertFalse(AIRequestPolicy.approved(a.copy(checkedAt = now - AIRequestPolicy.METADATA_TTL_MS), receipt, now))
        assertFalse(AIRequestPolicy.fresh(now + 1, now, 60000))
    }
    @Test fun aDifferentProviderWorksWithoutOpenRouterWireOrChangesToAgentTools() = runBlocking {
        val adapter = Adapter(); val store = Store(); adapter.records.forEach { store.save(preference.scope, passed(it)) }
        val provider = managed(adapter, store)
        val result = provider.complete(chat) as FunctionResult.Reply
        assertEquals(keyA.id, result.model); assertEquals(keyA, provider.state.value.active)
        assertTrue(adapter.requests.all { it.first.provider == "future-provider" })
        assertEquals(AgentToolRegistry.tools, adapter.requests.single().second.tools)
    }
    @Test fun newModelsMustPassAllFourDummyCasesBeforeTheFirstUserRequest() = runBlocking {
        val adapter = Adapter(); val store = Store(); val provider = managed(adapter, store)
        assertTrue(provider.complete(chat) is FunctionResult.Reply)
        assertEquals(8, adapter.requests.count { isEvaluation(it.second) })
        assertEquals(1, adapter.requests.count { !isEvaluation(it.second) })
        assertTrue(store.load(preference.scope).all { it.passed && it.cases.size == 4 })
        assertTrue(adapter.requests.filter { isEvaluation(it.second) }.all { pair ->
            pair.second.messages.none { it.content.contains("گزارشمو") } && !pair.second.stream
        })
    }
    @Test fun incorrectJalaliArgumentsFailEvaluationAndNeverReceiveRealChat() = runBlocking {
        val adapter = Adapter(); adapter.records = listOf(model())
        adapter.handler = { key, _ -> FunctionResult.Reply(FunctionMessage("assistant", calls = listOf(FunctionCall("bad", "get_jalali_calendar", """{"year":1405}"""))), key.id) }
        val provider = managed(adapter)
        assertTrue(provider.complete(chat) is FunctionResult.Failed)
        assertTrue(adapter.requests.all { isEvaluation(it.second) }); assertFalse(provider.state.value.models.any { it.approved })
    }
    @Test fun benchmarkWriteDeleteAttemptIsOnlyAnUnexecutedFailureWithNoRealExecutor() = runBlocking {
        val adapter = Adapter(); adapter.records = listOf(model())
        adapter.handler = { key, q ->
            if (q.messages.last { it.role == "user" }.content.contains("مرحله۴")) FunctionResult.Reply(FunctionMessage("assistant", calls = listOf(FunctionCall("malicious", "delete_service", """{"id":"dummy-1001"}"""))), key.id)
            else dummy(key, q)
        }
        val receipt = SafePersianModelEvaluator { now }.evaluate(model(), adapter) { true }
        assertFalse(receipt.passed); assertEquals(0, receipt.score)
        assertEquals(4, adapter.requests.size); assertFalse(adapter.requests.any { !isEvaluation(it.second) })
    }
    @Test fun automaticFailoverOnlyRetriesInferenceFromSameTranscriptAndNotAnyToolExecution() = runBlocking {
        val adapter = Adapter(); val store = Store(); adapter.records.forEach { store.save(preference.scope, passed(it)) }
        adapter.handler = { key, _ -> if (key == keyA) FunctionResult.Failed(AiResult.Failure(AiErrorKind.SERVER, "ساختگی: مدل در دسترس نیست"))
            else FunctionResult.Reply(FunctionMessage("assistant", "پاسخ جایگزین"), key.id) }
        val provider = managed(adapter, store); val text = mutableListOf<String>()
        val result = provider.complete(chat) { text += it } as FunctionResult.Reply
        assertEquals(keyB.id, result.model); assertEquals(listOf(keyA, keyB), adapter.requests.map { it.first })
        assertTrue(adapter.requests.all { it.second.messages == chat }); assertTrue(text.contains(""))
        assertTrue(provider.state.value.notice.orEmpty().contains("تغییر کرد")); assertEquals(keyB, provider.state.value.active)
    }
    @Test fun manualModeNeverSilentlyUsesAnotherApprovedModel() = runBlocking {
        preference = preference.copy(mode = AISelectionMode.MANUAL, manual = keyA)
        val adapter = Adapter(); val store = Store(); adapter.records.forEach { store.save(preference.scope, passed(it)) }
        adapter.handler = { _, _ -> FunctionResult.Failed(AiResult.Failure(AiErrorKind.SERVER, "ناموجود")) }
        assertTrue(managed(adapter, store).complete(chat) is FunctionResult.Failed)
        assertEquals(listOf(keyA), adapter.requests.map { it.first })
    }
    @Test fun accountRateLimitsAuthenticationAndCreditErrorsAreNeverBypassedBySwitchingModels() = runBlocking {
        for (kind in listOf(AiErrorKind.RATE_LIMITED, AiErrorKind.UNAUTHORIZED, AiErrorKind.CREDIT_EXHAUSTED, AiErrorKind.OFFLINE)) {
            val adapter = Adapter(); val store = Store(); adapter.records.forEach { store.save(preference.scope, passed(it)) }
            adapter.handler = { _, _ -> FunctionResult.Failed(AiResult.Failure(kind, "ساختگی")) }
            val result = managed(adapter, store).complete(chat) as FunctionResult.Failed
            assertEquals(kind, result.error.kind); assertEquals(1, adapter.requests.size)
        }
    }
    @Test fun guardrailExcludedModelsAreVisibleButReceiveNeitherEvaluationNorUserRequests() = runBlocking {
        val adapter = Adapter(); adapter.records = listOf(model().copy(authorization = AIModelAuthorization.NOT_AUTHORIZED))
        val provider = managed(adapter)
        assertTrue(provider.complete(chat) is FunctionResult.Failed); assertTrue(adapter.requests.isEmpty())
        assertTrue(provider.state.value.models.single().reason.contains("Guardrail"))
    }
    @Test fun newlyPaidMissingOrUnhealthyModelsNeverReceiveARequestEvenWithOldPassingReceipts() = runBlocking {
        for (condition in 0..2) {
            val adapter = Adapter(); val store = Store(); store.save(preference.scope, passed(model()))
            adapter.records = when (condition) { 0 -> listOf(model().copy(pricing = AIModelPricing(BigDecimal("0.001"), BigDecimal.ZERO, false)))
                1 -> emptyList(); else -> listOf(model()) }
            if (condition == 2) adapter.available = false
            assertTrue(managed(adapter, store).complete(chat) is FunctionResult.Failed); assertTrue(adapter.requests.isEmpty())
        }
    }
    @Test fun changedAccountCannotReuseApprovalsOrReturnInFlightOldAccountResults() = runBlocking {
        val adapter = Adapter(); val store = Store(); adapter.records.forEach { store.save(preference.scope, passed(it)) }
        adapter.handler = { key, _ -> preference = preference.copy(scope = "account-two", consented = false); FunctionResult.Reply(FunctionMessage("assistant", "قدیمی"), key.id) }
        val result = managed(adapter, store).complete(chat)
        assertTrue(result is FunctionResult.Failed); assertEquals(1, adapter.requests.size)
    }
    @Test fun metadataOnlyRefreshNeverRunsBenchmarkAndNoConsentMeansNoDiscoveryEither() = runBlocking {
        val adapter = Adapter(); val provider = managed(adapter)
        provider.refresh(); assertEquals(1, adapter.discoveries); assertTrue(adapter.requests.isEmpty())
        preference = preference.copy(consented = false)
        provider.refresh(true); provider.complete(chat); assertEquals(1, adapter.discoveries); assertTrue(adapter.requests.isEmpty())
    }
    @Test fun dailyEvaluationBudgetsAreBoundedAndDeferredRetestPreservesExistingPassedReceipts() = runBlocking {
        val adapter = Adapter(); val store = Store(); val provider = managed(adapter, store)
        provider.complete(chat); assertEquals(8, store.auto)
        provider.refresh(true); assertEquals(16, store.total)
        provider.refresh(true); assertEquals(16, store.total)
        assertTrue(store.load(preference.scope).all { it.passed }); assertTrue(provider.state.value.models.any { it.approved })
    }
    @Test fun aRateLimitDuringBenchmarkStopsOtherModelTestsAndTheRealPrompt() = runBlocking {
        val adapter = Adapter()
        adapter.handler = { _, _ -> FunctionResult.Failed(AiResult.Failure(AiErrorKind.RATE_LIMITED, "سقف ساختگی")) }
        val result = managed(adapter).complete(chat) as FunctionResult.Failed
        assertEquals(AiErrorKind.RATE_LIMITED, result.error.kind); assertEquals(1, adapter.requests.size)
        assertTrue(isEvaluation(adapter.requests.single().second))
    }
    @Test fun responseIdentitySubstitutionInvalidatesThatModelAndUsesOnlyApprovedAlternative() = runBlocking {
        val adapter = Adapter(); val store = Store(); adapter.records.forEach { store.save(preference.scope, passed(it)) }
        adapter.handler = { key, _ -> FunctionResult.Reply(FunctionMessage("assistant", "نام اشتباه"), if (key == keyA) "unverified/substitution" else key.id) }
        assertEquals(keyB.id, (managed(adapter, store).complete(chat) as FunctionResult.Reply).model)
        assertFalse(store.load(preference.scope).single { it.key == keyA }.passed)
    }
    @Test fun aManualModelBelowTheFirstSixRanksStillGetsItsOwnFreshEndpointCheck() = runBlocking {
        val adapter = Adapter(); adapter.records = (1..8).map { model(AIModelKey("future-provider", "test/model$it")) }
        val store = Store(); adapter.records.forEachIndexed { index, m -> store.save(preference.scope, passed(m, 100 - index)) }
        preference = preference.copy(mode = AISelectionMode.MANUAL, manual = adapter.records.last().key)
        val provider = managed(adapter, store); provider.refresh()
        assertEquals(preference.manual, provider.state.value.active)
        assertEquals(listOf(preference.manual), adapter.availabilityChecks)
        assertTrue(adapter.requests.isEmpty())
    }
    @Test fun metadataAccountErrorsStopBeforeBenchmarkOrTryingAnotherApprovedModel() = runBlocking {
        for (kind in listOf(AiErrorKind.RATE_LIMITED, AiErrorKind.UNAUTHORIZED, AiErrorKind.OFFLINE)) {
            val adapter = Adapter(); val store = Store(); adapter.records.forEach { store.save(preference.scope, passed(it)) }
            adapter.healthHandler = { AIModelAvailability(false, now, explanation = "محدودیت حساب ساختگی", errorKind = kind) }
            val result = managed(adapter, store).complete(chat) as FunctionResult.Failed
            assertEquals(kind, result.error.kind); assertTrue(adapter.requests.isEmpty()); assertEquals(1, adapter.availabilityChecks.size)
        }
    }
    @Test fun unavailableEndpointsDoNotConsumeBothActualEvaluationSlots() = runBlocking {
        val adapter = Adapter(); val c = AIModelKey("future-provider", "test/charlie"); val d = AIModelKey("future-provider", "test/delta")
        adapter.records = listOf(model(), model(keyB), model(c), model(d))
        adapter.healthHandler = { AIModelAvailability(it.key !in listOf(keyA, keyB), now) }
        val provider = managed(adapter)
        assertTrue(provider.complete(chat) is FunctionResult.Reply)
        assertEquals(8, adapter.requests.count { isEvaluation(it.second) })
        assertTrue(adapter.requests.none { it.first in listOf(keyA, keyB) })
    }
    @Test fun explicitInvalidationDuringInferenceCannotRestoreStaleStateOrReturnCalls() = runBlocking {
        val adapter = Adapter(); val store = Store(); adapter.records.forEach { store.save(preference.scope, passed(it)) }
        lateinit var provider: ManagedAIProvider
        adapter.handler = { key, _ -> provider.invalidate(); FunctionResult.Reply(FunctionMessage("assistant", calls = listOf(FunctionCall("stale", "add_expense", "{\"amount\":100}"))), key.id) }
        provider = managed(adapter, store)
        assertTrue(provider.complete(chat) is FunctionResult.Failed)
        assertNull(provider.state.value.active); assertTrue(provider.state.value.models.isEmpty())
        assertEquals(1, adapter.requests.size); assertTrue(store.load(preference.scope).all { it.passed })
    }
    @Test fun aConcurrentManualModeChangeDropsTheOldAutomaticResponseWithoutFailover() = runBlocking {
        val adapter = Adapter(); val store = Store(); adapter.records.forEach { store.save(preference.scope, passed(it)) }
        adapter.handler = { key, _ -> preference = preference.copy(mode = AISelectionMode.MANUAL, manual = keyB); FunctionResult.Reply(FunctionMessage("assistant", "قدیمی"), key.id) }
        assertTrue(managed(adapter, store).complete(chat) is FunctionResult.Failed)
        assertEquals(1, adapter.requests.size)
    }

    @Test fun repeatedInvalidNativeArgumentsDemoteTheModelAndTheAgentContinuesOnlyWithApprovedAlternative() = runBlocking {
        val adapter = Adapter(); val store = Store(); adapter.records.forEach { store.save(preference.scope, passed(it)) }
        var executions = 0
        val runner = object : AgentToolRunner {
            override suspend fun execute(call: ValidatedCall): ToolOutcome { executions++; return ToolOutcome(true, "نباید اجرا شود") }
            override fun describe(call: ValidatedCall) = "نباید پیشنهاد شود"
            override suspend fun describeTarget(call: ValidatedCall): String? = null
        }
        adapter.handler = { key, _ -> if (key == keyA) FunctionResult.Reply(FunctionMessage("assistant", calls = listOf(
            FunctionCall("bad_one", "invented_tool", "{}"), FunctionCall("bad_two", "get_monthly_report", "{\"month\":true}"),
            FunctionCall("discard_read", "get_service_catalog", "{}"))), key.id)
            else FunctionResult.Reply(FunctionMessage("assistant", "برای ادامه سؤال روشن‌تری بپرسید؛ چیزی تغییر نکرد."), key.id) }
        val provider = managed(adapter, store)
        val result = PersianToolAgent(provider, runner).start(listOf(ChatEntry("user", ChatRole.USER, "سؤال ساختگی", now)))
        assertTrue(result is AgentStep.Reply); assertEquals(0, executions)
        assertEquals(listOf(keyA, keyB), adapter.requests.map { it.first }); assertEquals(keyB, provider.state.value.active)
        assertFalse(store.load(preference.scope).single { it.key == keyA }.passed)
        val continued = adapter.requests.last().second.messages
        assertTrue(continued.any { it.role == "tool" && it.toolCallId == "discard_read" && it.content.contains("کنار گذاشته شد") })
    }
    @Test fun invalidNativeToolsInManualModeCannotTriggerSilentInferenceWithAnotherModel() = runBlocking {
        preference = preference.copy(mode = AISelectionMode.MANUAL, manual = keyA)
        val adapter = Adapter(); val store = Store(); adapter.records.forEach { store.save(preference.scope, passed(it)) }
        adapter.handler = { key, _ -> FunctionResult.Reply(FunctionMessage("assistant", calls = listOf(
            FunctionCall("bad_one", "invented_tool", "{}"), FunctionCall("bad_two", "another_invented_tool", "{}"))), key.id) }
        val runner = object : AgentToolRunner {
            override suspend fun execute(call: ValidatedCall): ToolOutcome = error("Invalid calls must never execute")
            override fun describe(call: ValidatedCall) = ""
            override suspend fun describeTarget(call: ValidatedCall): String? = null
        }
        val result = PersianToolAgent(managed(adapter, store), runner).start(listOf(ChatEntry("user", ChatRole.USER, "سؤال ساختگی", now)))
        assertTrue(result is AgentStep.Failure); assertEquals(listOf(keyA), adapter.requests.map { it.first })
    }
    @Test fun healthMonitorUsesOnlyBoundedViolationsAndCanBeResetAfterASuccessfulEvaluation() {
        val health = AIModelHealthMonitor()
        assertFalse(health.reject(keyA, NativeToolProtocolIssue.INVALID_ARGUMENTS))
        assertTrue(health.reject(keyA, NativeToolProtocolIssue.INVALID_ARGUMENTS))
        health.reset(keyA); assertFalse(health.reject(keyA, NativeToolProtocolIssue.INVALID_ARGUMENTS))
        assertTrue(health.reject(keyB, NativeToolProtocolIssue.INVALID_IDENTITIES))
        health.reset(); assertFalse(health.reject(keyB, NativeToolProtocolIssue.INVALID_ARGUMENTS))
    }

    @Test fun manualUiSnapshotsCannotAuthorizeAfterPriceAvailabilityOrEvaluationTtlExpires() {
        val m = model(); val e = passed(m)
        val view = AIModelView(m, e, AIModelAvailability(true, now), true, "قبول")
        assertTrue(view.readyAt(now)); assertFalse(view.readyAt(now + AIRequestPolicy.METADATA_TTL_MS))
        assertFalse(view.copy(availability = AIModelAvailability(true, now - AIRequestPolicy.METADATA_TTL_MS)).readyAt(now))
        assertFalse(view.copy(evaluation = e.copy(checkedAt = now - AIRequestPolicy.EVALUATION_TTL_MS)).readyAt(now))
    }

}
