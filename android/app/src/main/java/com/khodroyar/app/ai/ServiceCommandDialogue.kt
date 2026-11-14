package com.khodroyar.app.ai

import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.rates.ServiceType
import com.khodroyar.app.core.text.PersianInput
import org.json.JSONObject

object ServiceCommandSpecs {
    private val types = ServiceType.entries.map { it.wire }
    private val range = listOf(ToolParam("start_date", ParamType.DATE, true), ToolParam("end_date", ParamType.DATE, true),
        ToolParam("service_type", ParamType.ENUM, true, options = types + "all"))
    private val after = ToolParam("report_after", ParamType.ENUM, options = listOf("on", "off"))
    val tools = listOf(
        ToolSpec("bulk_register_services", ToolKind.WRITE, "تکمیل تعداد هدف روزانه در بازهٔ شمسی؛ الگوی واقعی ذخیره‌شده یا مشخصات صریح کاربر. رکوردهای موجود بازنویسی نمی‌شوند",
            range + listOf(ToolParam("per_day", ParamType.INT, true, min = 1.0, max = 20.0),
                ToolParam("days", ParamType.ENUM, true, options = listOf("all", "workdays")),
                ToolParam("km", ParamType.NUMBER, max = 3000.0), ToolParam("hours", ParamType.NUMBER, max = 24.0),
                ToolParam("template_source", ParamType.ENUM, desc = "saved: الگوی واقعی موجود (پیش‌فرض)، explicit: مسیر و km/hours صریح کاربر", options = listOf("saved", "explicit")),
                ToolParam("origin", ParamType.TEXT), ToolParam("destination", ParamType.TEXT),
                ToolParam("car_type", ParamType.ENUM, options = listOf("soren", "tara")),
                ToolParam("start_time", ParamType.TEXT), ToolParam("end_time", ParamType.TEXT),
                ToolParam("toll_count", ParamType.INT, min = 0.0, max = 100.0), after)),
        ToolSpec("bulk_edit_services", ToolKind.WRITE, "ویرایش سرویس‌های انتخاب‌شده پس از خلاصهٔ قبل/بعد و تأیید تازه",
            range + listOf(ToolParam("km", ParamType.NUMBER, max = 3000.0), ToolParam("hours", ParamType.NUMBER, max = 24.0), ToolParam("new_type", ParamType.ENUM, options = types), after)),
        ToolSpec("bulk_delete_services", ToolKind.DELETE, "حذف سرویس‌های دقیقاً انتخاب‌شده؛ فقط با دکمهٔ تأیید حذف", range + after),
        ToolSpec("search_services_range", ToolKind.READ, "جستجوی سرویس‌ها در بازهٔ دقیق و نوع مشخص", range),
        ToolSpec("report_services_range", ToolKind.READ, "گزارش تعداد، کیلومتر، ساعت و درآمد واقعی روزهای بازه", range),
    )
    val names = tools.map { it.name }.toSet()
}

