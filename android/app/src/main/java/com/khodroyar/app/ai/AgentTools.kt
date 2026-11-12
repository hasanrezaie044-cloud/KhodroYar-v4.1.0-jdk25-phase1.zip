package com.khodroyar.app.ai

import com.khodroyar.app.core.jalali.Jalali
import org.json.JSONObject

enum class ToolKind { READ, WRITE, DELETE }
enum class ParamType { TEXT, NUMBER, INT, DATE, ENUM, ID }

data class ToolParam(
    val name: String,
    val type: ParamType,
    val required: Boolean = false,
    val desc: String = "",
    val options: List<String> = emptyList(),
    val min: Double = 0.0,
    val max: Double = 1.0e12,
    val allowFuture: Boolean = false,
)

data class ToolSpec(
    val name: String,
    val kind: ToolKind,
    val desc: String,
    val params: List<ToolParam> = emptyList(),
)

/**
 * The ONLY operations the model can request. There is no SQL, no file access and no generic
 * command: anything not listed here is rejected by [AgentActionValidator].
 */
object AgentToolRegistry {

    private val MAINT_TYPES = listOf("oil-change", "timing-belt", "spark-plug", "brake-pad", "fuel-filter", "repair", "tire", "wash", "other")
    private val CATEGORIES = listOf(
        "cigarette", "coffee", "restaurant", "clothing", "food", "transport",
        "health", "home", "entertainment", "education", "bills", "other",
    )

    private val year = ToolParam("year", ParamType.INT, desc = "سال شمسی، پیش‌فرض سال جاری", min = 1300.0, max = 1600.0)
    private val month = ToolParam("month", ParamType.INT, desc = "ماه شمسی ۱ تا ۱۲، پیش‌فرض ماه جاری", min = 1.0, max = 12.0)
    private val limit = ToolParam("limit", ParamType.INT, desc = "حداکثر تعداد ردیف (پیش‌فرض ۱۰)", min = 1.0, max = 20.0)
    private val date = ToolParam("date", ParamType.DATE, desc = "تاریخ شمسی YYYY/MM/DD یا today یا yesterday؛ پیش‌فرض today")
    private val id = ToolParam("id", ParamType.ID, required = true, desc = "شناسهٔ رکورد از خروجی ابزار list")
    private val km = ToolParam("km", ParamType.NUMBER, desc = "کیلومتر کارکرد", min = 0.0, max = 3_000_000.0)

