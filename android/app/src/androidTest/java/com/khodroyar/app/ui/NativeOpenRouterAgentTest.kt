package com.khodroyar.app.ui

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.khodroyar.app.ai.*
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.rates.IncomeCalculator
import com.khodroyar.app.core.rates.Rates
import com.khodroyar.app.core.rates.ServiceType
import com.khodroyar.app.data.db.*
import com.khodroyar.app.data.prefs.AppSettings
import com.khodroyar.app.data.prefs.SettingsStore
import com.khodroyar.app.data.repo.*
import com.khodroyar.app.ui.screens.AiAssistantScreen
import com.khodroyar.app.ui.theme.CarManagerTheme
import com.khodroyar.app.workreport.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Actual Room/Keystore/Compose integration on disposable emulator; ALL OpenRouter replies are mocked. */
@RunWith(AndroidJUnit4::class)
class NativeOpenRouterAgentTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = AppDatabase.get(context)
    private val settings get() = SettingsStore.get(context)
    private lateinit var repo: AppRepository
    private var viewModelStore: ViewModelStore? = null
    private val syntheticKey get() = "sk-or-v1-" + "b".repeat(64)

    @Before fun reset() = runBlocking {
        check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")) { "Synthetic fixture must not reset a real phone" }
        WorkManager.getInstance(context).cancelAllWork().result.get()
        repo = AppRepository(context); repo.quickEntries.recover()
        withContext(Dispatchers.IO) { db.clearAllTables() }
        settings.update { AppSettings(vehicleCurrentKm = 1000.0, autoServicePresetSaved = false,
            remindOilChange = false, remindBackup = false, remindInstallments = false, remindInspection = false,
            remindBodyInsurance = false, remindVehicleInsurance = false) }
        context.getSharedPreferences("khodroyar_ai", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("khodroyar_ai_models", Context.MODE_PRIVATE).edit().clear().commit()
        AiSettingsStore(context).save(AiSettings())
        OpenRouterApiKeyStore(context).clear(); ConversationRepository(context).clearAll()
        File(context.noBackupFilesDir, "work-report").deleteRecursively()
        repo.saveRatesForYear(1404, Rates(nightKm = 1000.0, hour = 2000.0, toll = 5000.0))
    }
    @After fun dispose() {
        compose.runOnIdle { viewModelStore?.clear(); viewModelStore = null }
    }
    private fun call(name: String, args: String): ValidatedCall = (FunctionToolSchemas.validate(FunctionCall("native_call", name, args)) as ValidationResult.Ok).call
    private suspend fun preview(tools: AgentToolRunner, name: String, args: String): PendingAction =
        (AgentApprovalGate(tools).prepare(call(name, args)) as AgentStep.Confirm).pending
    private val explicitBulk = """{"start_date":"1404/01/01","end_date":"1404/12/29","service_type":"night","per_day":1,"days":"all","template_source":"explicit","origin":"مبارکه","destination":"اصفهان","km":60,"hours":3,"toll_count":1}"""

    @Test fun full1404PersianNativePlanShows365ExactDatesAndPricesBeforeAnyDatabaseWrite() = runBlocking {
        val tools = AppAgentTools(context, repo)
        val calendar = tools.execute(call("get_jalali_calendar", """{"year":1404}"""))
        assertTrue(calendar.ok); assertTrue(calendar.text.contains("1404/12/29")); assertTrue(calendar.text.contains("365"))
        val pending = preview(tools, "bulk_register_services", explicitBulk)
        val plan = pending.call.args["service_plan"] as ServiceBatchPlan
        assertEquals(365, plan.changes.size); assertEquals(21900.0, plan.created.sumOf { it.km }, 0.0)
        assertEquals(1095.0, plan.created.sumOf { it.hours }, 0.0); assertEquals(365, plan.created.sumOf { it.tollCount })
        val expected = IncomeCalculator.calculate(ServiceType.NIGHT, 60.0, 3.0, 1, 0.0, 0.0, 0.0, repo.ratesFor(1404))
        assertEquals(365L * expected, plan.created.sumOf { it.income })
        assertTrue(pending.summary.contains("1404/01/01")); assertTrue(pending.summary.contains("1404/12/29")); assertTrue(pending.summary.contains((365L * expected).toString()))
        assertTrue(db.services().allOnce().isEmpty()); assertEquals(1000.0, settings.current.vehicleCurrentKm, 0.0)
        assertTrue(tools.execute(pending.call).ok)
        assertEquals(365, db.services().allOnce().size); assertEquals(22900.0, settings.current.vehicleCurrentKm, 0.0)
        // New runner instance simulates retry after losing the orchestrator. Approved immutable IDs stay durable.
        assertTrue(AppAgentTools(context, AppRepository(context)).execute(pending.call).ok)
        assertEquals(365, db.services().allOnce().size); assertEquals(22900.0, settings.current.vehicleCurrentKm, 0.0)
        assertTrue(AgentApprovalGate(tools).prepare(call("bulk_register_services", explicitBulk)) is AgentStep.Reply)
    }
    @Test fun ordinaryCreatesHaveFixedAppOwnedIdsAndDoNotDuplicateAcrossExecutorInstances() = runBlocking {
        val tools = AppAgentTools(context, repo)
        for ((name, args) in listOf("add_expense" to """{"date":"1404/01/01","amount":45000,"title":"قهوه","category":"coffee"}""",
            "add_fuel" to """{"date":"1404/01/01","liters":20,"total_cost":60000}""",
            "add_repair" to """{"date":"1404/01/01","amount":500000,"description":"تعمیر آزمایشی","km":1000}""",
            "create_reminder" to """{"title":"بیمه آزمایشی","expiry_date":"1406/01/01","cost":100000}""")) {
            val pending = preview(tools, name, args)
            assertTrue(tools.execute(pending.call).ok)
            assertTrue(AppAgentTools(context, AppRepository(context)).execute(pending.call).ok)
        }
        assertEquals(1, db.insights().expensesOn("1404/01/01").size)
        assertEquals(1, repo.fuelsByPrefixOnce("").size); assertEquals(1, repo.maintenancesByPrefixOnce("").size); assertEquals(1, repo.insurance.allOnce().size)
        assertTrue(db.services().allOnce().isEmpty())
    }
    @Test fun singleServiceEditDeleteAndInsuranceUseExactSnapshotsAndReadback() = runBlocking {
        val tools = AppAgentTools(context, repo)
        val create = preview(tools, "add_service", """{"date":"1404/01/01","service_type":"night","origin":"مبارکه","destination":"اصفهان","km":60,"hours":3,"toll_count":1}""")
        assertTrue(db.services().allOnce().isEmpty()); assertTrue(tools.execute(create.call).ok)
        val row = db.services().allOnce().single()
        val edit = preview(tools, "update_service", """{"id":"${row.id}","km":70,"toll_count":2}""")
        assertEquals(row, db.services().byId(row.id)); assertTrue(edit.summary.contains("60.0")); assertTrue(edit.summary.contains("70.0"))
        assertTrue(tools.execute(edit.call).ok)
        assertEquals(70.0, requireNotNull(db.services().byId(row.id)).km, 0.0); assertEquals(1070.0, settings.current.vehicleCurrentKm, 0.0)
        val deletion = preview(tools, "delete_service", """{"id":"${row.id}"}""")
        assertNotNull(deletion.target); assertNotNull(db.services().byId(row.id)); assertTrue(tools.execute(deletion.call).ok)
        assertNull(db.services().byId(row.id)); assertEquals(1000.0, settings.current.vehicleCurrentKm, 0.0)
        val reminder = preview(tools, "create_reminder", """{"title":"بیمه آزمایشی","expiry_date":"1406/01/01"}""")
        tools.execute(reminder.call)
        val insurance = repo.insurance.allOnce().single()
        val update = preview(tools, "update_insurance", """{"id":"${insurance.id}","expiry_date":"1406/02/01","reminder":"off"}""")
        assertTrue(tools.execute(update.call).ok); assertFalse(requireNotNull(repo.insurance.byId(insurance.id)).reminder)
        val stale = preview(tools, "delete_insurance", """{"id":"${insurance.id}"}""")
        repo.insurance.insert(requireNotNull(repo.insurance.byId(insurance.id)).copy(cost = 500.0))
        assertFalse(tools.execute(stale.call).ok); assertNotNull(repo.insurance.byId(insurance.id))
    }
    @Test fun trackedMaintenanceUsesExistingCrashRecoveryJournalAndRejectsStaleSettings() = runBlocking {
        val tools = AppAgentTools(context, repo)
        val pending = preview(tools, "add_car_service", """{"service_type":"oil-change","date":"1404/01/01","km":1000,"amount":200000}""")
        assertTrue(pending.summary.contains("موعد همین نوع")); assertEquals(0.0, settings.current.oilCurrentKm, 0.0)
        settings.update { it.copy(oilNextKm = 12345.0) }
        assertFalse(tools.execute(pending.call).ok); assertTrue(repo.maintenancesByPrefixOnce("").isEmpty())
        val fresh = preview(tools, "add_car_service", """{"service_type":"oil-change","date":"1404/01/01","km":1000,"amount":200000}""")
        assertTrue(tools.execute(fresh.call).ok)
        assertEquals(1000.0, settings.current.oilCurrentKm, 0.0); assertEquals(11000.0, settings.current.oilNextKm, 0.0)
        assertTrue(settings.pendingQuickMileage.isNullOrBlank()); assertTrue(tools.execute(fresh.call).ok)
        assertEquals(1, repo.maintenancesByPrefixOnce("").size)
    }
    @Test fun singleServiceUsesExactlyTheRatesThatWerePricedInItsImmutablePreview() = runBlocking {
        val original = Rates(nightKm = 1000.0, hour = 2000.0)
        val changed = original.copy(hour = 9000.0)
        val reads = AtomicInteger()
        val operations = ServiceBatchOperations(db, settings) { if (reads.incrementAndGet() == 1) original else changed }
        val proposed = (preview(AppAgentTools(context, repo), "add_service",
            """{"date":"1404/01/01","service_type":"night","origin":"مبارکه","destination":"اصفهان","km":60,"hours":3}""").call.args["single_service_plan"] as ServiceBatchPlan).created.single()
        val frozen = operations.prepareSingle(null, proposed)
        assertEquals(1, reads.get()); assertEquals(original.toString(), frozen.rates[1404])
        assertEquals(66000L, frozen.created.single().income)
        assertTrue(runCatching { operations.execute(frozen) }.isFailure)
        assertTrue(db.services().allOnce().isEmpty()); assertEquals(1000.0, settings.current.vehicleCurrentKm, 0.0)
        assertTrue(settings.pendingQuickMileage.isNullOrBlank())
    }
    @Test fun committedServiceWithConcurrentManualMileageIsAnHonestPartialFailureNotFullSuccess() = runBlocking {
        val rates = Rates(nightKm = 1000.0, hour = 2000.0)
        val reads = AtomicInteger()
        // Existing replaceable rate reader deterministically models the concurrent settings edit
        // AFTER the journal is staged, while the approved transaction is still in flight.
        val operations = ServiceBatchOperations(db, settings) {
            if (reads.incrementAndGet() == 2) settings.update { it.copy(vehicleCurrentKm = 9000.0) }
            rates
        }
        val proposed = (preview(AppAgentTools(context, repo), "add_service",
            """{"date":"1404/01/01","service_type":"night","origin":"مبارکه","destination":"اصفهان","km":60,"hours":3}""").call.args["single_service_plan"] as ServiceBatchPlan).created.single()
        val frozen = operations.prepareSingle(null, proposed)
        val error = runCatching { operations.execute(frozen) }.exceptionOrNull()
        assertTrue(error is ServiceBatchVerificationException)
        assertTrue(requireNotNull(error).message.orEmpty().contains("دوباره ثبت نکنید"))
        assertEquals(frozen.created, db.services().allOnce()); assertEquals(9000.0, settings.current.vehicleCurrentKm, 0.0)
        assertNotNull(settings.quickMileageNotice.value); assertTrue(settings.pendingQuickMileage.isNullOrBlank())
        // Even retrying the durable plan cannot reinsert or overwrite the later manual value.
        operations.execute(frozen)
        assertEquals(1, db.services().allOnce().size); assertEquals(9000.0, settings.current.vehicleCurrentKm, 0.0)
        settings.acknowledgeQuickMileageNotice()
    }
    @Test fun companyCountsWithUnknownMetricsRemainReadOnlyAndRawDocumentIsNeverAToolResult() = runBlocking {
        val tools = AppAgentTools(context, repo); val date = "1404/07/05"
        val create = preview(tools, "bulk_register_services", explicitBulk.replace("1404/01/01", date).replace("1404/12/29", date).replace("\"per_day\":1", "\"per_day\":3"))
        tools.execute(create.call)
        val directory = File(context.noBackupFilesDir, "work-report").also { it.mkdirs() }
        val rows = (1..2).map { ReportRow("row$it", "p", 1, it, "PRIVATE ORIGINAL DOCUMENT", date, kind = ReportRowKind.SERVICE, hoursBlank = true) }
        val draft = WorkReportDraft(directory); draft.save(WorkReportDraft.Draft(emptyList(), emptyList(), rows, date, date))
        val before = db.services().allOnce()
        val query = call("compare_company_report", """{"start_date":"$date","end_date":"$date"}""")
        val comparison = tools.execute(query)
        assertTrue(comparison.ok); assertTrue(comparison.text.contains("شرکت 2 / برنامه 3 / اختلاف 1")); assertFalse(comparison.text.contains("PRIVATE ORIGINAL"))
        draft.save(requireNotNull(draft.load()).copy(companyCounts = mapOf(date to 4)))
        assertTrue(tools.execute(query).text.contains("شرکت 4 / برنامه 3 / اختلاف -1"))
        val optional = call("compare_company_report", """{"start_date":"$date","end_date":"$date","detail":"metrics"}""")
        assertTrue(tools.execute(optional).text.contains("جمع تعداد بالا مستقل"))
        draft.save(requireNotNull(draft.load()).copy(rows = rows.map { it.copy(count = "1", km = "60", hours = "") }))
        val sparse = tools.execute(optional)
        assertTrue(sparse.ok); assertTrue(sparse.text.contains("اختلاف ساعت نامشخص است، نه صفر"))
        draft.save(requireNotNull(draft.load()).copy(rows = rows.map { it.copy(count = "1", km = "60", hours = "3") }))
        val detailed = tools.execute(optional)
        assertTrue(detailed.ok); assertTrue(detailed.text.contains("تطبیق مبهم")); assertFalse(detailed.text.contains("PRIVATE ORIGINAL"))
        assertEquals(before, db.services().allOnce())
    }
    @Test fun financialComparisonAndPriceExplanationReuseStoredIncomeAndNeverRepriceTheDatabase() = runBlocking {
        val tools = AppAgentTools(context, repo)
        val pending = preview(tools, "add_service", """{"date":"1404/01/01","service_type":"night","origin":"مبارکه","destination":"اصفهان","km":60,"hours":3}""")
        tools.execute(pending.call)
        val row = db.services().allOnce().single()
        val expense = preview(tools, "add_expense", """{"date":"1404/01/01","amount":10000,"title":"قهوه"}"""); tools.execute(expense.call)
        val comparison = tools.execute(call("compare_financial_periods", """{"start_date":"1404/01/01","end_date":"1404/01/31","previous_start":"1403/12/01","previous_end":"1403/12/30"}"""))
        assertTrue(comparison.ok); assertTrue(comparison.text.contains("هم‌طول نیستند")); assertTrue(comparison.text.contains("${row.income.toDouble() - 10000}"))
        repo.saveRatesForYear(1404, Rates(nightKm = 2000.0, hour = 4000.0))
        val explained = tools.execute(call("explain_service_price", """{"id":"${row.id}"}"""))
        assertTrue(explained.ok); assertTrue(explained.text.contains("درآمدی که واقعاً ثبت شده: ${row.income}")); assertTrue(explained.text.contains("132000"))
        assertEquals(row, db.services().byId(row.id))
    }
    @Test fun keystoreEncryptionMigrationFreshConsentAndKeyDeletionDoNotTouchRecords() = runBlocking {
        val prefs = context.getSharedPreferences("khodroyar_ai", Context.MODE_PRIVATE)
        prefs.edit().putString("mode", "GAPGPT_DIRECT").putBoolean("consented", true).putBoolean("openrouter_consented_v1", true).commit()
        assertEquals(AiMode.OPENROUTER, AiSettingsStore(context).load().mode); assertFalse(AiSettingsStore(context).load().consented)
        prefs.edit().putString("mode", "LOCAL").commit(); assertEquals(AiMode.LOCAL, AiSettingsStore(context).load().mode)
        val aiSettings = AiSettingsStore(context)
        aiSettings.save(AiSettings(consented = true)); assertTrue(aiSettings.load().consented)
        aiSettings.revokeOpenRouterConsent(); assertFalse(aiSettings.load().consented)
        assertFalse(prefs.getBoolean("openrouter_consented_v2", true))
        val keys = OpenRouterApiKeyStore(context); keys.save(syntheticKey); assertEquals(syntheticKey, keys.read())
        val xml = File(context.applicationInfo.dataDir, "shared_prefs/khodroyar_openrouter_credentials.xml").readText()
        assertFalse(xml.contains(syntheticKey)); assertFalse(xml.contains("api_key"))
        val sample = PersonalExpenseEntity("expense-test", "1404/01/01", "coffee", "قهوه", 10.0, "synthetic")
        repo.applyApprovedAgentRecord(null, sample); keys.clear(); assertNull(keys.read()); assertEquals(sample, repo.insights.expense(sample.id))
    }
    private val free = "synthetic/persian:free"
    private fun catalog(): String {
        fun model(id: String) = JSONObject().put("id", id).put("context_length", 131072).put("pricing", JSONObject().put("prompt", "0").put("completion", "0"))
            .put("supported_parameters", JSONArray(listOf("tools", "tool_choice", "max_tokens")))
        return JSONObject().put("data", JSONArray().put(model("openrouter/free")).put(model(free))).toString()
    }
    private fun completion(content: String, calls: List<FunctionCall> = emptyList()): String = JSONObject().put("model", free)
        .put("choices", JSONArray().put(JSONObject().put("finish_reason", if (calls.isEmpty()) "stop" else "tool_calls")
            .put("message", OpenRouterWire.messageJson(FunctionMessage("assistant", content, calls))))).toString()
    private fun endpointEvidence(modelCatalog: String = catalog(), id: String = free): String {
        val rows = JSONObject(modelCatalog).getJSONArray("data")
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("id") == id }
        return JSONObject().put("data", JSONObject().put("id", id).put("endpoints", JSONArray().put(row
            .put("status", 0).put("provider_name", "Synthetic provider").put("uptime_last_30m", 100)))).toString()
    }
    private fun screenshot(name: String, tag: String = "ai-agent-screen") {
        compose.waitForIdle()
        val image = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val output = java.io.ByteArrayOutputStream()
        image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); image.recycle()
        publishSyntheticEvidence(context, "KhodroYar-UI-Smoke", name + ".png", "image/png", output.toByteArray())
    }
    private fun renderChat(http: AiHttpTransport, consented: Boolean = false, toolRunner: AgentToolRunner? = null,
        modelCatalog: String = catalog(), approvedIds: List<String> = listOf(free)): AiAssistantViewModel {
        AiSettingsStore(context).save(AiSettings(consented = consented))
        val receipts = AndroidModelEvaluationStore(context)
        val models = OpenRouterModelMetadata.parse(modelCatalog, modelCatalog, System.currentTimeMillis()).first
        approvedIds.forEachIndexed { index, id ->
            val m = models.single { it.key.id == id }
            receipts.save("initial", AIModelEvaluation(m.key, m.fingerprint, System.currentTimeMillis(), (1..4).map { AIModelTestCase("Synthetic seeded test $it", true) }, 100 - index * 5))
        }
        val wrapped = object : AiHttpTransport, AiStreamingTransport {
            override fun execute(request: AiHttpRequest): AiHttpResponse = if (request.url.endsWith("/endpoints")) {
                val id = request.url.removePrefix(OpenRouterFreePolicy.MODELS_URL + "/").removeSuffix("/endpoints")
                AiHttpResponse(200, endpointEvidence(modelCatalog, id))
            } else http.execute(request)
            override fun stream(request: AiHttpRequest, onEvent: (String) -> Unit): AiHttpResponse =
                if (http is AiStreamingTransport) http.stream(request, onEvent) else http.execute(request)
        }
        OpenRouterApiKeyStore(context).save(syntheticKey)
        val application = context.applicationContext as Application
        lateinit var vm: AiAssistantViewModel
        compose.runOnIdle {
            val store = ViewModelStore().also { viewModelStore = it }
            vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = AiAssistantViewModel(application, wrapped, toolRunner) as T
            })[AiAssistantViewModel::class.java]
        }
        compose.setContent { CarManagerTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, LocalDensity provides Density(density.density, 1.25f)) {
                Surface(Modifier.width(320.dp)) { AiAssistantScreen(vm, onNavigate = {}) }
            }
        } }
        compose.waitUntil(10000) { vm.hasOpenRouterKey }
        return vm
    }
    private fun send(text: String) {
        compose.onNodeWithTag("ai-message-input").performTextReplacement(text)
        compose.onNodeWithContentDescription("ارسال").performClick()
    }
    @Test fun rtlTextChatStreamsNativeToolsWaitsForButtonAndPreservesRealHistory() = runBlocking {
        val postCount = AtomicInteger(); val requests = mutableListOf<AiHttpRequest>()
        val http = object : AiHttpTransport, AiStreamingTransport {
            override fun execute(request: AiHttpRequest): AiHttpResponse { synchronized(requests) { requests += request }; return AiHttpResponse(200, catalog()) }
            override fun stream(request: AiHttpRequest, onEvent: (String) -> Unit): AiHttpResponse {
                synchronized(requests) { requests += request }
                return if (postCount.incrementAndGet() == 1) AiHttpResponse(200, completion("پیش‌نمایش هزینه", listOf(FunctionCall("expense_call", "add_expense", """{"date":"1404/01/01","amount":45000,"category":"coffee","title":"قهوه"}"""))))
                    else {
                        fun chunk(delta: JSONObject, finish: String? = null) = JSONObject().put("model", free).put("choices", JSONArray().put(JSONObject().put("index", 0)
                            .put("delta", delta).put("finish_reason", finish ?: JSONObject.NULL))).toString()
                        onEvent(chunk(JSONObject().put("content", "هزینه پس از تأیید ")))
                        onEvent(chunk(JSONObject().put("content", "در دیتابیس بازخوانی شد."), "stop")); onEvent("[DONE]")
                        AiHttpResponse(200, "")
                    }
            }
        }
        val vm = renderChat(http)
        assertTrue(requests.isEmpty()) // Opening the UI never tests/sends without consent.
        send("یه هزینه قهوه ۴۵ تومن برای اول فروردین ۱۴۰۴ بزن، بعد نتیجه‌شو بگو")
        compose.waitUntil(5000) { vm.needsConsent }; assertEquals(0, postCount.get())
        compose.onNodeWithText("موافقم و ادامه").performClick()
        compose.waitUntil(10000) { vm.pending != null && !vm.busy }
        assertTrue(db.insights().expensesOn("1404/01/01").isEmpty())
        compose.onNodeWithTag("ai-message-input").assertIsNotEnabled()
        compose.onNodeWithTag("agent-confirm-action").performScrollTo().assertIsDisplayed()
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        val image = compose.onNodeWithTag("ai-agent-screen").captureToImage().asAndroidBitmap()
        val output = java.io.ByteArrayOutputStream(); image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); image.recycle()
        publishSyntheticEvidence(context, "KhodroYar-UI-Smoke", "38-openrouter-approval-320-large-font.png", "image/png", output.toByteArray())
        // Exercise the real card hit target, not a direct ViewModel confirmation.
        compose.onNodeWithTag("agent-confirm-action").performClick()
        compose.waitUntil(10000) { !vm.busy && vm.pending == null && vm.messages.any { it.role == ChatRole.ASSISTANT && it.text.contains("بازخوانی شد") } }
        assertEquals(1, db.insights().expensesOn("1404/01/01").size); assertEquals(2, postCount.get())
        val lastRequest = synchronized(requests) { requests.last { it.method == "POST" } }
        val body = JSONObject(requireNotNull(lastRequest.body))
        assertEquals(free, body.getString("model")); assertFalse(body.has("models")); assertFalse(body.has("plugins"))
        assertTrue(body.getJSONArray("messages").toString().contains("\"tool_call_id\":\"expense_call\"")); assertFalse(lastRequest.body.orEmpty().contains(syntheticKey))
        compose.waitUntil(10000) { runBlocking { ConversationRepository(context).load().any { c -> c.messages.any { it.role == ChatRole.RESULT } } } }
        assertTrue(ConversationRepository(context).load().flatMap { it.messages }.any { it.text.contains("قهوه") })
        compose.onNodeWithTag("ai-options").performClick()
        compose.onNodeWithText("گفتگوی نو").performClick()
        compose.onNodeWithText("شروع گفتگوی جدید").performClick()
        compose.waitUntil(5000) { vm.messages.isEmpty() }
        compose.onNodeWithTag("ai-options").performClick()
        compose.onNodeWithText("تاریخچه").performClick()
        compose.waitUntil(5000) { vm.history.size == 1 }
        compose.onNodeWithContentDescription("حذف گفتگو").performClick()
        compose.waitUntil(10000) { runBlocking { ConversationRepository(context).load().isEmpty() } }
        compose.onNodeWithText("هنوز گفتگویی ذخیره نشده است.").assertIsDisplayed()
        assertEquals(1, db.insights().expensesOn("1404/01/01").size); assertEquals(2, postCount.get())
        val evidence = JSONObject().put("environment", "Synthetic OpenRouter HTTP and SSE; NOT live model/account")
            .put("passed", true).put("native_tool_calls", true).put("no_request_before_consent", true).put("posts", postCount.get())
            .put("records_after_approval", 1).put("paid_fallback", false).put("sse_completed", true).put("rtl_width_dp", 320).put("font_scale", 1.25)
            .put("confirm_button_clicked", true).put("history_deleted_records_preserved", true)
        publishSyntheticEvidence(context, "KhodroYar-UI-Smoke", "openrouter-agent.json", "application/json", evidence.toString().toByteArray())
    }
    @Test fun realCreateCancelDoubleTapAndDestructiveCancelConfirmNeverRunUnapprovedQueuedWrites() = runBlocking {
        val posts = AtomicInteger()
        val args = """{"date":"1404/01/01","service_type":"night","origin":"مبارکه","destination":"اصفهان","km":60,"hours":3,"toll_count":1}"""
        val http = AiHttpTransport { request ->
            if (request.method == "GET") AiHttpResponse(200, catalog()) else {
                val stage = posts.incrementAndGet()
                val calls = when (stage) {
                    1 -> listOf(FunctionCall("cancel_create", "add_service", args), FunctionCall("never_run", "add_expense", """{"amount":10000,"title":"نباید ثبت شود"}"""))
                    2 -> listOf(FunctionCall("approved_create", "add_service", args))
                    4 -> listOf(FunctionCall("search_actual", "search_services_range", """{"start_date":"1404/01/01","end_date":"1404/01/01","service_type":"night"}"""))
                    5, 6 -> listOf(FunctionCall("delete_$stage", "delete_service", """{"id":"${runBlocking { db.services().allOnce().single().id }}"}"""))
                    else -> emptyList()
                }
                assertTrue(stage in 1..7)
                AiHttpResponse(200, completion(if (calls.isEmpty()) "نتیجهٔ ابزار واقعی را بررسی کنید." else "پیشنهاد برای تأیید", calls))
            }
        }
        val vm = renderChat(http, consented = true)
        send("یه سرویس شب اول فروردین ۱۴۰۴ با مسیر مبارکه به اصفهان و ۶۰ کیلومتر و ۳ ساعت و یک عوارض ثبت کن")
        compose.waitUntil(10000) { vm.pending != null && !vm.busy }
        assertTrue(db.services().allOnce().isEmpty())
        compose.onNodeWithTag("agent-cancel-action").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5000) { vm.pending == null }
        assertEquals(1, posts.get()); assertTrue(db.services().allOnce().isEmpty()); assertTrue(db.insights().expensesOn(Jalali.todayString()).isEmpty())
        send("همون سرویس آزمایشی رو دوباره پیشنهاد بده؛ فقط سرویس، نه هزینه")
        compose.waitUntil(10000) { vm.pending != null && !vm.busy }
        compose.onNodeWithTag("agent-confirm-action").performScrollTo().assertIsDisplayed().performTouchInput { doubleClick() }
        compose.waitUntil(10000) { !vm.busy && vm.pending == null && posts.get() == 3 }
        val saved = db.services().allOnce().single()
        assertEquals(1060.0, settings.current.vehicleCurrentKm, 0.0)
        assertTrue(db.insights().expensesOn(Jalali.todayString()).isEmpty())
        send("همین سرویس اول فروردین رو پیدا کن و حذفش رو پیشنهاد بده")
        compose.waitUntil(10000) { vm.pending?.destructive == true && !vm.busy }
        compose.onNodeWithText("تأیید حذف (غیرقابل بازگشت)").assertExists()
        assertEquals(saved, db.services().byId(saved.id))
        compose.onNodeWithTag("agent-cancel-action").performScrollTo().performClick()
        compose.waitUntil(5000) { vm.pending == null }
        assertEquals(saved, db.services().byId(saved.id)); assertEquals(5, posts.get())
        send("حذف همون سرویس واقعی رو دوباره برای تأیید نشون بده")
        compose.waitUntil(10000) { vm.pending?.destructive == true && !vm.busy }
        assertEquals(saved, db.services().byId(saved.id))
        compose.onNodeWithTag("agent-confirm-action").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10000) { !vm.busy && vm.pending == null && posts.get() == 7 }
        assertTrue(db.services().allOnce().isEmpty()); assertEquals(1000.0, settings.current.vehicleCurrentKm, 0.0)
        assertTrue(db.insights().expensesOn(Jalali.todayString()).isEmpty())
        compose.onNodeWithTag("agent-confirm-action").assertDoesNotExist()
        val evidence = JSONObject().put("environment", "Synthetic OpenRouter; real Room and approval-card taps; NOT live account")
            .put("passed", true).put("posts", posts.get()).put("cancelled_create_unchanged", true).put("cancelled_delete_unchanged", true)
            .put("double_tap_created_once", true).put("unapproved_queue_discarded", true).put("confirmed_delete_readback", true).put("mileage_restored", true)
        publishSyntheticEvidence(context, "KhodroYar-UI-Smoke", "openrouter-approvals.json", "application/json", evidence.toString().toByteArray())
    }
    @Test fun aDeviceLocalApprovalOnAnEmptyChatStillShowsTheRealConfirmAndCancelCard() {
        val executed = AtomicInteger()
        val tools = object : AgentToolRunner {
            override suspend fun execute(call: ValidatedCall): ToolOutcome { executed.incrementAndGet(); return ToolOutcome(true, "عملیات مصنوعی تأیید شد") }
            override fun describe(call: ValidatedCall) = "توقف قانون آزمایشی؛ سوابق تغییر نمی‌کنند."
            override suspend fun describeTarget(call: ValidatedCall): String? = null
        }
        val vm = renderChat(AiHttpTransport { error("Local approval must not use any network") }, toolRunner = tools)
        assertTrue(vm.messages.isEmpty())
        compose.runOnIdle { vm.setAutomation("abcd1234", false) }
        compose.waitUntil(5000) { vm.pending != null && !vm.busy }
        compose.onNodeWithTag("agent-action-summary").assertExists()
        assertEquals(0, executed.get())
        compose.onNodeWithTag("agent-cancel-action").performScrollTo().performClick()
        assertEquals(0, executed.get())
        compose.runOnIdle { vm.newChat(); vm.setAutomation("abcd1234", false) }
        compose.waitUntil(5000) { vm.pending != null && !vm.busy }
        compose.onNodeWithTag("agent-confirm-action").performScrollTo().performClick()
        compose.waitUntil(5000) { !vm.busy && vm.pending == null }
        assertEquals(1, executed.get())
    }
    @Test fun fullScreenChatHasOnlyCompactToolbarAndOptionsStayBehindGearWithManualApprovedSelection() {
        val vm = renderChat(AiHttpTransport { request ->
            check(request.method == "GET") { "Settings refresh must not infer or benchmark" }
            AiHttpResponse(200, catalog())
        }, consented = true)
        compose.onNodeWithText("گفتگوی نو").assertDoesNotExist()
        compose.onNodeWithText("بخش‌های برنامه").assertDoesNotExist()
        compose.onNodeWithText("کارهای خودکار · 0").assertDoesNotExist()
        compose.onNodeWithTag("ai-options").performClick()
        compose.onNodeWithText("تنظیمات هوش مصنوعی و مدل‌ها").performClick()
        compose.waitUntil(10000) { vm.modelState.models.any { it.approved } && !vm.modelState.checking }
        compose.onNodeWithTag("ai-selection-MANUAL").performScrollTo().performClick()
        // The refreshed evidence arrives AFTER the dialog-open clock; the option must be ready
        // immediately, without waiting for a ten-second freshness tick or weakening TTL checks.
        compose.onNodeWithTag("ai-manual-model-picker").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("ai-manual-option-$free").assertIsDisplayed().performClick()
        screenshot("40-model-management-320", "ai-settings")
        compose.onNodeWithText("ذخیره", useUnmergedTree = false).performClick()
        compose.waitUntil(5000) { vm.settings.modelSelectionMode == AISelectionMode.MANUAL }
        assertEquals(free, vm.settings.openRouterModel)
        assertTrue(vm.settings.consented)
    }
    @Test fun realSafeModelEvaluationUsesOnlyDummyDataAndGuardrailRemovalStopsWithoutAnotherPost() = runBlocking {
        val privateRecord = PersonalExpenseEntity("private-test", "1404/01/01", "coffee", "PRIVATE-USER-DATA-DO-NOT-SEND", 9876543.0, "private-note")
        repo.applyApprovedAgentRecord(null, privateRecord)
        AiSettingsStore(context).save(AiSettings(consented = true)); OpenRouterApiKeyStore(context).save(syntheticKey)
        var authorized = true
        val posts = AtomicInteger(); val bodies = mutableListOf<String>()
        val http = AiHttpTransport { request ->
            when {
                request.url.endsWith("/endpoints") -> AiHttpResponse(200, endpointEvidence())
                request.method == "GET" -> AiHttpResponse(200, if (request.url == OpenRouterModelMetadata.USER_MODELS_URL && !authorized) "{\"data\":[]}" else catalog())
                else -> {
                    posts.incrementAndGet(); val body = requireNotNull(request.body); bodies += body
                    assertFalse(body.contains(privateRecord.title)); assertFalse(body.contains("9876543")); assertFalse(body.contains(syntheticKey))
                    assertTrue(body.contains(SafePersianModelEvaluator.MARKER))
                    val messages = JSONObject(body).getJSONArray("messages")
                    val text = (0 until messages.length()).map { messages.getJSONObject(it) }.last { it.getString("role") == "user" }.getString("content")
                    val (name, args) = when {
                        "مرحله۱" in text -> "get_jalali_calendar" to """{"year":1404}"""
                        "مرحله۲" in text -> "search_services_range" to """{"start_date":"1404/01/01","end_date":"1404/12/29","service_type":"night"}"""
                        "مرحله۳" in text -> "benchmark_report" to """{"income":540000,"expense":180000,"net":360000,"count":2,"days":365,"summary":"خالص دادهٔ ساختگی محاسبه شد"}"""
                        else -> "benchmark_refusal" to """{"decision":"needs_confirmation","summary":"بدون تأیید هیچ ثبت یا حذفی مجاز نیست"}"""
                    }
                    AiHttpResponse(200, completion("", listOf(FunctionCall("bench_${posts.get()}", name, args))))
                }
            }
        }
        val settingsStore = AiSettingsStore(context)
        val adapter = OpenRouterModelAdapter(OpenRouterClient(settingsStore::load, OpenRouterApiKeyStore(context), http))
        val manager = ManagedAIProvider(adapter, { AISelectionSettings(true, true, "initial") }, AndroidModelEvaluationStore(context))
        manager.refresh(runTests = true)
        assertEquals(4, posts.get()); assertEquals(free, manager.state.value.active?.id)
        assertTrue(manager.state.value.models.single { it.model.key.id == free }.approved)
        assertEquals(privateRecord, repo.insights.expense(privateRecord.id)); assertTrue(db.services().allOnce().isEmpty())
        authorized = false; manager.refresh()
        assertNull(manager.state.value.active); assertEquals(4, posts.get())
        assertFalse(manager.state.value.models.any { it.approved })
        val evidence = JSONObject().put("environment", "Synthetic official catalog/guardrail and four inference cases; real local Room; NOT live OpenRouter")
            .put("passed", true).put("dummy_test_posts", 4).put("private_record_unchanged", true).put("private_data_not_sent", true)
            .put("guardrail_removal_sent_no_post", true).put("no_real_tool_execution", true)
        publishSyntheticEvidence(context, "KhodroYar-UI-Smoke", "openrouter-model-evaluation.json", "application/json", evidence.toString().toByteArray())
    }
    @Test fun modelReplacementAfterAnApprovedWriteContinuesTranscriptWithoutReplayingTheCommittedRecord() = runBlocking {
        val alternative = "synthetic/alternative:free"
        val source = JSONObject(catalog()).apply { getJSONArray("data").put(JSONObject(getJSONArray("data").getJSONObject(1).toString()).put("id", alternative)) }.toString()
        val posts = AtomicInteger(); val bodies = mutableListOf<String>()
        val releaseAlternative = java.util.concurrent.CountDownLatch(1)
        val http = object : AiHttpTransport, AiStreamingTransport {
            override fun execute(request: AiHttpRequest): AiHttpResponse { check(request.method == "GET"); return AiHttpResponse(200, source) }
            override fun stream(request: AiHttpRequest, onEvent: (String) -> Unit): AiHttpResponse {
                val body = requireNotNull(request.body); bodies += body
                return when (posts.incrementAndGet()) {
                    1 -> AiHttpResponse(200, completion("پیشنهاد هزینه", listOf(FunctionCall("once_only", "add_expense", """{"date":"1404/01/01","amount":45000,"title":"آزمون جایگزینی","category":"coffee"}"""))))
                    2 -> {
                        onEvent(JSONObject().put("model", free).put("choices", JSONArray().put(JSONObject().put("index", 0)
                            .put("delta", JSONObject().put("content", "متن ناقص مدل قبلی نباید بماند")).put("finish_reason", JSONObject.NULL))).toString())
                        AiHttpResponse(503, "synthetic unavailable")
                    }
                    3 -> {
                        check(releaseAlternative.await(20, java.util.concurrent.TimeUnit.SECONDS)) { "UI must observe the cleared partial response" }
                        AiHttpResponse(200, JSONObject(completion("نتیجهٔ ابزار تأییدشده را دریافت کردم؛ ثبت دوباره نمی‌کنم.")).put("model", alternative).toString())
                    }
                    else -> error("Inference must not loop or replay")
                }
            }
        }
        val vm = renderChat(http, consented = true, modelCatalog = source, approvedIds = listOf(free, alternative))
        send("یک هزینهٔ آزمایشی ۴۵ هزار تومانی ثبت کن و بعد نتیجه را بگو")
        compose.waitUntil(10000) { vm.pending != null && !vm.busy }
        val screenHeight = compose.onNodeWithTag("ai-agent-screen").fetchSemanticsNode().boundsInRoot.height
        assertTrue(compose.onNodeWithTag("ai-chat-list").fetchSemanticsNode().boundsInRoot.height > screenHeight * 0.60f)
        compose.onNodeWithTag("agent-confirm-action").performScrollTo().performClick()
        try {
            compose.waitUntil(10000) { posts.get() == 3 && vm.modelState.active?.id == alternative && vm.streamingText.isEmpty() }
            compose.onNodeWithText("متن ناقص مدل قبلی نباید بماند").assertDoesNotExist()
        } finally { releaseAlternative.countDown() }
        compose.waitUntil(10000) { !vm.busy && vm.pending == null && posts.get() == 3 }
        assertNull(vm.failure); assertEquals(alternative, vm.modelState.active?.id)
        assertEquals(1, db.insights().expensesOn("1404/01/01").size)
        assertTrue(bodies.last().contains("\"tool_call_id\":\"once_only\""))
        assertEquals(free, JSONObject(bodies[0]).getString("model")); assertEquals(alternative, JSONObject(bodies[2]).getString("model"))
        screenshot("39-ai-fullscreen-320")
        val evidence = JSONObject().put("environment", "Synthetic OpenRouter; real Compose confirmation and Room; NOT live provider")
            .put("passed", true).put("posts", 3).put("committed_expenses", 1).put("approved_alternative_only", true)
            .put("tool_transcript_preserved", true).put("committed_write_not_replayed", true).put("chat_height_over_60_percent", true)
            .put("partial_stream_cleared", true)
        publishSyntheticEvidence(context, "KhodroYar-UI-Smoke", "openrouter-model-failover.json", "application/json", evidence.toString().toByteArray())
    }
    @Test fun modelReceiptsAndDailyBudgetPersistAcrossInstancesButNeverAcrossAccountScopes() {
        val settings = AiSettingsStore(context); settings.save(AiSettings(consented = true))
        val now = 1_800_000_000_000L
        val store = AndroidModelEvaluationStore(context)
        val model = OpenRouterModelMetadata.parse(catalog(), catalog(), now).first.single { it.key.id == free }
        store.save("initial", AIModelEvaluation(model.key, model.fingerprint, now, (1..4).map { AIModelTestCase("dummy$it", true) }, 95))
        store.setActive("initial", model.key)
        repeat(8) { assertTrue(store.reserve("initial", now, automatic = true)) }
        assertFalse(AndroidModelEvaluationStore(context).reserve("initial", now, automatic = true))
        repeat(8) { assertTrue(AndroidModelEvaluationStore(context).reserve("initial", now, automatic = false)) }
        assertFalse(store.reserve("initial", now, automatic = false))
        assertFalse(store.reserve("initial", now - 86_400_000L, automatic = false))
        assertTrue(store.reserve("initial", now + 86_400_000L, automatic = true))
        val restarted = AndroidModelEvaluationStore(context)
        assertTrue(restarted.load("initial").single().passed); assertEquals(model.key, restarted.active("initial"))
        settings.save(settings.load().copy(modelAccountScope = "rotated-account"))
        assertTrue(restarted.load("rotated-account").isEmpty()); assertNull(restarted.active("rotated-account"))
        assertFalse(restarted.reserve("initial", now + 86_400_000L, automatic = false))
        assertTrue(restarted.reserve("rotated-account", now + 86_400_000L, automatic = true))
    }
    @Test fun nativeAndroidJsonCannotCoerceNonstandardSyntaxOrRoundFractionalIntegerArguments() {
        for (args in listOf("{month:1}", "{'month':1}", "{\"month\":1,}", "{\"month\":1,\"month\":2}",
            "{\"month\":1.0000000001}", "{\"month\":1.0000000000000000001}", "{\"month\":true}", "{\"month\":null}")) {
            assertTrue(args, FunctionToolSchemas.validate(FunctionCall("validate", "get_monthly_report", args)) is ValidationResult.Invalid)
        }
    }
    @Test fun nativeAndroidCannotUnderflowAnyPositivePriceOrReportedCostIntoFreeZero() {
        val verified = OpenRouterFreePolicy.catalog(catalog())
        val underflow = catalog().replace("\"prompt\":\"0\"", "\"prompt\":1e-1000")
        assertTrue(OpenRouterFreePolicy.catalog(underflow).isEmpty())
        val priced = completion("متن مصنوعی").dropLast(1) + ",\"usage\":{\"cost\":1e-1000}}"
        assertTrue(runCatching { OpenRouterWire.completion(priced, "openrouter/free", verified) }.isFailure)
        val zero = completion("متن مصنوعی").dropLast(1) + ",\"usage\":{\"cost\":0.0}}"
        assertEquals("متن مصنوعی", OpenRouterWire.completion(zero, "openrouter/free", verified).message.content)
    }
    @Test fun historyRedactsCredentialsInTitlesMessagesAndFilesAcrossRepositoryInstances() = runBlocking {
        val entry = ChatEntry("redaction", ChatRole.ASSISTANT, "پاسخ مصنوعی $syntheticKey پایان", 1L)
        val conversation = Conversation("redaction", "عنوان $syntheticKey", 1L, 2L, listOf(entry), AiMode.OPENROUTER)
        ConversationRepository(context).save(conversation)
        val loaded = ConversationRepository(context).load().single()
        assertFalse(loaded.title.contains(syntheticKey)); assertFalse(loaded.messages.single().text.contains(syntheticKey))
        val file = File(context.filesDir, "ai_conversations.json")
        assertFalse(file.readText().contains(syntheticKey)); assertFalse(File(file.parentFile, file.name + ".tmp").exists())
        ConversationRepository(context).clearAll(); assertTrue(ConversationRepository(context).load().isEmpty())
        assertFalse(file.exists())
    }
}
