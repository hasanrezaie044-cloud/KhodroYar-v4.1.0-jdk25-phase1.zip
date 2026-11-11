package com.khodroyar.app.ai

import com.khodroyar.app.automation.AutomationRule
import com.khodroyar.app.automation.RepeatFrequency
import com.khodroyar.app.automation.RuleKind
import com.khodroyar.app.automation.validDate
import com.khodroyar.app.automation.validTime
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.core.rates.ServiceType
import com.khodroyar.app.ai.albert.PersianIntent
import com.khodroyar.app.ai.albert.PersianIntentPrediction
import com.khodroyar.app.ai.albert.PersianHintSafety
import org.json.JSONObject
import java.time.ZoneId

/** Ephemeral conversation context, not authority. Only validated tools + approval may change data. */
data class LocalDialogueContext(val title: String = "", val detail: String = "", val suggestions: List<String> = emptyList())

/**
 * Bounded Persian domain dialogue, not a general LLM. Slots survive short follow-ups and corrections.
 * It never runs a task, reads records, calls a model, or interprets old chat/record contents as commands.
 */
class LocalDialogueEngine(
    private val today: () -> String = Jalali::todayString,
    private val zone: () -> String = { ZoneId.systemDefault().id },
    private val rules: () -> List<AutomationRule> = { emptyList() },
    private val legacy: LocalIntentParser = LocalIntentParser(),
) {
    private enum class Kind { EXPENSE, FUEL, MAINTENANCE, REMINDER, SERVICE, TRAFFIC, EDIT_RULE }
    private data class Draft(
        val kind: Kind,
        var title: String = "",
        var category: String = "other",
        var amount: Double? = null,
        var liters: Double? = null,
        var km: Double? = null,
        var maintenanceType: String = "repair",
        var fuelType: String = "gov",
        // This is the app's tariff/category, NOT the time of day or a guessed price.
        var serviceType: String? = null,
        var recurring: Boolean = false,
        var frequency: RepeatFrequency? = null,
        var weekdays: Set<Int> = emptySet(),
        var date: String = "",
        var dateSpecified: Boolean = false,
        var endDate: String = "",
        var time: String = "21:00",
        var ruleId: String? = null,
    )
    private var draft: Draft? = null
    private var repeatDates: Pair<String, String>? = null
    private var waiting = ""
    private var management: String? = null
    private var lastRuleId: String? = null
    var context = LocalDialogueContext()
        private set

    fun reset() { repeatDates = null; draft = null; waiting = ""; management = null; lastRuleId = null; context = LocalDialogueContext() }
    fun discardDraft() { repeatDates = null; draft = null; management = null; waiting = ""; context = LocalDialogueContext() }
    fun completed(call: ValidatedCall, success: Boolean) {
        if (success) {
            if (call.spec.name.startsWith("create_") && call.spec.name.endsWith("_rule")) lastRuleId = call.str("operation_id")
            if (call.spec.name in setOf("update_expense_rule", "set_automation_enabled")) lastRuleId = call.str("id")
            discardDraft()
        }
    }

    /** Short slot replies and revisions must not be re-classified as a new task. */
    val acceptsModelHint: Boolean get() = draft == null && repeatDates == null && management == null

    fun parse(raw: String, prediction: PersianIntentPrediction? = null): LocalIntent {
        if (raw.isBlank() || raw.length > AiLimits.MAX_USER_CHARS) return reply("یک پیام کوتاه بنویسید؛ اطلاعاتی تغییر نکرد.")
        val t = normalize(raw).trim(' ', '.', '!', '؟', '?')
        val hint = if (acceptsModelHint) PersianHintSafety.allow(LocalIntentParser.normalize(raw), prediction) else null
        if (t in setOf("لغو", "لغوش کن", "بیخیال", "بی خیال", "کنسل", "ولش کن", "نه", "نه ممنون", "انصراف")) {
            discardDraft()
            return reply("باشه، درخواست در حال تنظیم لغو شد؛ قانون‌های قبلاً فعال‌شده تغییر نکردند.")
        }
        if (Regex("فقط مثال|مثال بزن|فرض کن|(?<![\\p{L}\\p{N}])اگر(?![\\p{L}\\p{N}])|وقتی که").containsMatchIn(t)) {
            return reply("برای این پیام کاری فعال نمی‌کنم. قانون‌های سادهٔ روزانه، روزهای کاری یا روزهای مشخص هفته را پشتیبانی می‌کنم؛ شرط‌های آزاد را حدس نمی‌زنم.")
        }
        if (t in setOf("راهنما", "کمک", "چه کارهایی میکنی", "چه کار میکنی", "چی بلدی", "چیکار میکنی")) return reply(HELP, listOf("روزای کاری هزینه قهوه‌مو ثبت کن", "کارهای خودکارم", "ترافیک الان چطوره"))
        if (t in setOf("سلام", "درود", "سلام خوبی", "سلام چطوری")) return reply("سلام! راحت و کوتاه بگو چه کاری در خودرویار انجام بدهم. اگر چیزی کم باشد، ازت می‌پرسم؛ تا تأیید نکنی تغییری نمی‌دهم.", listOf("روزای کاری هزینه قهوه‌مو ثبت کن", "هر روز برام سرویس ثبت کن", "گزارش این ماه"))
        if (Regex("نکن|نزن|نمی خوام|نمیخوام|نشود|نشه").containsMatchIn(t) && !isPause(t)) {
            discardDraft()
            return reply("چیزی ثبت یا فعال نمی‌کنم. اگر می‌خواهی یک کار خودکار قبلی را متوقف کنی، بگو «قانون قهوه را متوقف کن».")
        }
        if (has(t, "سوخت", "بنزین") && has(t, "تعمیر", "روغن", "لنت", "لاستیک") && has(t, "ثبت", "بزن")) return reply("سوخت و تعمیر دو ثبت جدا هستند؛ هر بار یکی را تنظیم کنیم.")
        if (Regex("(?: و |؛|;)\\s*(?:ثبت|اضافه|ذخیره|هر روز)").containsMatchIn(t) ||
            categoryHits(t).size > 1 && (has(t, "ثبت", "بزن", "روزانه", "هر روز") || draft != null || repeatDates != null)) {
            return reply("این پیام چند کار یا چند هزینه دارد. برای جلوگیری از ثبت اشتباه، هر کدام را جدا تنظیم کنیم؛ اول کدام؟")
        }
        // Short, explicit app questions. No record text is ever re-parsed as an instruction.
        if (has(t, "اجرا نشده", "اجرا نشدن", "انجام نشده", "عقب افتاده", "ناموفق") && has(t, "خودکار", "قانون", "کارام", "کارها", "کارای")) return tool("get_automation_health")
        if (has(t, "مقایسه", "نسبت به", "در مقایسه", "سود هر کیلومتر", "خالص هر کیلومتر", "سود هر ساعت", "خالص هر ساعت") ||
            has(t, "ماه قبل", "ماه پیش", "هفته قبل") && has(t, "چطور", "چجور", "بهتر", "بدتر")) {
            if (!has(t, "ماه", "هفته", "هر کیلومتر", "هر ساعت")) return reply("مقایسهٔ این ماه یا این هفته را می‌توانم نشان بدهم؛ کدام؟", listOf("مقایسه این ماه", "مقایسه این هفته"))
            return tool("get_period_comparison", JSONObject().put("period", if (has(t, "هفته")) "week" else "month"))
        }
        if (has(t, "تکرار", "دوباره", "همون", "همان") && has(t, "هزینه", "قهوه", "ناهار", "پارکینگ", "خرج") && has(t, "دیروز") && has(t, "ثبت", "بزن", "تکرار")) {
            if (has(t, "هر روز", "روزانه", "روزهای کاری", "خودکار", "تکراری")) return reply("برای هزینهٔ تکراری، مبلغ و برنامه را جدا تأیید کنیم؛ مثلاً بگو «هر روز قهوه ثبت کن».")
            if (draft != null) return reply("اول درخواست قبلی را کامل یا لغو کن تا دو ثبت قاطی نشوند.", listOf("لغو"))
            if (has(t, "فردا", "پس فردا")) return reply("تکرار دستی را فقط برای امروز یا گذشته ثبت می‌کنم؛ برای آینده یک قانون خودکار تنظیم کن.")
            val title = categoryHits(t).singleOrNull()?.first ?: if (has(t, "پارکینگ")) "پارکینگ" else null
            repeatDates = Jalali.addDays(today(), -1) to today()
            return repeatExpense(title)
        }
        navigation(t)?.let { return it }
        // Only three fixed, read-only domains can be suggested by the classifier.
        // All answers still come from the same grounded app tools, never model text.
        when (hint) {
            PersianIntent.VEHICLE_STATUS -> return tool("get_vehicle_status")
            PersianIntent.AUTOMATION_HEALTH -> return tool("get_automation_health")
            PersianIntent.COMPARISON -> {
                if (has(t, "پارسال", "امسال", "سال قبل", "سال گذشته") || datePattern.containsMatchIn(t))
                    return reply("مقایسهٔ این ماه یا این هفته با بازهٔ قبل را پشتیبانی می‌کنم؛ تاریخ دیگری را حدس نمی‌زنم.")
                if (!has(t, "ماه", "هفته", "هر کیلومتر", "هر ساعت", "برای هر ساعت", "برای هر کیلومتر"))
                    return reply("مقایسهٔ این ماه یا این هفته؟", listOf("مقایسه این ماه", "مقایسه این هفته"))
                return tool("get_period_comparison", JSONObject().put("period", if (has(t, "هفته")) "week" else "month"))
            }
            else -> Unit
        }
        if (has(t, "کارهای خودکار", "قانون هام", "قوانین", "قانون ها", "برنامه های خودکار", "کارای خودکار")) return tool("list_automations")
        if (has(t, "الگوی سرویس", "الگو سرویس", "الگوی همیشگی") && !has(t, "ثبت", "روز", "بزن")) return tool("get_service_preset")

        // Explicit read can interrupt slot filling without converting a report into a write.
        if (draft == null && isReport(t)) return legacy.parse(reportText(t))
        if (isReport(t) && has(t, "گزارش", "چقدر خرج", "چقدر درآمد")) return legacy.parse(reportText(t))

        if (has(t, "روز در میان", "یک روز در میان", "یه روز در میون", "ماهانه", "هر ماه", "هفتگی", "یک هفته در میان")) {
            return reply("فعلاً تکرار روزانه، روزهای کاری یا روزهای انتخابی هفته را می‌توانم تنظیم کنم. برنامهٔ دیگری را به‌جای درخواستت فعال نمی‌کنم.")
        }
        val traffic = has(t, "ترافیک", "ترافیکه", "شلوغه", "شلوغی مسیر")
        if (traffic && !isRecurring(t)) {
            if (has(t, "خبر", "هشدار", "اعلان", "اطلاع") || isPause(t)) {
                discardDraft()
                return tool("set_traffic_alerts", JSONObject().put("state", if (isPause(t)) "off" else "on"))
            }
            return tool("get_traffic_status")
        }
        if (repeatDates != null && has(t, "فردا", "پس فردا")) return reply("برای آینده یک قانون جدا با مبلغ و برنامهٔ مشخص تنظیم کن؛ این تکرار را برای آینده ثبت نمی‌کنم.", listOf("لغو"))
        if (repeatDates != null && draft == null && t.length <= 80 &&
            !has(t, "ثبت", "بزن", "اضافه", "هر روز", "روزانه", "تومان", "ریال", "لیتر", "ساعت", "چطور", "قانون", "مقایسه")) {
            if (t in setOf("بله", "آره", "اره", "اوکی", "باشه", "تایید", "تأیید")) return reply("عنوان هزینهٔ آن روز را بگو؛ مثلاً قهوه. هنوز چیزی ثبت نشده.")
            val title = categoryHits(t).singleOrNull()?.first ?: t.removePrefix("عنوانش ").removePrefix("عنوان ").trim()
            return repeatExpense(title)
        }
        repeatDates = null
        if (management != null) return manage(t, requireNotNull(management))
        if (draft == null && (isPause(t) || isResume(t) || isRuleEdit(t) || has(t, "حذف") && has(t, "قانون"))) {
            return manage(t, when { has(t, "حذف") -> "delete"; isPause(t) -> "off"; isResume(t) -> "on"; else -> "edit" })
        }
        if (draft != null && t in setOf("بله", "آره", "اره", "اوکی", "باشه", "تایید", "تأیید")) {
            return ready(requireNotNull(draft)) // Confirmation is deliberately handled by the ViewModel, not here.
        }
        val existingDraft = draft
        if (draft == null) {
            // A model hint widens wording, not privileges. Existing explicit domain
            // recognition wins; the model can only open a validated, unsaved draft.
            val hintedKind = when (hint) {
                PersianIntent.EXPENSE -> Kind.EXPENSE
                PersianIntent.FUEL -> Kind.FUEL
                PersianIntent.MAINTENANCE -> Kind.MAINTENANCE
                PersianIntent.SERVICE -> Kind.SERVICE
                else -> null
            }
            val hasEntryRequest = Regex("ثبت|بنویس|یادداشت|وارد|ذخیره|اضافه|بیار|بیفزا|نگه دار|دادم|زدم|انداختم|خریدم|عوض کردم|نو کردم|تعمیر کردم|پر کردم|گرفتم|انجام دادم").containsMatchIn(t) || isRecurring(t)
            val explicitKind = detectKind(t)
            if (explicitKind == null && !hasEntryRequest && serviceCategoryInput(t) != null && !isReport(t)) {
                return reply("درخواست فعالی برای اصلاح سرویس ندارم. برای ثبت تازه بگو «سرویس شب امروز را ثبت کن»؛ چیزی ثبت نشده.")
            }
            val kind = explicitKind ?: hintedKind?.takeIf { hasEntryRequest } ?: return legacy.parse(t)
            draft = Draft(kind, date = today(), recurring = isRecurring(t))
        }
        val d = requireNotNull(draft)
        val incoming = detectKind(t)
        if (existingDraft != null && incoming != null && incoming != d.kind && d.kind != Kind.EDIT_RULE) {
            return reply("هنوز ${label(d)} را کامل نکرده‌ایم. اگر کار دیگری می‌خواهی، اول «لغو» بگو تا این دو درخواست قاطی نشوند.", listOf("لغو"))
        }
        val before = d.copy()
        val serviceCategory = if (d.kind == Kind.SERVICE) serviceCategoryInput(t) else null
        update(d, t, serviceCategory)?.let { return it }
        if (existingDraft != null && before == d && incoming == null && serviceCategory == null && !has(t, "فردا", "امروز", "امشب", "دیشب", "دیروز", "روزهای کاری", "هر روز", "ساعت", "الگو", "ذخیره کردم", "تومان", "لیتر")) {
            return reply("این پاسخ را به درخواست فعلی ربط ندادم. جواب سؤال آخر را کوتاه بگو، یا برای شروع کار دیگری «لغو» بنویس؛ چیزی تغییر نکرد.", context.suggestions)
        }
        return ready(d)
    }

    private data class ServiceCategoryInput(val type: String? = null, val clarification: String? = null)

    /** Only explicit category fields/bare slot answers; a clock's 'night' is not a tariff. */
    private fun serviceCategoryInput(t: String): ServiceCategoryInput? {
        val withoutClock = t.replace(Regex("(?:ساعت\\s*)?\\d{1,2}:\\d{2}\\s*(?:شب|صبح|عصر)?|ساعت\\s*\\d{1,2}(?:\\s*(?:و نیم|نیم))?\\s*(?:شب|صبح|عصر)?"), " ")
        val clean = withoutClock.replace(Regex("^(?:نه|ن|خیر)[،,:\\s]+"), "").trim()
        val introduced = Regex("(?<![\\p{L}])(?:نوع(?:\\s+سرویس)?ش?|سرویس(?:م)?|سفر(?:م)?)\\s+(?:از نوع\\s+)?(.+)").find(clean)
        val bare = clean.replace(Regex("\\s+(?:بود|بوده|است|باشه|نبود|نیست)$"), "").trim()
        val bareCategory = serviceCategories.any { (_, names) -> bare in names }
        val rawField = introduced?.groupValues?.get(1) ?: if (bareCategory || waiting == "service_type") clean else return null
        // Calendar inclusion/exclusion does not specify a tariff. A directly negated
        // category ("سرویس غیر تعطیل") is ambiguous instead of becoming HOLIDAY.
        val calendarQualifiers = if (Regex("^غیر\\s*").containsMatchIn(rawField)) "حتی|به جز|بجز" else "حتی|به جز|بجز|غیر"
        val field = rawField.replace(Regex("(?<![\\p{L}])(?:$calendarQualifiers)\\s+تعطیل(?:ات|ی)?(?![\\p{L}])"), " ")
        val negated = Regex("(?<![\\p{L}])(?:غیر\\s*|نه\\s+)").containsMatchIn(field)
        val choices = serviceCategories.filter { (_, names) -> names.any { name ->
            Regex("(?<![\\p{L}])${Regex.escape(name)}(?![\\p{L}])").containsMatchIn(field)
        } }
        if (choices.isEmpty() && !negated && waiting != "service_type" && !has(clean, "نوع", "سرویس روز", "سفر روز", "حقوق", "مرخصی")) return null
        if (choices.size != 1 || negated || has(field, "نبود", "نیست", "نیس", "حقوق", "مرخصی")) {
            return ServiceCategoryInput(clarification = "نوع سرویس کدام است: شب، تعطیل، درخواستی، در اختیار، ثابت یا مأموریت؟ بقیهٔ درخواست حفظ شده؛ هنوز چیزی ثبت نشده.")
        }
        return ServiceCategoryInput(type = choices.single().first)
    }

    private fun update(d: Draft, t: String, serviceCategory: ServiceCategoryInput? = null): LocalIntent.Reply? {
        serviceCategory?.clarification?.let { return question(d, it, "service_type", listOf("سرویس شب", "سرویس درخواستی", "لغو")) }
        if (has(t, "تقریبا", "حدودا", "شاید", "حداکثر", "حداقل", "یا ") && !has(t, "ساعت")) return question(d, "مبلغ و برنامه را قطعی مشخص کنیم؛ مقدار دقیق چقدر است؟", "amount")
        serviceCategory?.type?.let { d.serviceType = it; if (waiting == "service_type") waiting = "" }
        if (isRecurring(t)) d.recurring = true
        if (has(t, "فقط امروز", "فقط یک بار", "فقط یه بار", "یکبار", "یه بار")) { d.recurring = false; d.frequency = null; d.weekdays = emptySet() }
        when {
            has(t, "روزهای کاری", "روز کاری", "غیر تعطیل", "به جز تعطیل", "بجز تعطیل") -> { d.frequency = RepeatFrequency.WORKDAYS; d.weekdays = emptySet(); d.recurring = true }
            has(t, "هر روز", "هرروز", "روزانه", "همه روزها", "حتی تعطیل") -> { d.frequency = RepeatFrequency.DAILY; d.weekdays = emptySet(); d.recurring = true }
        }
        if (!has(t, "روزهای کاری", "روز کاری")) {
            val days = dayNames.mapIndexedNotNull { index, name -> if (Regex("(?<![\\p{L}])${Regex.escape(name)}(?: ها)?(?![\\p{L}])").containsMatchIn(t)) index else null }.toSet()
            if (days.isNotEmpty()) {
                if (!has(t, "فقط", "هر", "روزهای", "شنبه ها", "جمعه ها") && waiting != "frequency") return question(d, "این روزها برای تکرار هفتگی است یا یک تاریخ مشخص؟ برای تکرار مثلاً بگو «فقط شنبه و دوشنبه».", "frequency", listOf("فقط شنبه و دوشنبه", "روزهای کاری", "هر روز"))
                d.frequency = RepeatFrequency.WEEKDAYS; d.weekdays = days; d.recurring = true
            }
        }
        dateUpdate(d, t)?.let { return it }
        val tm = Regex("(?:ساعت\\s*)?(?<!\\d)(\\d{1,2}):(\\d{2})(?!\\d)").find(t)
        val hour = Regex("ساعت\\s*(\\d{1,2})(?![\\d:])(?:\\s*(و نیم|نیم))?").find(t)
        if (tm != null || hour != null) {
            var h = (tm ?: hour)!!.groupValues[1].toInt()
            val minute = if (tm != null) tm.groupValues[2].toInt() else if (hour!!.groupValues.getOrNull(2).orEmpty().isNotBlank()) 30 else 0
            // A service tariff's "شب" elsewhere in the sentence must not turn
            // "ساعت ۸ صبح" / "08:00" into 20:00. Read only the clock's period or
            // an explicit recurring period ("هر شب" normalizes to "هر روز شب").
            val clockPeriod = if (d.kind == Kind.SERVICE) {
                val afterClock = t.substring((tm ?: hour)!!.range.last + 1)
                Regex("^\\s*(بعد از ظهر|بعدازظهر|بامداد|صبح|عصر|شب)(?![\\p{L}])").find(afterClock)?.groupValues?.get(1)
                    ?: Regex("(?<![\\p{L}])هر روز\\s+(صبح|عصر|شب)(?![\\p{L}])").find(t)?.groupValues?.get(1).orEmpty()
            } else t
            if (has(clockPeriod, "عصر", "شب", "بعد از ظهر", "بعدازظهر") && h in 1..11) h += 12
            if (has(clockPeriod, "صبح", "بامداد") && h == 12) h = 0
            val value = "$h".padStart(2, '0') + ":" + "$minute".padStart(2, '0')
            if (!validTime(value)) return question(d, "زمان معتبر نیست؛ مثلاً ۲۱:۰۰ یا ساعت ۹ شب بنویس.", "time", listOf("ساعت ۸ صبح", "ساعت ۹ شب"))
            d.time = value
        } else if (has(t, "صبح", "عصر", "شب") && d.recurring && serviceCategory?.type == null && !has(t, "سرویس شب")) {
            return question(d, "چه ساعتی؟ مثلاً «ساعت ۸ صبح». اجرای پس‌زمینه ممکن است با تأخیر باشد.", "time", listOf("ساعت ۸ صبح", "ساعت ۹ شب"))
        }
        if (d.kind == Kind.EXPENSE) {
            val hit = categoryHits(t).singleOrNull()
            if (hit != null) { d.title = hit.first; d.category = hit.second }
            else if (d.title.isEmpty() || waiting == "title" || has(t, "عنوانش", "اسمش")) {
                val title = titleFrom(t)
                if (title.isNotBlank()) d.title = title
            }
        }
        if (d.kind == Kind.REMINDER && (d.title.isBlank() || waiting == "title")) {
            d.title = t.replace(datePattern, "").replace(Regex("یادم بنداز|یادآور|یاداوری|ثبت|کن|تاریخ|انقضا|برای|امروز|فردا|از"), " ").trim().replace(Regex("\\s+"), " ").take(80)
        }
        if (d.kind in setOf(Kind.EXPENSE, Kind.FUEL, Kind.MAINTENANCE, Kind.EDIT_RULE)) {
            val money = money(t, waiting == "amount")
            when (money) {
                is LocalNumbers.Value.Found -> {
                    if (money.number < 0 || money.number > 1_000_000_000 || money.number == 0.0 && d.kind != Kind.MAINTENANCE) {
                        return question(d, "مبلغ باید مثبت و در محدودهٔ مجاز باشد. مبلغ دقیق چند تومان است؟", "amount")
                    }
                    d.amount = money.number
                }
                LocalNumbers.Value.Ambiguous, LocalNumbers.Value.Invalid -> return question(d, "یک مبلغ دقیق بنویس؛ مثلاً «۵۰ هزار تومان». چند مبلغ یا عدد نامعتبر را حدس نمی‌زنم.", "amount")
                LocalNumbers.Value.Missing -> {
                    if (d.kind == Kind.MAINTENANCE && has(t, "رایگان", "بدون هزینه")) d.amount = 0.0
                    val small = LocalNumbers.money("$t تومان")
                    if (waiting == "amount" && small is LocalNumbers.Value.Found && small.number in 0.0..<1000.0) {
                        return question(d, "منظورت $t تومان است یا $t هزار تومان؟ واحد را روشن کنیم.", "amount", listOf("$t تومان", "$t هزار تومان"))
                    }
                }
            }
        }
        if (d.kind == Kind.FUEL) {
            val liters = LocalNumbers.liters(t).let { if (it == LocalNumbers.Value.Missing && waiting == "liters") LocalNumbers.liters("$t لیتر") else it }
            if (liters is LocalNumbers.Value.Found) {
                if (liters.number !in 0.1..500.0) return question(d, "مقدار سوخت باید بین ۰٫۱ تا ۵۰۰ لیتر باشد.", "liters")
                d.liters = liters.number
            }
            if (has(t, "آزاد", "ازاد")) d.fuelType = "free"
            if (has(t, "نیمه")) d.fuelType = "semi"
        }
        if (d.kind == Kind.MAINTENANCE) {
            maintenanceTypes.firstOrNull { (_, words) -> words.any { t.contains(it) } }?.let { d.maintenanceType = it.first }
            if (d.title.isBlank()) d.title = t.take(80)
            val km = LocalNumbers.kilometers(t)
            if (km is LocalNumbers.Value.Found) d.km = km.number
        }
        return null
    }

    private fun dateUpdate(d: Draft, t: String): LocalIntent.Reply? {
        val matches = datePattern.findAll(t).toList()
        if (matches.size > 2 || matches.size == 2 && !has(t, "تا ")) return question(d, "بازه روشن نیست؛ یک تاریخ شروع و در صورت نیاز «تا تاریخ پایان» بنویس.", "date")
        for (match in matches) {
            val parts = match.value.replace('-', '/').replace('.', '/').split('/').map { it.toIntOrNull() }
            val value = if (parts.all { it != null }) Jalali.format(parts[0]!!, parts[1]!!, parts[2]!!) else ""
            if (!validDate(value)) return question(d, "این تاریخ شمسی معتبر نیست. مثلاً ۱۴۰۵/۰۸/۲۰ بنویس.", "date")
            val before = t.substring(0, match.range.first).trimEnd()
            if (before.endsWith("تا") || before.endsWith("تا تاریخ")) d.endDate = value else { d.date = value; d.dateSpecified = true }
        }
        if (matches.isEmpty()) {
            if (has(t, "فردا", "امروز", "امشب", "دیشب", "دیروز", "پریروز")) d.dateSpecified = true
            when {
                has(t, "پس فردا", "پسفردا") -> d.date = Jalali.addDays(today(), 2)
                has(t, "فردا") -> d.date = Jalali.addDays(today(), 1)
                has(t, "پریروز") -> d.date = Jalali.addDays(today(), -2)
                has(t, "دیروز", "دیشب") -> d.date = Jalali.addDays(today(), -1)
                has(t, "امروز", "امشب") -> d.date = today()
                has(t, "از هفته", "از ماه", "آخر ماه", "اول ماه") -> return question(d, "برای شروع، تاریخ دقیق را بگو یا «از فردا» بنویس.", "date", listOf("از امروز", "از فردا"))
            }
        }
        if (d.endDate.isNotBlank() && d.endDate < d.date) return question(d, "پایان قبل از شروع است؛ تاریخ پایان را اصلاح کن.", "date")
        return null
    }

    private fun ready(d: Draft): LocalIntent {
        if (d.kind == Kind.SERVICE && waiting == "service_type") return question(d,
            "کدام نوع سرویس؟ مثلاً «سرویس شب» یا «سرویس درخواستی». تا نوع روشن نشود خلاصهٔ تازه نمی‌سازم.",
            "service_type", listOf("سرویس شب", "سرویس درخواستی", "لغو"))
        if (d.kind == Kind.EDIT_RULE) {
            if (d.amount == null) return question(d, "مبلغ جدید این قانون چند تومان باشد؟ ثبت‌های قبلی تغییر نمی‌کنند.", "amount", listOf("۶۰ هزار تومان", "لغو"))
            return prepared(d, "update_expense_rule", JSONObject().put("id", d.ruleId).put("amount", d.amount).also {
                if (d.frequency != null) scheduleArgs(d, it)
            })
        }
        if (d.kind == Kind.EXPENSE && d.title.isBlank()) return question(d, "برای چه هزینه‌ای؟ مثلاً قهوه، ناهار یا پارکینگ.", "title", listOf("قهوه", "ناهار", "پارکینگ"))
        if (d.kind in setOf(Kind.EXPENSE, Kind.FUEL, Kind.MAINTENANCE) && d.amount == null) return question(d,
            if (d.kind == Kind.FUEL) "مبلغ کل سوخت چند تومان شد؟" else "مبلغ ${d.title.ifBlank { "هزینه" }} چند تومان باشد؟", "amount", listOf("۵۰ هزار تومان", "۱۰۰ هزار تومان", "لغو"))
        if (d.kind == Kind.FUEL && d.liters == null) return question(d, "چند لیتر سوخت زدی؟", "liters", listOf("۴۰ لیتر", "۵۰ لیتر"))
        if (d.kind == Kind.REMINDER) {
            if (d.title.isBlank()) return question(d, "عنوان یادآور چیست؟", "title")
            if (!d.dateSpecified) return question(d, "موعد یادآور چه تاریخی است؟ تاریخ شمسی یا «فردا» بگو.", "date", listOf("فردا", "لغو"))
            return prepared(d, "create_reminder", JSONObject().put("title", d.title).put("expiry_date", d.date))
        }
        if (d.recurring) {
            if (d.kind !in setOf(Kind.EXPENSE, Kind.SERVICE, Kind.TRAFFIC)) return reply("ثبت خودکار برای هزینهٔ ثابت، سرویس رفت‌وآمد و گزارش ترافیک است. سوخت یا تعمیر انجام‌نشده را تکراری ثبت نمی‌کنم؛ می‌توانم یک یادآور بسازم.")
            if (d.frequency == null) return question(d, "هر روز انجام شود یا فقط روزهای کاری؟", "frequency", listOf("هر روز", "روزهای کاری", "فقط شنبه و دوشنبه"))
            val args = scheduleArgs(d, JSONObject())
            if (d.kind == Kind.SERVICE) d.serviceType?.let { args.put("service_type", it) }
            val name = when (d.kind) {
                Kind.EXPENSE -> { args.put("title", d.title).put("amount", d.amount).put("category", d.category); "create_expense_rule" }
                Kind.SERVICE -> "create_service_rule"
                else -> "create_traffic_rule"
            }
            return prepared(d, name, args)
        }
        val args = JSONObject().put("date", d.date)
        return when (d.kind) {
            Kind.EXPENSE -> prepared(d, "add_expense", args.put("title", d.title).put("amount", d.amount).put("category", d.category))
            Kind.FUEL -> prepared(d, "add_fuel", args.put("liters", d.liters).put("total_cost", d.amount).put("fuel_type", d.fuelType))
            Kind.MAINTENANCE -> {
                args.put("amount", d.amount).put("description", d.title)
                d.km?.let { args.put("km", it) }
                if (d.maintenanceType == "repair") prepared(d, "add_repair", args) else prepared(d, "add_car_service", args.put("service_type", d.maintenanceType))
            }
            Kind.SERVICE -> prepared(d, "add_service_from_preset", args.also { d.serviceType?.let { type -> it.put("service_type", type) } })
            Kind.TRAFFIC -> tool("get_traffic_status")
        }
    }

    private fun scheduleArgs(d: Draft, args: JSONObject): JSONObject = args.put("frequency", d.frequency!!.wire)
        .put("start_date", d.date).put("time", d.time).put("zone", zone()).also {
            if (d.endDate.isNotBlank()) it.put("end_date", d.endDate)
            if (d.frequency == RepeatFrequency.WEEKDAYS) it.put("weekdays", d.weekdays.sorted().joinToString(","))
        }

    private fun prepared(d: Draft, name: String, args: JSONObject): LocalIntent {
        waiting = "review"
        context = LocalDialogueContext("در حال تنظیم ${label(d)}", "خلاصه را بررسی کن؛ هنوز اجرا نشده. می‌توانی همین‌جا اصلاحش کنی.",
            if (d.recurring) listOf("نه، از فردا", "فقط روزهای کاری", "ساعت ۹ شب", "لغو") else listOf("لغو"))
        return tool(name, args, preserveContext = true)
    }

    private fun manage(t: String, action: String): LocalIntent {
        management = action
        val all = rules()
        val explicit = all.filter { r -> t.contains(r.id.take(8)) || t.contains(normalize(r.title)) }
        val target = when {
            explicit.size == 1 -> explicit.single()
            explicit.isEmpty() && has(t, "همون", "همان", "مبلغش", "متوقفش", "فعالَش", "فعال کنش") -> all.find { it.id == lastRuleId }
            explicit.isEmpty() && all.size == 1 -> all.single()
            else -> null
        }
        if (target == null) {
            if (all.isEmpty()) { management = null; return reply("هنوز قانونی برای تغییر نداری. مثلاً بگو «روزای کاری هزینه قهوه‌مو ثبت کن».") }
            return reply("کدام قانون؟ نام یا شناسهٔ کوتاهش را بگو؛ یکی را حدس نمی‌زنم.", all.take(4).map { "قانون ${it.id.take(8)}" })
        }
        management = null
        if (action == "edit") {
            if (target.kind != RuleKind.EXPENSE) return reply("تغییر مبلغ فقط برای قانون هزینه است. برای تغییر الگوی سرویس یا برنامهٔ ترافیک، اول قانون قبلی را متوقف و قانون جدید را با تأیید بساز.", listOf("قانون ${target.id.take(8)} را متوقف کن"))
            draft = Draft(Kind.EDIT_RULE, title = target.title, ruleId = target.id, date = target.schedule.startDate,
                endDate = target.schedule.endDate, time = target.schedule.time)
            waiting = "amount"
            update(requireNotNull(draft), t)?.let { return it }
            return ready(requireNotNull(draft))
        }
        discardDraft()
        return if (action == "delete") tool("remove_automation_rule", JSONObject().put("id", target.id))
        else tool("set_automation_enabled", JSONObject().put("id", target.id).put("state", action))
    }

    private fun navigation(t: String): LocalIntent.Tool? {
        if (!has(t, "باز کن", "بازکن", "برو", "ببر", "وارد", "نشان بده تب", "صفحه ") || has(t, "ثبت کن", "ثبتش", "هر روز", "روزانه")) return null
        val section = when {
            has(t, "ترافیک") -> AgentSection.TRAFFIC
            has(t, "سوخت", "بنزین") -> AgentSection.FUEL
            has(t, "مالی", "هزینه", "قسط", "اقساط", "وام") -> AgentSection.FINANCE
            has(t, "تعمیر", "روغن", "نگهداری") -> AgentSection.MAINTENANCE
            has(t, "بیمه", "مدارک") -> AgentSection.INSURANCE
            has(t, "نرخ", "تعرفه") -> AgentSection.RATES
            has(t, "گزارش") -> AgentSection.REPORTS
            has(t, "تقویم") -> AgentSection.CALENDAR
            has(t, "تنظیم") -> AgentSection.SETTINGS
            has(t, "سرویس", "سفر") -> AgentSection.SERVICES
            has(t, "جستجو", "جست و جو") -> AgentSection.SEARCH
            has(t, "خانه", "داشبورد") -> AgentSection.HOME
            else -> return null
        }
        return tool("open_app_section", JSONObject().put("section", section.wire), preserveContext = true)
    }

    private fun detectKind(t: String): Kind? {
        val writing = has(t, "ثبت", "بزن", "اضافه", "ذخیره", "دادم", "خریدم", "خرج کردم", "زدم", "یادم بنداز") || isRecurring(t)
        if (!writing) return null
        return when {
            has(t, "ترافیک") -> Kind.TRAFFIC
            has(t, "یادآور", "یاداوری", "یادم بنداز") -> Kind.REMINDER
            has(t, "سوخت", "بنزین", "گازوییل", "گازوئیل") -> Kind.FUEL
            has(t, "تعمیر", "روغن", "لنت", "لاستیک", "تسمه", "شمع", "فیلتر", "کارواش") -> Kind.MAINTENANCE
            has(t, "سرویس", "سفر", "رفت و آمد") -> Kind.SERVICE
            has(t, "هزینه", "خرج", "خرید") || categoryHits(t).isNotEmpty() -> Kind.EXPENSE
            else -> null
        }
    }

    private fun money(t: String, amountAnswer: Boolean): LocalNumbers.Value {
        val explicit = LocalNumbers.money(t)
        if (explicit != LocalNumbers.Value.Missing) return explicit
        val clean = t.replace(Regex("^(?:مبلغش|مبلغ|بکن|بشه|نه|رو|را)\\s+|\\s+(?:باشه|بشه|بکن|کن)$"), "").trim()
        if (has(clean, "هزار", "میلیون", "میلیارد") && !has(clean, "کیلومتر", "لیتر")) return LocalNumbers.money("$clean تومان")
        if (amountAnswer && (clean.replace(",", "").toDoubleOrNull() ?: 0.0) >= 1000) return LocalNumbers.money("$clean تومان")
        return LocalNumbers.Value.Missing
    }

    private fun categoryHits(t: String): List<Pair<String, String>> = categories.filter { (_, words) -> words.any { word ->
        Regex("(?<![\\p{L}])${Regex.escape(word)}(?:مو|م|رو|هام|ام)?(?![\\p{L}])").containsMatchIn(t)
    } }.map { it.first }

    private fun titleFrom(t: String): String {
        val candidate = t.replace(datePattern, "").replace(Regex("ساعت\\s*\\d{1,2}(?::\\d{2})?"), "")
            .replace(Regex("از فردا|از امروز|روزهای کاری|هر روز|هرروز|روزانه|خودکار|هزینه|خرج|ثبتش کن|ثبت کن|ثبت|بزن|برای من|برای|لطفا|عنوانش|اسمش|باشه|فقط|را| رو "), " ")
            .replace(Regex("[\\d,.]+|تومان|تومن|ریال|هزار|میلیون"), " ").replace(Regex("\\s+"), " ").trim()
        return candidate.take(80).takeIf { it.length >= 2 }.orEmpty()
    }

    private fun label(d: Draft): String = when (d.kind) {
        Kind.EXPENSE -> "هزینهٔ ${d.title}"; Kind.FUEL -> "سوخت"; Kind.MAINTENANCE -> "نگهداری خودرو"
        Kind.REMINDER -> "یادآور"
        Kind.SERVICE -> d.serviceType?.let { "سرویس ${ServiceType.fromWireOrNull(it)!!.label}" } ?: "سرویس رفت‌وآمد"
        Kind.TRAFFIC -> "ترافیک"; Kind.EDIT_RULE -> "قانون ${d.title}"
    }
    private fun question(d: Draft, text: String, field: String, suggestions: List<String> = listOf("لغو")): LocalIntent.Reply {
        waiting = field
        context = LocalDialogueContext("در حال تنظیم ${label(d)}", text, suggestions)
        return LocalIntent.Reply(text)
    }
    private fun reply(text: String, suggestions: List<String> = emptyList()): LocalIntent.Reply {
        context = context.copy(suggestions = suggestions)
        return LocalIntent.Reply(text)
    }
    private fun tool(name: String, args: JSONObject = JSONObject(), preserveContext: Boolean = false): LocalIntent.Tool {
        if (!preserveContext && draft == null) { repeatDates = null; context = LocalDialogueContext() }
        return LocalIntent.Tool(name, args)
    }
    private fun repeatExpense(title: String?): LocalIntent.Tool {
        val (source, target) = requireNotNull(repeatDates)
        context = LocalDialogueContext("تکرار هزینه", "$source ← $target", listOf("لغو"))
        return tool("repeat_expense", JSONObject().put("source_date",source).put("date",target).apply { if (!title.isNullOrBlank()) put("title",title) }, preserveContext = true)
    }
    private fun isReport(t: String) = has(t, "گزارش", "چقدر خرج", "چقدر درآمد", "چقدر درآوردم", "چقد خرج", "خرج این ماه", "مصرف سوخت", "لیست", "سوابق", "وضعیت خودرو", "وضعیت ماشین") && !has(t, "ثبت کن", "ثبتش", "هر روز", "روزانه")
    private fun reportText(t: String) = t.replace("چقدر درآوردم", "گزارش درآمد").replace("چقد خرج", "گزارش هزینه").replace("چقدر خرج", "گزارش هزینه")
    private fun isRecurring(t: String) = has(t, "هر روز", "هرروز", "روزانه", "روزهای کاری", "روز کاری", "خودکار", "تکرار", "فقط شنبه", "فقط یکشنبه", "فقط دوشنبه", "فقط سه شنبه", "فقط چهارشنبه", "فقط پنجشنبه", "فقط جمعه")
    private fun isPause(t: String) = has(t, "متوقف", "توقف", "غیرفعال", "غیر فعال", "خاموش", "دیگه ثبت نکن", "دیگر ثبت نکن")
    private fun isResume(t: String) = !isPause(t) && has(t, "فعال کن", "دوباره فعال", "ادامه بده")
    private fun isRuleEdit(t: String) = has(t, "مبلغش", "مبلغ قانون", "تغییر مبلغ", "مبلغ هزینه") && !has(t, "ثبت کن")
    private fun has(t: String, vararg values: String) = values.any { t.contains(it) }

    companion object {
        val STARTERS = listOf("روزای کاری هزینه قهوه‌مو ثبت کن", "هر روز برام سرویس ثبت کن", "ترافیک الان چطوره", "کارهای خودکارم")
        const val HELP = "من دستیار تخصصی خودرویارم؛ مدل چت عمومی نیستم. کوتاه بگو، اطلاعات ناقص را مرحله‌به‌مرحله می‌پرسم.\n\n• روزای کاری هزینه قهوه‌مو ثبت کن\n• هر روز برام سرویس ثبت کن (از الگوی ذخیره‌شده)\n• ترافیک الان چطوره / هر روز ساعت ۸ ترافیک رو بگو\n• قانون قهوه را متوقف کن / مبلغش را تغییر بده\n• برو تب سوخت / گزارش این ماه\n• همون هزینه دیروز رو برای امروز ثبت کن\n• این ماه نسبت به ماه قبل چطور بود؟\n• کدوم کارهای خودکارم اجرا نشده؟\n• بنزین زدم ثبت کن / یادم بنداز بیمه\n\nهزینه و سرویس بدون API هوش مصنوعی کار می‌کنند؛ ترافیک تازه به اینترنت و کلید نشان نیاز دارد. هر تغییر ابتدا خلاصه و تأیید دارد. پرداخت، چت عمومی و کنترل اپ‌های دیگر ندارم."
        fun normalize(text: String): String = LocalIntentParser.normalize(text)
            .replace("روزای کاری", "روزهای کاری").replace("روز های کاری", "روزهای کاری")
            .replace("هزنیه", "هزینه").replace("هزینه هام", "هزینه ها")
            .replace("سه‌شنبه", "سه شنبه").replace("سهشنبه", "سه شنبه")
            .replace("واسم", "برای من").replace("برام", "برای من").replace("بهم", "به من")
            .replace(Regex("(?<=\\d)(?=هزار|میلیون|میلیارد|تومان|تومن|ریال|لیتر|کیلومتر)"), " ")
            .replace("هر شب", "هر روز شب").replace("هر صبح", "هر روز صبح")
            .replace("تومن", "تومان").replace("بذار", "ثبت کن").replace("کنسلش کن", "لغو")
            .replace(Regex("\\s+"), " ").trim()
        private val serviceCategories = listOf(
            "night" to listOf("شب"), "holiday" to listOf("تعطیل", "تعطیلی"),
            "request" to listOf("درخواستی"), "available" to listOf("در اختیار", "آماده باش", "اماده باش"),
            "fixed" to listOf("ثابت"), "mission" to listOf("ماموریت", "مأموریت"),
        )
        private val datePattern = Regex("(?<!\\d)\\d{4}[/.-]\\d{1,2}[/.-]\\d{1,2}(?!\\d)")
        private val dayNames = listOf("شنبه", "یکشنبه", "دوشنبه", "سه شنبه", "چهارشنبه", "پنجشنبه", "جمعه")
        private val categories = listOf(
            ("قهوه" to "coffee") to listOf("قهوه", "کافی", "coffee"),
            ("ناهار و رستوران" to "restaurant") to listOf("ناهار", "شام", "رستوران"),
            ("خوراک" to "food") to listOf("غذا", "خوراک", "صبحانه", "سوپرمارکت"),
            ("رفت‌وآمد" to "transport") to listOf("کرایه", "تاکسی", "مترو", "اتوبوس"),
            ("قبض و اینترنت" to "bills") to listOf("قبض", "اینترنت", "تلفن"),
            ("درمان" to "health") to listOf("دارو", "درمان", "پزشک"),
            ("سیگار" to "cigarette") to listOf("سیگار"),
            ("پارکینگ" to "other") to listOf("پارکینگ"),
        )
        private val maintenanceTypes = listOf("oil-change" to listOf("روغن"), "timing-belt" to listOf("تسمه"),
            "spark-plug" to listOf("شمع"), "brake-pad" to listOf("لنت"), "fuel-filter" to listOf("فیلتر"),
            "tire" to listOf("لاستیک"), "wash" to listOf("کارواش"))
    }
}