    val tools: List<ToolSpec> = listOf(
        // ---- read -------------------------------------------------------
        ToolSpec("get_today_expenses", ToolKind.READ, "هزینه‌های امروز (شخصی، سوخت، تعمیرات)"),
        ToolSpec("get_monthly_report", ToolKind.READ, "گزارش درآمد و هزینهٔ یک ماه", listOf(year, month)),
        ToolSpec("get_yearly_report", ToolKind.READ, "گزارش درآمد و هزینهٔ یک سال به تفکیک ماه", listOf(year)),
        ToolSpec(
            "get_totals", ToolKind.READ, "مجموع درآمد و هزینه در یک بازه",
            listOf(
                ToolParam("period", ParamType.ENUM, required = true, options = listOf("today", "month", "year", "all")),
                year, month,
            ),
        ),
        ToolSpec("list_maintenance", ToolKind.READ, "سوابق تعمیرات و سرویس‌های خودرو", listOf(year, month, limit)),
        ToolSpec("list_services", ToolKind.READ, "سرویس‌های (سفرهای) ثبت‌شده", listOf(year, month, ToolParam("date", ParamType.DATE, desc = "فقط یک روز مشخص"), limit)),
        ToolSpec("list_expenses", ToolKind.READ, "هزینه‌های شخصی ثبت‌شده (با شناسه برای ویرایش/حذف)", listOf(year, month, limit)),
        ToolSpec("list_fuel", ToolKind.READ, "سوخت‌گیری‌های ثبت‌شده (با شناسه)", listOf(year, month, limit)),
        ToolSpec("get_active_reminders", ToolKind.READ, "یادآورهای فعال: بیمه، اقساط، تعویض روغن و مدارک"),
        ToolSpec("analyze_fuel", ToolKind.READ, "تحلیل مصرف و هزینهٔ سوخت", listOf(year, month)),
        ToolSpec("get_vehicle_status", ToolKind.READ, "وضعیت خودرو: کیلومتر و موعد سرویس‌ها"),
        ToolSpec("create_financial_report", ToolKind.READ, "گزارش مالی کامل یک ماه یا سال (متن)", listOf(year, month)),
        // ---- write (need user confirmation) -------------------------------
        ToolSpec(
            "add_expense", ToolKind.WRITE, "ثبت هزینهٔ شخصی/عمومی (غیر از تعمیر و سوخت)",
            listOf(
                ToolParam("amount", ParamType.NUMBER, required = true, desc = "مبلغ به تومان", min = 1.0),
                ToolParam("title", ParamType.TEXT, desc = "عنوان کوتاه"),
                ToolParam("category", ParamType.ENUM, options = CATEGORIES),
                date,
            ),
        ),
        ToolSpec(
            "add_repair", ToolKind.WRITE, "ثبت هزینهٔ تعمیر خودرو",
            listOf(
                ToolParam("amount", ParamType.NUMBER, required = true, desc = "مبلغ به تومان", min = 0.0),
                ToolParam("description", ParamType.TEXT, desc = "شرح تعمیر"),
                km, date,
            ),
        ),
        ToolSpec(
            "add_car_service", ToolKind.WRITE, "ثبت سرویس خودرو (تعویض روغن، تسمه، شمع، لنت، فیلتر، لاستیک، کارواش)",
            listOf(
                ToolParam("service_type", ParamType.ENUM, required = true, options = MAINT_TYPES),
                ToolParam("amount", ParamType.NUMBER, desc = "هزینه به تومان", min = 0.0),
                km,
                ToolParam("description", ParamType.TEXT),
                date,
            ),
        ),
        ToolSpec(
            "add_fuel", ToolKind.WRITE, "ثبت سوخت‌گیری",
            listOf(
                ToolParam("liters", ParamType.NUMBER, required = true, desc = "لیتر", min = 0.1, max = 500.0),
                ToolParam("total_cost", ParamType.NUMBER, required = true, desc = "مبلغ کل به تومان", min = 0.0),
                ToolParam("fuel_type", ParamType.ENUM, options = listOf("gov", "semi", "free")),
                date,
            ),
        ),
        ToolSpec(
            "create_reminder", ToolKind.WRITE, "ایجاد یادآور انقضا (بیمه/مدارک) با تاریخ شمسی",
            listOf(
                ToolParam("title", ParamType.TEXT, required = true, desc = "مثلاً بیمه شخص ثالث"),
                ToolParam("expiry_date", ParamType.DATE, required = true, desc = "تاریخ انقضا", allowFuture = true),
                ToolParam("company", ParamType.TEXT),
                ToolParam("cost", ParamType.NUMBER, min = 0.0),
            ),
        ),
        ToolSpec(
            "update_expense", ToolKind.WRITE, "ویرایش هزینهٔ شخصی",
            listOf(id, ToolParam("amount", ParamType.NUMBER, min = 1.0), ToolParam("title", ParamType.TEXT), ToolParam("category", ParamType.ENUM, options = CATEGORIES), ToolParam("date", ParamType.DATE)),
        ),
        ToolSpec(
            "update_maintenance", ToolKind.WRITE, "ویرایش سابقهٔ تعمیر/سرویس",
            listOf(id, ToolParam("amount", ParamType.NUMBER, min = 0.0), km, ToolParam("description", ParamType.TEXT), ToolParam("date", ParamType.DATE)),
        ),
        ToolSpec(
            "update_fuel", ToolKind.WRITE, "ویرایش سوخت‌گیری",
            listOf(id, ToolParam("liters", ParamType.NUMBER, min = 0.1, max = 500.0), ToolParam("total_cost", ParamType.NUMBER, min = 0.0), ToolParam("date", ParamType.DATE)),
        ),
        // ---- delete (separate explicit confirmation) ----------------------
        ToolSpec("delete_expense", ToolKind.DELETE, "حذف یک هزینهٔ شخصی", listOf(id)),
        ToolSpec("delete_maintenance", ToolKind.DELETE, "حذف یک سابقهٔ تعمیر/سرویس", listOf(id)),
        ToolSpec("delete_fuel", ToolKind.DELETE, "حذف یک سوخت‌گیری", listOf(id)),
    )

    private val byName: Map<String, ToolSpec> = tools.associateBy { it.name }

    fun find(name: String): ToolSpec? = byName[name]

    /** Compact tool catalogue placed in the system prompt. */
    fun promptSection(): String = buildString {
        tools.forEach { t ->
            append("- ").append(t.name).append(" [").append(t.kind.name).append("]: ").append(t.desc)
            if (t.params.isNotEmpty()) {
                append(" | args: ")
                append(t.params.joinToString(", ") { p ->
                    val opts = if (p.options.isNotEmpty()) "=" + p.options.joinToString("/") else ""
                    p.name + (if (p.required) "*" else "") + ":" + p.type.name.lowercase() + opts
                })
            }
            append('\n')
        }
    }
}

/** Arguments that already passed validation, with normalised values. */
class ValidatedCall(val spec: ToolSpec, val args: Map<String, Any>) {
    fun str(name: String): String? = args[name] as? String
    fun dbl(name: String): Double? = (args[name] as? Number)?.toDouble()
    fun int(name: String): Int? = (args[name] as? Number)?.toInt()
    fun has(name: String): Boolean = args.containsKey(name)
}

