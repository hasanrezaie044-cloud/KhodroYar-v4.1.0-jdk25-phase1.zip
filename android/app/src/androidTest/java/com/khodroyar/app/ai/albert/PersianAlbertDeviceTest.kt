package com.khodroyar.app.ai.albert

import android.content.ContentValues
import android.os.Debug
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.khodroyar.app.ai.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real shipped tokenizer + ONNX Runtime CPU execution. No mock model or external network. */
class PersianAlbertDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixtures() = context.assets.open("albert/parity-fixtures.json").bufferedReader().use { JSONArray(it.readText()) }

    @Test fun nativeTokenizerMatchesBuildTimeSentencePieceForEveryHoldoutAndValidationInput() {
        val rows = fixtures()
        assertTrue(rows.length() >= 80)
        SentencePieceBridge(context.assets.open("albert/spiece.model").use { it.readBytes() }).use { sp ->
            for (j in 0 until rows.length()) {
                val row = rows.getJSONObject(j)
                val normalized = LocalIntentParser.normalize(row.getString("text"))
                assertEquals(row.getString("normalized"), normalized)
                val pieces = sp.encode(normalized)
                val ids = IntArray(64)
                ids[0] = 2
                pieces.copyInto(ids, 1)
                ids[pieces.size + 1] = 3
                val expected = row.getJSONArray("ids")
                assertArrayEquals(row.getString("text"), IntArray(64) { expected.getInt(it) }, ids)
            }
        }
    }

    @Test fun realAlbertClassifiesHeldoutDomainsAndReleasesItsSession() = runBlocking {
        val engine = PersianAlbertEngine(context)
        val pssBefore = Debug.getPss()
        val start = SystemClock.elapsedRealtime()
        try {
            val rows = fixtures()
            val sample = (0 until rows.length()).map { rows.getJSONObject(it) }
                .groupBy { it.getString("label") }.values.flatMap { group -> group.sortedByDescending { it.getDouble("score") }.take(2) }
            assertEquals(8, sample.map { it.getString("label") }.toSet().size)
            val timings = JSONArray()
            for (row in sample) {
                val p = engine.predict(row.getString("text"))
                assertNotNull("Model must really run, not pass via a rule fallback", p)
                assertEquals(PersianAlbertEngine.Status.READY, engine.status)
                assertEquals(row.getString("text"), row.getString("label"), p!!.intent.wire)
                assertEquals(row.getDouble("score"), p.score, .08)
                timings.put(JSONObject().put("label", p.intent.wire).put("inferenceMs", p.inferenceMs))
            }
            assertNull(engine.predict("کلمه ".repeat(110))) // under 600 chars, over 64 tokens: abstain, never truncate
            val pssLoaded = Debug.getPss()
            engine.releaseMemory()
            assertEquals(PersianAlbertEngine.Status.NOT_LOADED, engine.status)
            val report = JSONObject().put("environment", "API35 x86_64 debug emulator; not a phone or release benchmark")
                .put("sourceRevision", "2315c0d4ddd6bd68f7703c5651978706bf8aff37")
                .put("actualNativePredictions", sample.size).put("tokenizerParityExamples", rows.length())
                .put("totalWithLoadMs", SystemClock.elapsedRealtime() - start)
                .put("processPssBeforeKb", pssBefore).put("processPssLoadedKb", pssLoaded)
                .put("processPssAfterReleaseKb", Debug.getPss()).put("warmPredictions", timings)
            evidence("albert-emulator.json", report.toString())
        } finally { engine.releaseMemory() }
    }

    @Test fun realModelStillCannotExecuteANegatedOrUnapprovedWrite() = runBlocking {
        val engine = PersianAlbertEngine(context)
        val calls = mutableListOf<ValidatedCall>()
        val tools = object : AgentToolRunner {
            override suspend fun execute(call: ValidatedCall): ToolOutcome { calls += call; return ToolOutcome(true, "actual app tool") }
            override fun describe(call: ValidatedCall) = "review ${call.spec.name}"
            override suspend fun describeTarget(call: ValidatedCall): String? = "target"
        }
        try {
            val local = LocalAssistant(tools, model = engine)
            assertTrue(local.run("نمیخوام سفر امروز تو دفتر باشه") is AgentStep.Reply)
            assertTrue(calls.isEmpty())
            local.dialogue.reset()
            assertTrue(local.run("پول قهوه امروز پنجاه هزار تومان رو یادداشت کن") is AgentStep.Confirm)
            assertEquals(PersianAlbertEngine.Status.READY, engine.status)
            assertTrue(calls.isEmpty())
        } finally { engine.releaseMemory() }
    }

    private fun evidence(name: String, data: String) {
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/KhodroYar-UI-Smoke"); put(MediaStore.Downloads.IS_PENDING, 1)
        }))
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(data.toByteArray()) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    }
}
