package com.khodroyar.app.ai.gemma

import com.khodroyar.app.ai.AgentActionValidator
import com.khodroyar.app.core.jalali.Jalali
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses the JSON table the vision pass extracted from a company work-report photo.
 * Everything is treated as untrusted OCR-like input: dates must be valid Jalali dates,
 * numbers go through [AgentActionValidator.normalizeDigits], and anything unreadable is
 * marked — never guessed. Rows that fail structural checks are DROPPED from the data set
 * and reported in [ExtractedReport.warnings] instead of silently fabricating records.
 */
object CompanyReportParser {

    data class Row(
        val index: Int,
        val date: String?,           // validated Jalali YYYY/MM/DD, or null when unreadable
        val origin: String,
        val destination: String,
        val km: Double?,
        val hours: Double?,
        val startTime: String,
        val endTime: String,
        val requestNumber: String,
        val amount: Double?,         // payment printed on the report for this row
        val uncertain: List<String>, // field names the model flagged as unreadable
    ) {
        val isComplete: Boolean get() = date != null && km != null && uncertain.isEmpty()
    }

    data class ExtractedReport(
        val rows: List<Row>,
        val printedTotalKm: Double?,
        val printedTotalHours: Double?,
        val printedTotalAmount: Double?,
        val warnings: List<String>,
    ) {
        val sumKm: Double get() = rows.mapNotNull { it.km }.sum()
        val sumHours: Double get() = rows.mapNotNull { it.hours }.sum()
        val sumAmount: Double get() = rows.mapNotNull { it.amount }.sum()
    }

    sealed interface ParseResult {
        data class Ok(val report: ExtractedReport) : ParseResult
        /** The model output had no usable table at all (bad photo, not a report, ...). */
        data class Empty(val reason: String) : ParseResult
    }

    fun parse(rawModelOutput: String): ParseResult {
        val start = rawModelOutput.indexOf('{')
        val end = rawModelOutput.lastIndexOf('}')
        if (start < 0 || end <= start) return ParseResult.Empty("خروجی مدل ساختار JSON ندارد.")
        val root = try {
            JSONObject(AgentActionValidator.normalizeDigits(rawModelOutput.substring(start, end + 1)))
        } catch (_: Exception) {
            return ParseResult.Empty("خروجی مدل قابل‌خواندن نبود (JSON خراب).")
        }
        val rowsArr: JSONArray = root.optJSONArray("rows")
            ?: return ParseResult.Empty(root.optJSONArray("notes")?.optString(0) ?: "جدولی در تصویر پیدا نشد.")

        val warnings = ArrayList<String>()
        val rows = ArrayList<Row>()
        for (i in 0 until rowsArr.length()) {
            val o = rowsArr.optJSONObject(i) ?: continue
            val uncertain = ArrayList<String>()
            val rowNum = rows.size + 1

            val rawDate = o.optString("date", "").trim()
            val date = normalizeJalaliDate(rawDate)
            if (rawDate.isEmpty() || rawDate == "null") uncertain.add("تاریخ")
            else if (date == null) warnings.add("ردیف $rowNum: تاریخ «$rawDate» نامعتبر است و نادیده گرفته شد.")

            fun num(key: String, label: String): Double? {
                if (!o.has(key) || o.isNull(key)) return null
                val v = AgentActionValidator.normalizeDigits(o.optString(key, "")).trim()
                val d = v.toDoubleOrNull()
                if (d == null) warnings.add("ردیف $rowNum: $label خوانده نشد («$v»)، خالی گذاشته شد.")
                return d
            }
            fun str(key: String): String = o.optString(key, "").takeIf { it.isNotBlank() && it != "null" } ?: ""
            fun time(key: String): String {
                val t = str(key)
                val m = Regex("^(\\d{1,2}):(\\d{2})$").find(t) ?: return ""
                val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
                return if (h in 0..23 && min in 0..59) "%02d:%02d".format(h, min) else ""
            }

            root.optJSONArray("unreadable")?.let { u ->
                for (j in 0 until u.length()) {
                    val mark = u.optString(j)
                    if (mark.contains("$rowNum")) uncertain.add(mark)
                }
            }

            rows.add(
                Row(
                    index = rowNum,
                    date = date,
                    origin = str("origin"),
                    destination = str("destination"),
                    km = num("km", "کیلومتر"),
                    hours = num("hours", "ساعت"),
                    startTime = time("start"),
                    endTime = time("end"),
                    requestNumber = str("request"),
                    amount = num("amount", "مبلغ"),
                    uncertain = uncertain.distinct(),
                ),
            )
        }
        if (rows.isEmpty()) return ParseResult.Empty("هیچ ردیف قابل‌استفاده‌ای از تصویر استخراج نشد.")

        fun total(key: String): Double? {
            if (!root.has(key) || root.isNull(key)) return null
            return AgentActionValidator.normalizeDigits(root.optString(key)).trim().toDoubleOrNull()
        }
        return ParseResult.Ok(
            ExtractedReport(
                rows = rows,
                printedTotalKm = total("total_km"),
                printedTotalHours = total("total_hours"),
                printedTotalAmount = total("total_amount"),
                warnings = warnings,
            ),
        )
    }

    /** Accepts 1405/08/12, 1405-08-12, 1405.08.12 with any digit script; validates against the Jalali calendar. */
    internal fun normalizeJalaliDate(raw: String): String? {
        if (raw.isBlank() || raw == "null") return null
        val s = AgentActionValidator.normalizeDigits(raw).trim().replace('-', '/').replace('.', '/')
        val parts = s.split('/')
        if (parts.size != 3) return null
        var (y, m, d) = Triple(parts[0].toIntOrNull(), parts[1].toIntOrNull(), parts[2].toIntOrNull())
        if (y == null || m == null || d == null) return null
        // Swapped yyyy vs dd happens often in model output for day-first formats. This must
        // be checked BEFORE the two-digit-year fix-up, otherwise "12/08/1405" would first
        // become "1412/08/1405" and never be recognised as swapped.
        if (d > 1000 && y <= 31) { val t = y; y = d; d = t }
        if (y in 1..99) y += 1400 // tolerate two-digit Jalali years on badly printed forms
        if (y !in 1300..1600) return null
        return if (Jalali.isValid(y, m, d)) Jalali.format(y, m, d) else null
    }
}