sealed interface ValidationResult {
    data class Ok(val call: ValidatedCall) : ValidationResult
    data class Invalid(val reason: String) : ValidationResult
}

/** Checks every model-produced value inside the app before anything is shown or executed. */
object AgentActionValidator {

    private val ID_RE = Regex("^[A-Za-z0-9-]{4,40}$")

    fun normalizeDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                in '۰'..'۹' -> sb.append('0' + (ch - '۰'))
                in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
                '٫' -> sb.append('.')
                '٬', ',', ' ', '\u200c' -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun validate(name: String, raw: JSONObject?): ValidationResult {
        val spec = AgentToolRegistry.find(name)
            ?: return ValidationResult.Invalid("ابزار «$name» وجود ندارد یا مجاز نیست.")
        val src = raw ?: JSONObject()
        val out = LinkedHashMap<String, Any>()
        for (p in spec.params) {
            val v = if (src.has(p.name) && !src.isNull(p.name)) src.opt(p.name) else null
            if (v == null || (v is String && v.isBlank())) {
                if (p.required) return ValidationResult.Invalid("پارامتر ${p.name} الزامی است.")
                continue
            }
            when (val r = coerce(p, v)) {
                is Coerced.Bad -> return ValidationResult.Invalid(r.reason)
                is Coerced.Good -> out[p.name] = r.value
            }
        }
        if (spec.name.startsWith("update_") && out.keys.none { it != "id" }) {
            return ValidationResult.Invalid("برای ویرایش حداقل یک فیلد جدید لازم است.")
        }
        return ValidationResult.Ok(ValidatedCall(spec, out))
    }

    private sealed interface Coerced {
        data class Good(val value: Any) : Coerced
        data class Bad(val reason: String) : Coerced
    }

    private fun coerce(p: ToolParam, v: Any): Coerced {
        return when (p.type) {
            ParamType.TEXT -> {
                val t = v.toString().filter { !it.isISOControl() }.trim().take(80)
                if (t.isEmpty()) Coerced.Bad("${p.name} خالی است.") else Coerced.Good(t)
            }
            ParamType.NUMBER, ParamType.INT -> {
                val d = when (v) {
                    is Number -> v.toDouble()
                    is String -> normalizeDigits(v).toDoubleOrNull()
                    else -> null
                }
                when {
                    d == null || d.isNaN() || d.isInfinite() -> Coerced.Bad("${p.name} باید عدد معتبر باشد.")
                    d < p.min || d > p.max -> Coerced.Bad("${p.name} خارج از محدوده مجاز است.")
                    p.type == ParamType.INT && Math.abs(d - Math.rint(d)) > 1e-9 -> Coerced.Bad("${p.name} باید عدد صحیح باشد.")
                    p.type == ParamType.INT -> Coerced.Good(Math.rint(d).toInt())
                    else -> Coerced.Good(d)
                }
            }
            ParamType.DATE -> normalizeDate(v.toString(), p.allowFuture)
            ParamType.ENUM -> {
                val t = v.toString().trim().lowercase().replace('_', '-')
                val hit = p.options.firstOrNull { it == t }
                if (hit == null) Coerced.Bad("${p.name} باید یکی از ${p.options.joinToString("، ")} باشد.") else Coerced.Good(hit)
            }
            ParamType.ID -> {
                val t = v.toString().trim()
                if (ID_RE.matches(t)) Coerced.Good(t) else Coerced.Bad("شناسهٔ رکورد نامعتبر است.")
            }
        }
    }

    private fun normalizeDate(input: String, allowFuture: Boolean): Coerced {
        val s = normalizeDigits(input).trim().lowercase()
        val today = Jalali.todayString()
        if (s == "today" || s == "امروز") return Coerced.Good(today)
        if (s == "yesterday" || s == "دیروز") return Coerced.Good(Jalali.addDays(today, -1))
        val parts = s.replace('-', '/').replace('.', '/').split('/')
        if (parts.size != 3) return Coerced.Bad("تاریخ باید شمسی و به شکل YYYY/MM/DD باشد.")
        val y = parts[0].toIntOrNull()
        val m = parts[1].toIntOrNull()
        val d = parts[2].toIntOrNull()
        if (y == null || m == null || d == null) return Coerced.Bad("تاریخ نامعتبر است.")
        if (y < 1300 || y > 1600) return Coerced.Bad("تاریخ باید شمسی باشد (مثل 1405/08/20).")
        if (!Jalali.isValid(y, m, d)) return Coerced.Bad("چنین تاریخی در تقویم شمسی وجود ندارد.")
        val normalized = Jalali.format(y, m, d)
        if (!allowFuture && normalized > today) return Coerced.Bad("تاریخ نمی‌تواند در آینده باشد.")
        return Coerced.Good(normalized)
    }
}
