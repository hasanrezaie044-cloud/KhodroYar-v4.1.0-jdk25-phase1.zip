package com.khodroyar.app.ai

import com.khodroyar.app.core.jalali.Jalali
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

sealed interface LocalIntent {
    data class Reply(val text: String) : LocalIntent
    data class Tool(val name: String, val args: JSONObject = JSONObject()) : LocalIntent
}

/**
 * Offline, deterministic command assistant. This is NOT a general language model. It has no
 * Context, network, provider, key or database dependency. Unknown/ambiguous requests fail closed;
 * only the existing allow-listed app tools can be requested, never executed here.
 */
class LocalIntentParser(private val today: () -> Jalali.Ymd = { Jalali.today() }) {
    companion object {
        const val HELP = LocalDialogueEngine.HELP

        private val DATE = Regex("(?<!\\d)\\d{4}[/.-]\\d{1,2}[/.-]\\d{1,2}(?!\\d)")
        private val WRITE = Regex("(?:^(?:لطفا\\s+)?ثبت\\s+(?:هزینه|تعمیر|سوخت|بنزین|تعویض|سرویس|یادآور|یاداوری|درآمد|درامد|بیمه))|(?:ثبت(?:ش|شان)?\\s*(?:کن|بکن|شود|بشه)(?![\\p{L}]))|(?:اضافه\\s*کن(?![\\p{L}]))|(?:ذخیره\\s*کن(?![\\p{L}]))|(?:\\b(?:add|record|save)\\b)")
        private val NEGATED = Regex("نکن|نشه|نشود|نمی\\s*خوا|نمیخوام|بدون ثبت|فقط مثال|فرض کن|اگر|\\b(?:don't|dont|do not)\\b")
        private val EDIT_DELETE = Regex("حذف|پاک\\s*کن|ویرایش|تغییر\\s*بده|\\b(?:delete|remove|edit|update)\\b")

        fun normalize(raw: String): String = buildString {
            for (ch in Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT)) {
                append(when (ch) {
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    'ي', 'ى' -> 'ی'
                    'ك' -> 'ک'
                    '٫' -> '.'
                    '−', '–', '—', '﹣', '－' -> '-'
                    '٬', '،' -> ','
                    '\u200c', '\u00a0' -> ' '
                    else -> ch
                })
            }
        }.replace(Regex("[\\p{M}ـ]"), "").replace(Regex("\\s+"), " ").trim()
    }

    fun parse(raw: String): LocalIntent {
        if (raw.isBlank()) return LocalIntent.Reply("پیام خود را بنویسید یا «راهنما» بفرستید.")
        if (raw.length > AiLimits.MAX_USER_CHARS) return LocalIntent.Reply("پیام طولانی است؛ لطفاً هر بار یک درخواست کوتاه بنویسید.")
        val text = normalize(raw)
        if (has(text, "راهنما", "چه کار", "چیکار", "کمک", "چطور", "چگونه", "help")) return LocalIntent.Reply(HELP)
        if (NEGATED.containsMatchIn(text)) return LocalIntent.Reply("درخواستی برای تغییر اطلاعات اجرا نمی‌کنم. برای گزارش، مثلاً «گزارش این ماه» و برای راهنما «راهنما» بنویسید.")
        if (EDIT_DELETE.containsMatchIn(text)) return LocalIntent.Reply("در حالت داخلی، ویرایش و حذف متنی پشتیبانی نمی‌شود تا رکورد اشتباهی تغییر نکند. از صفحهٔ سوخت، تعمیرات یا مالی رکورد موردنظر را انتخاب کنید.")
        if (WRITE.containsMatchIn(text)) return write(text)
        return read(text)
    }

    private fun write(text: String): LocalIntent {
        if (Regex("(?: و |؛|;)\\s*(?:ثبت|اضافه|ذخیره)").containsMatchIn(text)) return LocalIntent.Reply("لطفاً هر بار فقط یک عملیات ثبت درخواست کنید.")
        if (has(text, "حدود", "تقریبا", "حداکثر", "حداقل", "کمتر", "بیشتر", "شاید", " یا ")) return LocalIntent.Reply("مبلغ یا درخواست قطعی نیست. برای ثبت، یک مبلغ دقیق و یک عملیات مشخص بنویسید.")
        if (has(text, "درآمد", "درامد", "income")) return LocalIntent.Reply("ثبت درآمد و سفر در حالت داخلی پشتیبانی نمی‌شود. از بخش مالی یا فرم ثبت سرویس استفاده کنید؛ چیزی ثبت نشده است.")
        val reminder = has(text, "یادآور", "یاداوری", "reminder")
        val fuel = has(text, "سوخت", "بنزین", "گازوئیل", "fuel")
        val serviceTypes = listOf(
            "oil-change" to listOf("روغن"), "timing-belt" to listOf("تسمه"),
            "spark-plug" to listOf("شمع"), "brake-pad" to listOf("لنت"),
            "fuel-filter" to listOf("فیلتر"), "tire" to listOf("لاستیک", "تایر"),
            "wash" to listOf("کارواش"),
        ).filter { (_, words) -> words.any { text.contains(it) } }.map { it.first }
        val repair = has(text, "تعمیر", "repair")
        if (!reminder && (serviceTypes.size > 1 || fuel && (repair || serviceTypes.isNotEmpty()))) {
            return LocalIntent.Reply("چند نوع هزینه در پیام دیده می‌شود. لطفاً هر بار فقط یک هزینه، تعمیر یا سوخت‌گیری بنویسید.")
        }
        val dates = DATE.findAll(text).map { it.value.replace('-', '/').replace('.', '/') }.distinct().toList()
        if (dates.size > 1) return LocalIntent.Reply("چند تاریخ در پیام است. برای هر ثبت فقط یک تاریخ شمسی بنویسید.")
        val date = dates.singleOrNull() ?: when {
            has(text, "پس فردا", "پسفردا") -> Jalali.addDays(today().toString(), 2)
            has(text, "فردا", "tomorrow") -> Jalali.addDays(today().toString(), 1)
            has(text, "پریروز") -> Jalali.addDays(today().toString(), -2)
            has(text, "دیروز", "yesterday") -> "yesterday"
            has(text, "تاریخ", "هفته", "ماه", "شنبه", "جمعه") -> return LocalIntent.Reply("تاریخ دقیق را شمسی و به شکل ۱۴۰۵/۰۸/۲۰ بنویسید؛ تاریخ را حدس نمی‌زنم.")
            reminder -> return LocalIntent.Reply("تاریخ انقضای یادآور را شمسی بنویسید؛ مثلاً «ثبت یادآور بیمه شخص ثالث تاریخ ۱۴۰۶/۰۱/۱۵».")
            else -> "today"
        }
        if (reminder) {
            val title = text.replace(DATE, "").replace(Regex("ثبت(?:ش)?(?: کن)?|یادآور|یاداوری|تاریخ|انقضا|برای"), " ")
                .replace(Regex("\\s+"), " ").trim().take(80)
            if (title.isBlank()) return LocalIntent.Reply("عنوان یادآور را هم بنویسید؛ مثلاً «بیمه شخص ثالث».")
            return LocalIntent.Tool("create_reminder", JSONObject().put("title", title).put("expiry_date", date))
        }
        if (has(text, "سفر", "مسیر", "مسافر") || text.contains("سرویس") && !repair && serviceTypes.isEmpty()) {
            return LocalIntent.Reply("برای ثبت سفر یا سرویس درآمدی از فرم «ثبت سرویس» استفاده کنید. در حالت داخلی فقط هزینه، تعمیر، سرویس نگهداری، سوخت و یادآور ثبت می‌کنم.")
        }
        val amount = when (val value = LocalNumbers.money(text)) {
            is LocalNumbers.Value.Found -> value.number
            LocalNumbers.Value.Ambiguous -> return LocalIntent.Reply("چند مبلغ در پیام است. لطفاً هر بار یک مبلغ کل با واحد تومان بنویسید.")
            LocalNumbers.Value.Invalid -> return LocalIntent.Reply("مبلغ روشن یا معتبر نیست. مثلاً «۲٬۵۰۰٬۰۰۰ تومان» یا «دو میلیون و پانصد هزار تومان» بنویسید.")
            LocalNumbers.Value.Missing -> if (has(text, "بدون هزینه", "رایگان") && (repair || serviceTypes.isNotEmpty())) 0.0
                else return LocalIntent.Reply("مبلغ را با واحد تومان یا ریال بنویسید؛ مثلاً «ثبت هزینه قهوه ۵۰ هزار تومان». هیچ مبلغی حدس زده نمی‌شود.")
        }
        val args = JSONObject().put("date", date)
        if (fuel) {
            val liters = when (val value = LocalNumbers.liters(text)) {
                is LocalNumbers.Value.Found -> value.number
                else -> return LocalIntent.Reply("مقدار سوخت را هم دقیق و با واحد لیتر بنویسید؛ مثلاً «ثبت سوخت ۴۰ لیتر ۱۲۰ هزار تومان».")
            }
            val type = when {
                has(text, "آزاد", "ازاد") -> "free"
                has(text, "نیمه") -> "semi"
                else -> "gov"
            }
            return LocalIntent.Tool("add_fuel", args.put("liters", liters).put("total_cost", amount).put("fuel_type", type))
        }
        if (repair || serviceTypes.isNotEmpty()) {
            when (val km = LocalNumbers.kilometers(text)) {
                is LocalNumbers.Value.Found -> args.put("km", km.number)
                LocalNumbers.Value.Missing -> if (text.contains("کیلومتر") || text.contains(" km")) {
                    return LocalIntent.Reply("کیلومتر را با عدد و واحد بنویسید؛ مثلاً «۱۵۰۰۰۰ کیلومتر».")
                }
                else -> return LocalIntent.Reply("کیلومتر مشخص نیست. لطفاً فقط یک عدد کارکرد با واحد کیلومتر بنویسید.")
            }
            args.put("amount", amount).put("description", text.take(80))
            return if (serviceTypes.isEmpty()) LocalIntent.Tool("add_repair", args)
            else LocalIntent.Tool("add_car_service", args.put("service_type", serviceTypes.single()))
        }
        if (!has(text, "هزینه", "خرج", "خرید", "expense", "قهوه", "غذا", "ناهار", "شام", "قبض", "کرایه")) {
            return LocalIntent.Reply("نوع ثبت مشخص نیست. هزینه، تعمیر، سوخت یا یادآور را صریح بنویسید. برای نمونه‌ها «راهنما» بفرستید.")
        }
        val category = when {
            has(text, "قهوه", "کافی", "coffee") -> "coffee"
            has(text, "رستوران", "ناهار", "شام") -> "restaurant"
            has(text, "غذا", "خوراک", "سوپرمارکت") -> "food"
            has(text, "کرایه", "تاکسی", "مترو", "اتوبوس") -> "transport"
            has(text, "دارو", "درمان", "پزشک") -> "health"
            has(text, "قبض", "اینترنت", "تلفن") -> "bills"
            has(text, "لباس", "پوشاک") -> "clothing"
            else -> "other"
        }
        return LocalIntent.Tool("add_expense", args.put("amount", amount).put("title", text.take(80)).put("category", category))
    }

    private fun read(text: String): LocalIntent {
        if (text.trim('!', '؟', '.', ',') in setOf("سلام", "درود", "hello", "hi")) return LocalIntent.Reply("سلام! بدون اینترنت و API آماده‌ام. می‌توانید «گزارش این ماه»، «وضعیت خودرو» یا «راهنما» بنویسید. من دستیار دستورمحور هستم، نه مدل زبانی عمومی.")
        if (has(text, "وضعیت خودرو", "وضعیت ماشین", "کیلومتر خودرو", "کیلومتر ماشین", "موعد سرویس", "زمان تعویض", "car status")) return LocalIntent.Tool("get_vehicle_status")
        if (has(text, "یادآور", "یاداوری", "بیمه", "اقساط", "قسط", "مدارک", "reminder")) return LocalIntent.Tool("get_active_reminders")
        val list = has(text, "لیست", "فهرست", "سوابق", "لیستی", "list")
        val fuel = has(text, "سوخت", "بنزین", "fuel")
        val maintenance = has(text, "تعمیر", "روغن", "لنت", "لاستیک", "نگهداری", "maintenance")
        val service = has(text, "سرویس", "سفر", "service") && !maintenance
        val dates = DATE.findAll(text).map { it.value.replace('-', '/').replace('.', '/') }.distinct().toList()
        if (dates.isNotEmpty()) {
            if (dates.size == 1 && service) return LocalIntent.Tool("list_services", JSONObject().put("date", dates.single()))
            return LocalIntent.Reply("گزارش روزانهٔ داخلی برای امروز و گزارش مالی برای ماه یا سال است. مثلاً «هزینه‌های امروز» یا «گزارش آبان ۱۴۰۵» بنویسید.")
        }
        if (has(text, "فردا", "دیروز", "پریروز", "هفته", "yesterday", "tomorrow", "week")) {
            return LocalIntent.Reply("این بازه در گزارش متنی داخلی پشتیبانی نمی‌شود. از تقویم برنامه استفاده کنید یا «گزارش این ماه» و «هزینه‌های امروز» بنویسید.")
        }
        val period = period(text)
        val periodArgs = JSONObject().put("year", period.year).put("month", period.month)
        val filterArgs = if (period.explicit) JSONObject().put("year", period.year).apply {
            if (!period.yearly) put("month", period.month)
        } else JSONObject()
        if (fuel) return LocalIntent.Tool(if (list) "list_fuel" else "analyze_fuel", if (period.yearly || list) filterArgs else periodArgs)
        if (maintenance) return LocalIntent.Tool("list_maintenance", filterArgs)
        if (service) return LocalIntent.Tool("list_services", if (has(text, "امروز", "today")) JSONObject().put("date", "today") else filterArgs)
        if (list && has(text, "هزینه", "خرج", "expense")) return LocalIntent.Tool("list_expenses", filterArgs)
        if (has(text, "گزارش", "درآمد", "درامد", "هزینه", "خرج", "جمع", "مجموع", "report", "income", "expense", "total")) {
            if (has(text, "کل دوره", "همه", "کل زمان", "all time")) return LocalIntent.Tool("get_totals", JSONObject().put("period", "all"))
            if (has(text, "امروز", "today")) {
                return if (has(text, "درآمد", "درامد", "income", "گزارش")) LocalIntent.Tool("get_totals", JSONObject().put("period", "today"))
                else LocalIntent.Tool("get_today_expenses")
            }
            return if (period.yearly) LocalIntent.Tool("get_yearly_report", JSONObject().put("year", period.year))
            else LocalIntent.Tool("get_monthly_report", periodArgs)
        }
        return LocalIntent.Reply("این درخواست را در حالت داخلی متوجه نشدم. من مدل زبانی عمومی نیستم. برای گزارش، ثبت هزینه و کارهای پشتیبانی‌شده «راهنما» بنویسید؛ هیچ اطلاعاتی تغییر نکرد.")
    }

    private data class Period(val year: Int, val month: Int, val explicit: Boolean, val yearly: Boolean)

    private fun period(text: String): Period {
        val now = today()
        val previousMonth = has(text, "ماه قبل", "ماه پیش", "ماه گذشته", "last month")
        val previousYear = has(text, "پارسال", "سال قبل", "سال پیش", "سال گذشته", "last year")
        val base = if (previousMonth) Jalali.parse(Jalali.addMonths(now.toString(), -1))!! else now
        val yearMonth = Regex("(?<!\\d)(\\d{4})[/.-](\\d{1,2})(?!\\d)").find(text)
        val yearToken = Regex("(?<!\\d)(1[3-6]\\d{2})(?!\\d)").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("سال\\s+(\\d{4})").find(text)?.groupValues?.get(1)?.toIntOrNull()
        val namedMonth = Jalali.MONTH_NAMES.indexOfFirst { month ->
            Regex("(?<![\\p{L}])${Regex.escape(month)}(?![\\p{L}])").containsMatchIn(text)
        }.takeIf { it >= 0 }?.plus(1)
        val numericMonth = Regex("ماه\\s+(\\d{1,2})(?!\\d)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        val year = yearMonth?.groupValues?.get(1)?.toIntOrNull() ?: yearToken ?: if (previousYear) now.year - 1 else base.year
        val month = yearMonth?.groupValues?.get(2)?.toIntOrNull() ?: namedMonth ?: numericMonth ?: base.month
        val hasMonth = has(text, "ماه", "month") || namedMonth != null || yearMonth != null
        val yearly = !hasMonth && (has(text, "سال", "year") || yearToken != null)
        return Period(year, month, hasMonth || yearly, yearly)
    }

    private fun has(text: String, vararg needles: String): Boolean = needles.any { text.contains(it) }
}
