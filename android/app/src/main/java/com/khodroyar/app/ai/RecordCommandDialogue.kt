package com.khodroyar.app.ai

import com.khodroyar.app.core.text.PersianInput
import org.json.JSONObject

/** Local management of existing expense/fuel/maintenance records by an explicit list ID. */
class RecordCommandDialogue {
    private data class Draft(var action: String, var kind: String?, var id: String? = null, val fields: MutableMap<String, Any> = linkedMapOf())
    private var draft: Draft? = null
    var context = LocalDialogueContext(); private set
    fun reset() { draft = null; context = LocalDialogueContext() }
    fun completed(call: ValidatedCall, success: Boolean) { if (success && call.spec.name in names) reset() }
    fun parse(t: String): LocalIntent? {
        val action = when {
            listOf("حذف", "پاک کن").any { t.contains(it) } -> "delete"
            listOf("ویرایش", "اصلاح", "تغییر بده").any { t.contains(it) } -> "update"
            else -> null
        }
        val kind = when {
            listOf("سوخت", "بنزین").any { t.contains(it) } -> "fuel"
            listOf("تعمیر", "نگهداری", "روغن", "لنت", "کارواش").any { t.contains(it) } -> "maintenance"
            listOf("هزینه", "خرج", "خرید").any { t.contains(it) } -> "expense"
            else -> null
        }
        if (draft == null && (action == null || kind == null || t.contains("قانون"))) return null
        if (draft == null) draft = Draft(requireNotNull(action), kind)
        val d = requireNotNull(draft)
        if (kind != null && kind != d.kind) return question("برای جلوگیری از تغییر رکورد اشتباه، اول درخواست قبلی را لغو کن.")
        if (action != null) d.action = action
        if (listOf("فهرست", "لیست", "سوابق").any { t.contains(it) } && action == null)
            return LocalIntent.Tool(when (d.kind) { "expense" -> "list_expenses"; "fuel" -> "list_fuel"; else -> "list_maintenance" })
        val explicit = Regex("(?:شناسه|id)\\s*[:：]?\\s*([a-z0-9-]{4,40})(?![a-z0-9-])").find(t)?.groupValues?.get(1)
        val bare = t.trim().takeIf { Regex("[a-z0-9-]{8,40}").matches(it) && it.any { ch -> ch in 'a'..'z' } }
        (explicit ?: bare)?.let { d.id = it }
        if (d.action == "update") {
            when (val amount = LocalNumbers.money(t)) {
                is LocalNumbers.Value.Found -> d.fields[if (d.kind == "fuel") "total_cost" else "amount"] = amount.number
                LocalNumbers.Value.Ambiguous, LocalNumbers.Value.Invalid -> return question("یک مبلغ دقیق با واحد تومان/ریال بنویس؛ مبلغ را حدس نمی‌زنم.")
                else -> Unit
            }
            if (d.kind == "fuel") when (val liters = LocalNumbers.liters(t)) {
                is LocalNumbers.Value.Found -> d.fields["liters"] = liters.number
                LocalNumbers.Value.Ambiguous, LocalNumbers.Value.Invalid -> return question("یک مقدار دقیق لیتر بنویس.")
                else -> Unit
            }
            if (d.kind == "maintenance") when (val km = LocalNumbers.kilometers(t)) {
                is LocalNumbers.Value.Found -> d.fields["km"] = km.number
                LocalNumbers.Value.Ambiguous, LocalNumbers.Value.Invalid -> return question("یک کیلومتر کارکرد دقیق بنویس.")
                else -> Unit
            }
            val dates = PersianInput.dates(t)
            if (dates.size > 1 || dates.any { it == null }) return question("برای تاریخ جدید، فقط یک تاریخ شمسی معتبر بنویس.")
            dates.singleOrNull()?.let { d.fields["date"] = it }
            Regex("(?:عنوانش|عنوان جدید|توضیح جدید)\\s*[:：]?\\s*(.+?)(?:\\s+(?:بشه|باشد))?$").find(t)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }?.let {
                if (d.kind != "fuel") d.fields[if (d.kind == "maintenance") "description" else "title"] = it.take(80)
            }
        }
        if (d.id == null) return question("شناسهٔ رکورد کدام است؟ از فهرست ${label(d.kind)} شناسهٔ ۸حرفی را بفرست؛ انتخاب مبهم را حدس نمی‌زنم.", listOf("فهرست ${label(d.kind)}", "لغو"))
        if (d.action == "update" && d.fields.isEmpty()) return question("چه چیزی تغییر کند؟ مبلغ با واحد تومان، تاریخ شمسی، و برای سوخت لیتر یا تعمیرات کیلومتر را بگو.")
        val args = JSONObject().put("id", d.id); d.fields.forEach { (key, value) -> args.put(key, value) }
        context = LocalDialogueContext("${if (d.action == "delete") "حذف" else "ویرایش"} ${label(d.kind)}", "رکورد ${d.id}؛ منتظر بررسی و تأیید", listOf("لغو"))
        return LocalIntent.Tool("${d.action}_${d.kind}", args)
    }
    private fun question(text: String, suggestions: List<String> = listOf("لغو")): LocalIntent.Reply {
        context = LocalDialogueContext("مدیریت رکورد موجود", text, suggestions); return LocalIntent.Reply(text)
    }
    private fun label(kind: String?) = when (kind) { "expense" -> "هزینه‌ها"; "fuel" -> "سوخت"; else -> "تعمیرات" }
    companion object { val names = setOf("update_expense", "delete_expense", "update_fuel", "delete_fuel", "update_maintenance", "delete_maintenance") }
}