/** Stateful, offline Persian work-record dialogue. It proposes tools; it cannot write data. */
class ServiceCommandDialogue(private val today: () -> String) {
    private data class Scope(val start: String, val end: String, val type: String)
    private data class Draft(var action: String, var start: String? = null, var end: String? = null, var type: String? = null,
        var perDay: Int? = null, var workdays: Boolean = false, var km: Double? = null, var hours: Double? = null, var newType: String? = null, var reportAfter: Boolean = false)
    private var draft: Draft? = null
    private var last: Scope? = null
    private var waiting = ""
    var context = LocalDialogueContext(); private set
    fun reset() { draft = null; last = null; waiting = ""; context = LocalDialogueContext() }
    fun discard() { draft = null; waiting = ""; context = LocalDialogueContext() }
    fun completed(call: ValidatedCall, success: Boolean) {
        if (call.spec.name !in ServiceCommandSpecs.names || !success) return
        last = Scope(requireNotNull(call.str("start_date")), requireNotNull(call.str("end_date")), call.str("new_type") ?: requireNotNull(call.str("service_type")))
        draft = null; waiting = ""; context = LocalDialogueContext("سرویس‌های بازه", "${last!!.start} تا ${last!!.end}", listOf("گزارش همونا رو بده", "جستجوی همون سرویس‌ها"))
    }
    fun parse(raw: String): LocalIntent? {
        val original = PersianInput.latin(raw).lowercase()
        val after = Regex("(?:و\\s+)?(?:بعدش|بعد|سپس|then)\\s*(?:گزارش|جمع|report)").find(original)
        val t = if (after != null) original.substring(0, after.range.first).trim() else original
        val dates = PersianInput.dates(t)
        val service = has(t, "سرویس", "services", "night-shift") && !has(t, "تعویض", "روغن", "لنت", "نگهداری", "کارواش", "قانون")
        val follow = last != null && has(t, "همونا", "همان ها", "همون", "گزارشش", "این سرویس")
        val range = dates.size >= 2 || has(t, "بازه", " از ") && has(t, " تا ")
        val action = when {
            has(t, "حذف", "پاک کن", "delete", "remove") -> "bulk_delete_services"
            has(t, "ویرایش", "تغییر", "اصلاح", "edit", "update") -> "bulk_edit_services"
            has(t, "گزارش", "جمع", "چندتا", "report", "total") -> "report_services_range"
            has(t, "جستجو", "پیدا", "بگرد", "فهرست", "لیست", "سوابق", "search", "find") -> "search_services_range"
            has(t, "ثبت", "اضافه", "بزن", "register", "record") -> "bulk_register_services"
            else -> null
        }
        // Preserve existing single-service/recurring dialogue unless an explicit range,
        // record management, or a continuation of this conversation is requested.
        if (draft == null && !(follow || service && action != null && (range || action != "bulk_register_services"))) return null
        if (has(t, "هر روز", "روزانه", "خودکار") && !range && draft == null && !follow) return null
        if (draft == null) {
            val a = action ?: return question("می‌خواهید در این بازه ثبت، جستجو، گزارش، ویرایش یا حذف کنم؟", "action")
            draft = Draft(a)
            if (follow) last?.let { scope -> draft!!.start = scope.start; draft!!.end = scope.end; draft!!.type = scope.type }
        }
        val d = requireNotNull(draft)
        if (after != null) d.reportAfter = true
        if (action != null && action != d.action) {
            d.action = action; d.km = null; d.hours = null; d.newType = null; d.perDay = null
        }
        val dateLike = Regex("(?<![0-9])(?:[0-9]{7,9}|[0-9]{4}\\s*[/.-]\\s*[0-9]{1,2}\\s*[/.-]\\s*[0-9]{1,2})(?![0-9])").findAll(t).count()
        if (dates.any { it == null } || dates.size > 2 || dateLike > dates.size) return question("تاریخ نامعتبر یا بیش از دو تاریخ دیده شد؛ ابتدا و انتهای بازه را دقیق شمسی بنویسید.", "range")
        when (dates.size) {
            2 -> { d.start = dates[0]; d.end = dates[1] }
            1 -> when {
                Regex("(?:^|\\s)(?:تا|to)\\s").containsMatchIn(t) -> d.end = dates[0]
                Regex("(?:^|\\s)(?:از|from)\\s").containsMatchIn(t) -> d.start = dates[0]
                else -> { d.start = dates[0]; d.end = dates[0] }
            }
        }
        if (has(t, "امروز", "امشب")) { if (has(t, "تا امروز", "تا امشب")) d.end = today() else { d.start = today(); d.end = today() } }
        if (has(t, "دیروز", "دیشب")) { val yesterday = Jalali.addDays(today(), -1); if (has(t, "از دیروز", "از دیشب")) d.start = yesterday else { d.start = yesterday; d.end = yesterday } }
        val hits = typeHits(t)
        if (has(t, "همه نوع", "همه سرویس", "تمام سرویس", "all types")) d.type = "all"
        else if (hits.size == 1) {
            if (d.action == "bulk_edit_services" && d.type != null && has(t, "نوعش", "نوع را", "بکن", "بشه", "به ")) d.newType = hits.single()
            else d.type = hits.single()
        } else if (hits.size > 1) {
            if (d.action == "bulk_edit_services" && hits.size == 2 && has(t, " به ")) { d.type = hits[0]; d.newType = hits[1] }
            else return question("در هر پیش‌نمایش فقط یک نوع سرویس را انتخاب کنید؛ مثلاً شب یا درخواستی.", "type")
        }
        if (has(t, "فقط روزهای کاری", "روزای کاری", "working days")) d.workdays = true
        if (has(t, "همه روزها", "حتی تعطیل", "all days")) d.workdays = false
        val stripped = t.replace(PersianInput.datePattern, " ")
        if (Regex("[-−–—﹣－]\\s*[0-9]").containsMatchIn(stripped)) return question("مقدار منفی مجاز نیست؛ مقدار دقیق را اصلاح کنید.", "changes")
        val km = value(stripped, "کیلومتر|km", false)
        val hours = value(stripped, "ساعت|hours?", true)
        if (km.first) { if (km.second == null || km.second!! > 3000) return question("کیلومتر باید عدد دقیق بین صفر و ۳۰۰۰ باشد.", "km"); d.km = km.second }
        if (hours.first) { if (hours.second == null || hours.second!! > 24) return question("ساعت باید عدد دقیق بین صفر و ۲۴، یا HH:mm باشد.", "hours"); d.hours = hours.second }
        if (d.action == "bulk_register_services") {
            val count = Regex("(?:روزی|هر روز|روزانه|per day)\\s*([0-9]+|یک|یه|ی|دو|سه)(?:\\s*(?:تا|سرویس))?").find(stripped)?.groupValues?.get(1)
                ?: if (waiting == "count" && Regex("[0-9]+|یک|یه|ی|دو|سه").matches(stripped.trim())) stripped.trim() else null
            if (count != null) d.perDay = when (count) { "یک", "یه", "ی" -> 1; "دو" -> 2; "سه" -> 3; else -> count.toIntOrNull() }
        }
        if (d.start == null || d.end == null) return question("ابتدا و انتهای بازه کدام تاریخ است؟ مثلاً ۱۴۰۳۰۱۰۱ تا ۱۴۰۳۱۲۲۹. برای یک روز همان تاریخ را بگو.", "range")
        val days = PersianInput.days(d.start!!, d.end!!)
            ?: return question("بازه باید معتبر، صعودی و حداکثر ۳۶۶ روز باشد؛ تاریخ‌ها را اصلاح کنید.", "range")
        if (d.end!! > today()) return question("ثبت یا تغییر واقعی برای آینده انجام نمی‌دهم. پایان بازه باید حداکثر امروز باشد؛ برای آینده از قانون خودکار استفاده کنید.", "range")
        if (d.type == null || d.type == "all" && d.action == "bulk_register_services") return question("نوع سرویس چیست؟ شب، ثابت، تعطیل، درخواستی، در اختیار یا مأموریت؟", "type")
        if (d.action == "bulk_register_services" && (d.perDay == null || d.perDay?.let { it in 1..20 } != true)) return question("برای هر روز چند سرویس ${ServiceType.fromWire(d.type).label} باشد؟ تعداد دقیق ۱ تا ۲۰ را بگو؛ روزهای موجود دوباره شمرده نمی‌شوند.", "count")
        if (d.action == "bulk_register_services" && days.size * requireNotNull(d.perDay) > 1000) return question("حداکثر ۱۰۰۰ سرویس در یک پیش‌نمایش پشتیبانی می‌شود؛ تعداد روزانه یا بازه را کمتر کنید.", "count")
        if (d.action == "bulk_edit_services" && d.km == null && d.hours == null && d.newType == null) return question("کدام مقدار تغییر کند؟ مثلاً «کیلومترش ۸۰ و ساعتش ۳٫۵ بشه». مبلغ درآمد با نرخ همان سال محاسبه می‌شود.", "changes")
        val args = JSONObject().put("start_date", d.start).put("end_date", d.end).put("service_type", d.type)
        if (d.reportAfter) args.put("report_after", "on")
        if (d.action == "bulk_register_services") args.put("per_day", d.perDay).put("days", if (d.workdays) "workdays" else "all")
        d.km?.let { args.put("km", it) }; d.hours?.let { args.put("hours", it) }; d.newType?.let { args.put("new_type", it) }
        waiting = ""
        context = LocalDialogueContext("سرویس‌های بازه", "${d.start} تا ${d.end} · ${if (d.type == "all") "همهٔ نوع‌ها" else ServiceType.fromWire(d.type).label}", listOf("لغو", "فقط روزهای کاری"))
        if (d.action in setOf("search_services_range", "report_services_range")) {
            last = Scope(d.start!!, d.end!!, d.type!!); draft = null
        }
        return LocalIntent.Tool(d.action, args)
    }
    private fun value(t: String, unit: String, hours: Boolean): Pair<Boolean, Double?> {
        val u = "(?:$unit)(?:ش|ها|شون)?"
        // Consume the whole token. Never accept 1,234 from an invalid 1,234.5.6
        // or the positive tail of a signed/otherwise malformed value.
        val number = "(?<![0-9.,٫٬:])(?:[0-9][0-9.,٫٬:]*)(?![0-9.,٫٬:])"
        val matches = Regex("($number)\\s*$u|$u\\s*(?:را|رو|به|بشه|باشد|بکن|:)?\\s*($number)").findAll(t).toList()
        if (matches.size != 1) return (matches.isNotEmpty() || Regex("$u\\s*[0-9]").containsMatchIn(t)) to null
        val hit = matches.single().groupValues.drop(1).first { it.isNotEmpty() }
        return true to (if (hours) PersianInput.hours(hit) else PersianInput.decimal(hit))?.toDouble()
    }
    private fun question(text: String, field: String): LocalIntent.Reply {
        waiting = field; context = LocalDialogueContext("تنظیم سرویس‌های بازه", text, listOf("لغو")); return LocalIntent.Reply(text)
    }
    private fun typeHits(t: String): List<String> {
        // Longest non-overlapping phrase wins: «حقوق ثابت» is NOT also «ثابت».
        val hits = aliases.flatMap { (wire, words) -> words.flatMap { word ->
            Regex("(?<![\\p{L}])${Regex.escape(word)}(?![\\p{L}])").findAll(t).map { it.range to wire }.toList()
        } }.sortedWith(compareByDescending<Pair<IntRange, String>> { it.first.count() }.thenBy { it.first.first })
        val chosen = mutableListOf<Pair<IntRange, String>>()
        for (hit in hits) if (chosen.none { it.first.first <= hit.first.last && hit.first.first <= it.first.last }) chosen += hit
        return chosen.sortedBy { it.first.first }.map { it.second }.distinct()
    }
    private fun has(t: String, vararg words: String) = words.any { t.contains(it) }
    companion object {
        private val aliases = listOf("fixed-salary" to listOf("حقوق ثابت"), "night" to listOf("شب", "شبانه", "night", "night-shift"),
            "holiday" to listOf("تعطیل", "تعطیلات"), "request" to listOf("درخواستی", "request"), "available" to listOf("در اختیار", "آماده باش"),
            "fixed" to listOf("ثابت"), "mission" to listOf("ماموریت", "مأموریت"), "leave" to listOf("مرخصی"))
    }
}
