package com.khodroyar.app.data.backup

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.khodroyar.app.ai.AgentOperation
import com.khodroyar.app.ai.AgentScratchpad
import com.khodroyar.app.ai.AgentTask
import com.khodroyar.app.ai.AgentTaskStatus
import com.khodroyar.app.ai.AiMode
import com.khodroyar.app.ai.AssistantAgentTools
import com.khodroyar.app.ai.ChatEntry
import com.khodroyar.app.ai.ChatRole
import com.khodroyar.app.ai.Conversation
import com.khodroyar.app.ai.ConversationRepository
import com.khodroyar.app.ai.ProviderApiKeyStore
import com.khodroyar.app.automation.AutomationRule
import com.khodroyar.app.automation.AutomationStore
import com.khodroyar.app.automation.RepeatFrequency
import com.khodroyar.app.automation.RepeatSchedule
import com.khodroyar.app.automation.RuleKind
import com.khodroyar.app.core.rates.Rates
import com.khodroyar.app.data.db.RecordAttachmentEntity
import com.khodroyar.app.data.attachments.Attachments
import com.khodroyar.app.data.db.AppDatabase
import com.khodroyar.app.data.db.InsuranceEntity
import com.khodroyar.app.data.db.MaintenanceEntity
import com.khodroyar.app.data.db.ServiceEntity
import com.khodroyar.app.data.legacy.toEntity
import com.khodroyar.app.data.prefs.AppSettings
import com.khodroyar.app.data.prefs.SettingsStore
import com.khodroyar.app.data.repo.AppRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Emulator integration: complete restore, attachment bytes, integrity rejection, and secret retention. */
@RunWith(AndroidJUnit4::class)
class BackupIntegrityIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = AppDatabase.get(context)
    private val settings get() = SettingsStore.get(context)
    private lateinit var repository: AppRepository
    private val createdFiles = mutableListOf<File>()
    private var testInitialized = false

    @Before fun reset(): Unit = runBlocking {
        check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")) {
            "This integration test clears only its disposable emulator database."
        }
        repository = AppRepository(context)
        db.clearAllTables()
        settings.update { AppSettings(vehicleCurrentKm = 12345.0, isRental = true, neshanApiKey = TEST_KEY) }
        testInitialized = true
    }

    @After fun cleanup(): Unit = runBlocking {
        if (!testInitialized) return@runBlocking
        db.clearAllTables()
        createdFiles.forEach { it.delete() }
        settings.update { AppSettings() }
        testInitialized = false
    }

    @Test fun fullBackupRestoresInsuranceAllYearsAndRealAttachmentBytesWithoutExportingSecrets() = runBlocking {
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x4b, 0x59, 0x2d, 0x50, 0x48, 0x4f, 0x54, 0x4f, 0xff.toByte(), 0xd9.toByte())
        val attachment = File(Attachments.dir(context), "backup-test-${UUID.randomUUID()}.jpg").apply {
            writeBytes(bytes)
            createdFiles += this
        }
        val service = ServiceEntity(
            id = "backup-service-${UUID.randomUUID()}", date = "1404/04/12", type = "night", carType = "soren",
            km = 42.0, hours = 1.5, workHours = 0.0, startTime = "", endTime = "", origin = "تهران",
            destination = "قم", passengers = "", requestNumber = "", tollCount = 0, missionFood = 0.0,
            missionToll = 0.0, missionFine = 0.0, income = 70000, timestamp = "2025-07-03T10:00:00Z",
            attachmentPath = attachment.absolutePath,
        )
        val maintenance = MaintenanceEntity(
            id = "backup-maintenance-${UUID.randomUUID()}", date = "1404/04/13", type = "repair", typeText = "",
            cost = 550000.0, km = 42000.0, carType = "soren", description = "تعمیر آزمایشی",
            timestamp = "2025-07-04T10:00:00Z", attachmentPath = attachment.absolutePath,
        )
        db.services().insertAll(listOf(service))
        db.maintenances().insertAll(listOf(maintenance))
        db.insurance().insert(InsuranceEntity(
            id = "backup-insurance-${UUID.randomUUID()}", type = "third-party", expiryDate = "1405/01/01",
            cost = 1200000.0, company = "آزمایشی", policyNumber = "TEST-1404", reminder = true,
            timestamp = "2025-07-05T10:00:00Z",
        ))
        db.rates().upsertAll(listOf(Rates.DEFAULT.toEntity(1404), Rates.DEFAULT.copy(hour = 9876.0).toEntity(1405)))

        val backup = repository.exportBackupJson()
        assertFalse("Device-local paths must not leak into exported JSON", backup.contains(attachment.absolutePath))
        assertFalse("Traffic API key must never be part of a backup", backup.contains(TEST_KEY))
        assertEquals(1, JSONObject(backup).getJSONObject("backupManifest").getInt("attachmentCount"))
        val preview = repository.previewBackup(backup)
        assertTrue(preview.error ?: "", preview.valid)
        assertEquals(1, preview.counts.insurance)
        assertEquals(2, preview.counts.rateYears)

        val corruptedAttachment = JSONObject(backup).apply {
            val data = getJSONObject("attachmentData")
            val id = data.keys().next()
            val encoded = data.getString(id)
            data.put(id, (if (encoded[0] == 'A') "B" else "A") + encoded.drop(1))
        }.toString()
        assertFalse("A corrupt attachment must not be previewed as restorable", repository.previewBackup(corruptedAttachment).valid)

        db.clearAllTables()
        val result = repository.restoreBackup(backup)
        assertTrue(result.exceptionOrNull()?.message ?: "Restore failed", result.isSuccess)
        val restoredService = db.services().allOnce().single()
        val restoredMaintenance = db.maintenances().allOnce().single()
        assertTrue(restoredService.attachmentPath != attachment.absolutePath)
        assertTrue(restoredMaintenance.attachmentPath != attachment.absolutePath)
        assertArrayEquals(bytes, File(restoredService.attachmentPath).readBytes())
        assertArrayEquals(bytes, File(restoredMaintenance.attachmentPath).readBytes())
        createdFiles += File(restoredService.attachmentPath)
        createdFiles += File(restoredMaintenance.attachmentPath)
        assertEquals(1, db.insurance().allOnce().size)
        assertEquals(2, db.rates().allOnce().size)
        assertEquals(12345.0, settings.current.vehicleCurrentKm, 0.0)
        assertTrue(settings.current.isRental)
        assertEquals("1405/01/01", db.insurance().allOnce().single().expiryDate)
        assertEquals(9876.0, requireNotNull(db.rates().allOnce().firstOrNull { it.year == 1405 }).hour, 0.0)
        assertEquals(TEST_KEY, settings.current.neshanApiKey)
    }

    @Test fun missingAttachmentDoesNotBlockBackupAndIsReportedWithoutLeakingItsPath() = runBlocking {
        val missingPath = File(Attachments.dir(context), "missing-${UUID.randomUUID()}.jpg").absolutePath
        val service = ServiceEntity(
            id = "missing-attachment-${UUID.randomUUID()}", date = "1404/04/14", type = "night", carType = "soren",
            km = 10.0, hours = 0.0, workHours = 0.0, startTime = "", endTime = "", origin = "تهران",
            destination = "کرج", passengers = "", requestNumber = "", tollCount = 0, missionFood = 0.0,
            missionToll = 0.0, missionFine = 0.0, income = 10000, timestamp = "2025-07-06T10:00:00Z",
            attachmentPath = missingPath,
        )
        db.services().insertAll(listOf(service))

        val backup = repository.exportBackupJson()
        assertFalse("A stale device path must not be exported", backup.contains(missingPath))
        val root = JSONObject(backup)
        assertEquals(1, root.getJSONObject("backupManifest").getInt("omittedAttachmentCount"))
        assertEquals(1, BackupAttachmentCodec.omittedAttachmentCount(backup))
        assertEquals("", root.getJSONArray("services").getJSONObject(0).getString("attachmentPath"))
        val preview = repository.previewBackup(backup)
        assertTrue(preview.error ?: "", preview.valid)

        db.clearAllTables()
        val restored = repository.restoreBackup(backup)
        assertTrue(restored.exceptionOrNull()?.message ?: "Restore failed", restored.isSuccess)
        assertEquals(service.id, db.services().allOnce().single().id)
        assertEquals("", db.services().allOnce().single().attachmentPath)
    }

    @Test fun exportedBackupStreamsToDownloadsAndReadsBackExactly() = runBlocking {
        val name = "backup-writer-${UUID.randomUUID()}.json"
        val expected = "{\"services\":[],\"message\":\"${"پشتیبان".repeat(32768)}\"}"
        val export = BackupFiles.writeExportFile(context, expected, name)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, BackupFiles.MIME_JSON)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/KhodroYarTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        assertNotNull("Android must create the selected Downloads document", uri)
        try {
            assertTrue("SAF/MediaStore must accept the streamed export", BackupFiles.writeToUri(context, uri!!, export))
            val published = context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            assertEquals("A complete backup must become visible in Downloads", 1, published)
            assertEquals(expected, BackupFiles.readFromUri(context, uri))
        } finally {
            context.contentResolver.delete(uri!!, null, null)
            export.delete()
        }
    }

    @Test fun legacySettingsSplitAcrossSectionsAreAllRestored() = runBlocking {
        val legacy = """{
            "services":[],"fuels":[],"maintenances":[],
            "appearance":{"themeMode":"dark"},
            "goals":{"vehicleCurrentKm":23456.0,"isRental":false},
            "reminderPrefs":{"trafficNotifyEnabled":true}
        }"""
        val result = repository.restoreBackup(legacy)
        assertTrue(result.exceptionOrNull()?.message ?: "Restore failed", result.isSuccess)
        assertEquals("dark", settings.current.themeMode)
        assertEquals(23456.0, settings.current.vehicleCurrentKm, 0.0)
        assertFalse(settings.current.isRental)
        assertTrue(settings.current.trafficNotifyEnabled)
        // Device-only credentials are deliberately not exported or overwritten.
        assertEquals(TEST_KEY, settings.current.neshanApiKey)
    }

    @Test fun malformedModernManifestDoesNotReplaceExistingRows() = runBlocking {
        db.services().insertAll(listOf(ServiceEntity(
            id = "stay-put-service", date = "1404/01/01", type = "night", carType = "soren", km = 1.0,
            hours = 0.0, workHours = 0.0, startTime = "", endTime = "", origin = "", destination = "",
            passengers = "", requestNumber = "", tollCount = 0, missionFood = 0.0, missionToll = 0.0,
            missionFine = 0.0, income = 1, timestamp = "2025-03-21T00:00:00Z",
        )))
        val damaged = """{"services":[],"fuels":[],"maintenances":[],"backupManifest":{"format":"unknown","formatVersion":2,"databaseVersion":6,"payloadSha256":"bad","attachmentCount":0,"attachments":[]},"attachmentData":{}}"""
        assertFalse(repository.restoreBackup(damaged).isSuccess)
        assertEquals("stay-put-service", db.services().allOnce().single().id)
    }

    private suspend fun clearAssistantData() {
        ConversationRepository(context).clearAll()
        AssistantAgentTools.memoryFor(context).clear()
        AssistantAgentTools.tasksFor(context).clear()
        AutomationStore.get(context).change { emptyList() }
    }

    @Test fun assistantDataInstallmentsAndRecordAttachmentsRoundTripWithoutApiKeys() = runBlocking {
        val groqKey = "gsk_" + "k".repeat(48)
        val openRouterKey = "sk-or-v1-" + "b".repeat(64)
        ProviderApiKeyStore(context, AiMode.GROQ).save(groqKey)
        ProviderApiKeyStore(context, AiMode.OPENROUTER).save(openRouterKey)
        try {
            clearAssistantData()
            ConversationRepository(context).save(Conversation("conv-backup", "گفتگوی آزمایشی", 1L, 2L,
                listOf(ChatEntry("m1", ChatRole.USER, "هزینهٔ امروز چقدر بود؟", 1L), ChatEntry("m2", ChatRole.ASSISTANT, "۲۵۰ هزار تومان", 2L)), AiMode.GROQ))
            val fact = requireNotNull(AssistantAgentTools.memoryFor(context).remember("خودروی من سورن است", "vehicle"))
            val task = AgentTask("task-backup-1", "conv-backup", "ثبت هزینه", listOf(AgentScratchpad.Step("ثبت", AgentScratchpad.Status.DONE, "add_expense")),
                listOf(AgentOperation("op-backup-1", "add_expense", true, "ثبت شد", 3L)), AgentTaskStatus.COMPLETED, 1L, 3L)
            assertTrue(AssistantAgentTools.tasksFor(context).upsert(task))
            AutomationStore.get(context).change { listOf(AutomationRule(RULE_ID, RuleKind.TRAFFIC, "ترافیک صبح",
                RepeatSchedule(RepeatFrequency.DAILY, "1404/01/01"), enabled = true)) }
            val loanId = repository.addLoan("وام آزمایشی", 12_000_000.0, 0.0, "1404/01/01", 12)
            val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 3, 4, 0xff.toByte(), 0xd9.toByte())
            val photo = File(File(context.filesDir, "record-attachments").apply { mkdirs() }, "backup-${UUID.randomUUID()}.jpg").apply { writeBytes(bytes); createdFiles += this }
            repository.addAttachment("loan", loanId, RecordAttachmentEntity(UUID.randomUUID().toString(), "", "", photo.absolutePath, "image/jpeg", 5L))

            val backup = repository.exportBackupJson()
            assertFalse("Groq key must never be in a backup", backup.contains(groqKey))
            assertFalse("OpenRouter key must never be in a backup", backup.contains(openRouterKey))
            assertFalse(backup.contains("gsk_")); assertFalse(backup.contains("sk-or-"))
            val preview = repository.previewBackup(backup)
            assertTrue(preview.error ?: "", preview.valid)
            assertEquals(1, preview.counts.conversations); assertEquals(1, preview.counts.memoryFacts)
            assertEquals(1, preview.counts.agentTasks); assertEquals(1, preview.counts.automations)
            assertEquals(12, preview.counts.loanInstallments); assertEquals(1, preview.counts.recordAttachments)

            db.clearAllTables(); clearAssistantData(); photo.delete()
            val restored = repository.restoreBackup(backup)
            assertTrue(restored.exceptionOrNull()?.message ?: "Restore failed", restored.isSuccess)
            assertTrue(restored.getOrThrow().skipped.isEmpty())
            assertEquals("conv-backup", ConversationRepository(context).load().single().id)
            assertEquals(fact.text, AssistantAgentTools.memoryFor(context).all().single().text)
            assertEquals(task.operations, AssistantAgentTools.tasksFor(context).all().single().operations)
            val rule = AutomationStore.get(context).rules.value.single()
            assertEquals(RULE_ID, rule.id); assertFalse("restored automations stay paused", rule.enabled)
            assertEquals(12, db.loanInstallments().forLoanOnce(loanId).size)
            val attachment = db.attachments().allOnce().single()
            assertArrayEquals(bytes, File(attachment.filePath).readBytes())
            createdFiles += File(attachment.filePath)
            // Device keys are neither exported nor overwritten, and the owner is told so.
            assertEquals(groqKey, ProviderApiKeyStore(context, AiMode.GROQ).read())
            assertTrue(AssistantBackup.keyStatusMessage(context).contains("هرگز در پشتیبان نیستند"))

            // Restoring the same backup again replaces, never duplicates.
            assertTrue(repository.restoreBackup(backup).isSuccess)
            assertEquals(1, ConversationRepository(context).load().size)
            assertEquals(1, AssistantAgentTools.memoryFor(context).all().size)
            assertEquals(12, db.loanInstallments().allOnce().size)
        } finally {
            clearAssistantData()
            ProviderApiKeyStore(context, AiMode.GROQ).clear(); ProviderApiKeyStore(context, AiMode.OPENROUTER).clear()
        }
    }

    @Test fun oldBackupWithoutAssistantPartKeepsAssistantDataAndMalformedPartChangesNothing() = runBlocking {
        try {
            clearAssistantData()
            AssistantAgentTools.memoryFor(context).remember("گزارش کوتاه می‌خواهم", "preference")
            val legacy = """{"services":[],"fuels":[],"maintenances":[],"version":"3.0"}"""
            assertTrue(repository.restoreBackup(legacy).isSuccess)
            assertEquals(1, AssistantAgentTools.memoryFor(context).all().size)

            db.services().insertAll(listOf(ServiceEntity(
                id = "keep-service", date = "1404/01/01", type = "night", carType = "soren", km = 1.0,
                hours = 0.0, workHours = 0.0, startTime = "", endTime = "", origin = "", destination = "",
                passengers = "", requestNumber = "", tollCount = 0, missionFood = 0.0, missionToll = 0.0,
                missionFine = 0.0, income = 1, timestamp = "2025-03-21T00:00:00Z",
            )))
            val malformed = """{"services":[],"fuels":[],"maintenances":[],"assistant":{"version":99}}"""
            assertFalse(repository.previewBackup(malformed).valid)
            assertFalse(repository.restoreBackup(malformed).isSuccess)
            assertEquals("keep-service", db.services().allOnce().single().id)
            assertEquals(1, AssistantAgentTools.memoryFor(context).all().size)
        } finally { clearAssistantData() }
    }

    companion object {
        private const val RULE_ID = "6f1d2c3b-4a59-4e8f-9b7a-0c1d2e3f4a5b"
        private const val TEST_KEY = "TEST-NESHAN-API-KEY-NOT-A-REAL-CREDENTIAL"
    }
}
