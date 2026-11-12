package com.khodroyar.app.reconciliation

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ReconciliationDraft(val pages: List<ImportedStatementPage>, val rows: List<StatementRow>, val from: String = "", val to: String = "")

/** Separate private draft; cannot open, migrate or write car_management.db. */
class ReconciliationDraftStore(context: Context) {
    private val directory = File(context.filesDir, "reconciliation").apply { mkdirs() }
    private val file = AtomicFile(File(directory, "draft.json"))
    fun load(): ReconciliationDraft {
        if (!file.baseFile.exists()) return ReconciliationDraft(emptyList(), emptyList())
        val json = JSONObject(file.openRead().use { it.readBytesBounded(12 * 1024 * 1024).toString(Charsets.UTF_8) })
        val pages = json.getJSONArray("pages")
        require(pages.length() <= 40)
        val imported = (0 until pages.length()).map { i ->
            val p = pages.getJSONObject(i)
            val id = p.getString("id")
            require(Regex("[a-f0-9-]{36}").matches(id))
            require(File(directory, "$id.jpg").isFile) { "تصویر صفحه در پیش‌نویس موجود نیست" }
            ImportedStatementPage(id, "$id.jpg", p.optInt("rotation"), p.optDouble("deskew", 0.0).toFloat(), p.optBoolean("enhance"), p.optBoolean("reviewed"), p.optBoolean("processed"))
        }
        val rows = json.getJSONArray("rows")
        require(rows.length() <= 40000)
        val parsed = (0 until rows.length()).map { i ->
            val r = rows.getJSONObject(i); val b = r.optJSONObject("area")
            StatementRow(r.getString("id"), r.getString("pageId"), r.optString("date"), r.optString("km"), r.optString("hours"), r.optString("count", "1"), r.optString("raw"), r.optInt("confidence"),
                b?.let { RowArea(it.getInt("l"), it.getInt("t"), it.getInt("r"), it.getInt("b")) },
                StatementRowKind.valueOf(r.getString("kind")), r.optBoolean("confirmed"), r.optBoolean("excluded"), r.optBoolean("keepDuplicate"))
        }
        require(parsed.all { row -> imported.any { it.id == row.pageId } })
        return ReconciliationDraft(imported, parsed, json.optString("from"), json.optString("to"))
    }
    fun save(draft: ReconciliationDraft) {
        val json = JSONObject().put("format", 1).put("from", draft.from).put("to", draft.to)
        json.put("pages", JSONArray().apply { draft.pages.forEach { p -> put(JSONObject().put("id", p.id).put("rotation", p.rotation).put("deskew", p.deskew.toDouble()).put("enhance", p.enhance).put("reviewed", p.reviewed).put("processed", p.processed)) } })
        json.put("rows", JSONArray().apply { draft.rows.forEach { r -> put(JSONObject().put("id", r.id).put("pageId", r.pageId).put("date", r.date).put("km", r.kilometers).put("hours", r.hours).put("count", r.count).put("raw", r.rawText).put("confidence", r.confidence).put("kind", r.kind.name).put("confirmed", r.confirmed).put("excluded", r.excluded).put("keepDuplicate", r.keepDuplicate).apply {
            r.area?.let { put("area", JSONObject().put("l", it.left).put("t", it.top).put("r", it.right).put("b", it.bottom)) }
        }) } })
        val stream = file.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
