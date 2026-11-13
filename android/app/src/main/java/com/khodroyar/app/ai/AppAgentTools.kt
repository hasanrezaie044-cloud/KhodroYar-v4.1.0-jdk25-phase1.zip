package com.khodroyar.app.ai

import android.content.Context
import com.khodroyar.app.automation.*
import com.khodroyar.app.core.analytics.*
import com.khodroyar.app.core.finance.PurchaseCategory
import com.khodroyar.app.data.repo.QuickDraft
import kotlinx.coroutines.flow.first
import com.khodroyar.app.core.jalali.Jalali
import com.khodroyar.app.data.repo.AppRepository
import com.khodroyar.app.notifications.AgentAutomationScheduler
import com.khodroyar.app.notifications.Notifications
import com.khodroyar.app.notifications.TrafficCheckScheduler
import com.khodroyar.app.traffic.TrafficRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Connects the agent to the SAME repositories used by the tabs, not simulated UI clicks. */
class AppAgentTools(context: Context, private val repo: AppRepository) : AgentToolRunner {
    private val app = context.applicationContext
    private val records = AgentToolExecutor(repo)
    private val serviceRecords = ServiceRecordTools(repo)
    private val store = AutomationStore.get(app)
    private val extraNames = (AutomationToolSpecs.tools + DailyToolSpecs.tools).map { it.name }.toSet()

    private fun template(): ServiceTemplate {
        val s = repo.settingsNow
        userCheck(s.autoServicePresetSaved, "ابتدا در تب «ثبت سرویس»، مسیر و مشخصات همیشگی را به‌عنوان الگو ذخیره کن. بعد برگرد و بگو «الگو را ذخیره کردم»؛ هنوز چیزی فعال نشده است.")
        return ServiceTemplate(s.autoServiceType, s.autoServiceCarType, s.autoServiceOrigin, s.autoServiceDestination,
            s.autoServiceKm, s.autoServiceHours, s.autoServiceStartTime, s.autoServiceEndTime,
            s.autoServiceTollCount, s.autoServicePassengers).also { userCheck(it.valid(), "الگوی سرویس کامل نیست؛ مسیر، نوع، خودرو و کیلومتر/ساعت آن را در تب ثبت سرویس بررسی کن.") }
    }

    override suspend fun prepare(call: ValidatedCall): ValidatedCall {
        if (call.spec.name in ServiceCommandSpecs.names) return serviceRecords.prepare(call)
        if (call.spec.name !in extraNames) return records.prepare(call)
        val args = call.args.toMutableMap()
        if (call.spec.name == "repeat_expense") {
            val sourceDate = requireNotNull(call.str("source_date"))
            val title = call.str("title")?.let(LocalDialogueEngine::normalize)
            val dayRows = repo.insights.expensesOn(sourceDate)
            userCheck(dayRows.size <= 20, "ثبت‌های آن روز زیاد است؛ برای انتخاب دقیق هزینه از تب مالی استفاده کن. چیزی ثبت نشد.")
            val candidates = dayRows.filter { row ->
                title == null || LocalDialogueEngine.normalize(row.title).contains(title) ||
                    LocalDialogueEngine.normalize(PurchaseCategory.fromWire(row.category).label) == title
            }
            userCheck(candidates.isNotEmpty(), "برای آن روز هزینه‌ای با این عنوان پیدا نشد؛ چیزی ثبت نمی‌کنم.")
            userCheck(candidates.size == 1, "برای آن روز چند هزینه هست؛ عنوان را مشخص کن، مثلاً «قهوهٔ دیروز را برای امروز تکرار کن».")
            val source = candidates.single()
            args["source_id"] = source.id
            args["source_fingerprint"] = source.toString()
            args["source_title"] = source.title
            args["source_amount"] = source.amount
            args["operation_id"] = UUID.randomUUID().toString()
        }
        if (call.spec.name in setOf("create_expense_rule", "create_service_rule", "create_traffic_rule", "add_service_from_preset")) {
            args["operation_id"] = UUID.randomUUID().toString()
        }
        if (call.spec.name in setOf("create_service_rule", "add_service_from_preset")) {
            val saved = template()
            val requestedType = call.str("service_type")
            val chosen = if (requestedType == null) saved else saved.copy(type = requestedType)
            userCheck(chosen.valid(), "الگوی سرویس با نوع انتخاب‌شده کامل نیست؛ مقادیر واقعی را بررسی کن.")
            args["service_snapshot"] = AutomationCodec.templateJson(chosen).toString()
        }
        if (call.spec.name in setOf("set_automation_enabled", "update_expense_rule", "remove_automation_rule")) {
            val rule = find(call.str("id"))
            args["id"] = rule.id
            args["expected_revision"] = rule.revision
        }
        val prepared = ValidatedCall(call.spec, args)
        if (call.spec.name in setOf("create_expense_rule", "create_service_rule", "create_traffic_rule")) {
            userCheck(store.rules.value.size < AutomationCodec.MAX_RULES, "حداکثر ۳۲ قانون روی این دستگاه پشتیبانی می‌شود.")
            ensureNoConflict(ruleFrom(prepared), store.rules.value)
        }
        if (call.spec.name == "update_expense_rule") userCheck(edited(find(prepared.str("id")), prepared).valid(), "برنامه یا مبلغ جدید معتبر نیست؛ زمان، بازهٔ تاریخ و روزها را بررسی کن.")
        if (call.spec.name == "set_automation_enabled" && call.str("state") == "on") {
            val target = find(prepared.str("id"))
            ensureNoConflict(target, store.rules.value.filter { it.id != target.id })
        }
        if (call.spec.name == "set_traffic_alerts" && call.str("state") == "on") userCheck(repo.settingsNow.neshanApiKey.isNotBlank(), "برای هشدار ترافیک، ابتدا کلید نشان را در تب ترافیک ذخیره کن؛ نیازی به API هوش مصنوعی نیست.")
        return prepared
    }

    override fun describe(call: ValidatedCall): String = when (call.spec.name) {
        in ServiceCommandSpecs.names -> serviceRecords.describe(call)
        "repeat_expense" -> "تکرار هزینهٔ «${call.str("source_title") }» از ${call.str("source_date")} برای ${call.str("date")}\nمبلغ: ${Analytics.formatNumber(call.dbl("source_amount") ?: 0.0)} تومان\nیک ثبت جدید؛ سابقهٔ قبلی تغییر نمی‌کند."
        "create_expense_rule", "create_service_rule", "create_traffic_rule" -> ruleSummary(ruleFrom(call)) +
            (if (call.spec.name == "create_traffic_rule") "\n\nاجازهٔ دریافت آنلاین و اعلان ترافیک در زمان‌های همین قانون؛ مصرف سهمیهٔ نشان محتمل است، نه API هوش مصنوعی." else "\n\nاجازهٔ ثبت خودکار واقعی برای همین قانون؛ تا توقف یا پایان آن.") +
            " اجرای پس‌زمینه تقریبی است، فقط همان روز؛ روزهای ازدست‌رفته جبران نمی‌شوند."
        "add_service_from_preset" -> "ثبت یک سرویس رفت‌وآمد در ${call.str("date") ?: Jalali.todayString()}\n" + snapshot(call).summary() +
            "\nدرآمد با نرخ سال تاریخ ثبت محاسبه می‌شود؛ کیلومتر خودرو به‌روز خواهد شد."
        "set_automation_enabled" -> (if (call.str("state") == "on") "فعال‌سازی دوبارهٔ" else "توقف") +
            " قانون «${find(call.str("id")).title}»\n" + ruleSummary(find(call.str("id"))) + "\nسوابق قبلی حذف یا بازنویسی نمی‌شوند."
        "remove_automation_rule" -> "حذف قانون «${find(call.str("id")).title}» و توقف اجراهای آینده؛ رکوردهای مالی و سرویس‌های قبلی حذف نمی‌شوند."
        "update_expense_rule" -> "تغییر ثبت‌های آینده؛ نه سوابق قبلی:\n" + ruleSummary(edited(find(call.str("id")), call))
        "set_traffic_alerts" -> if (call.str("state") == "on")
            "فعال‌کردن هشدار تغییر وضعیت مسیرهای تب ترافیک؛ بررسی آنلاین دوره‌ای با کلید نشان و احتمال مصرف سهمیهٔ آن سرویس. نیاز به اجازهٔ اعلان دارد؛ زمان‌بندی دقیق نیست."
            else "متوقف‌کردن هشدار خودکار ترافیک؛ کلید نشان و سوابق مالی تغییر نمی‌کنند."
        else -> records.describe(call)
    }

    override suspend fun describeTarget(call: ValidatedCall): String? = if (call.spec.name in ServiceCommandSpecs.names) serviceRecords.describeTarget(call) else if (call.spec.name == "remove_automation_rule")
        store.rules.value.singleOrNull { it.id == call.str("id") }?.let { ruleSummary(it) } else records.describeTarget(call)

    override suspend fun execute(call: ValidatedCall): ToolOutcome {
        if (call.spec.name in ServiceCommandSpecs.names) return serviceRecords.execute(call)
        if (call.spec.name !in extraNames) return records.execute(call)
        return try {
            withContext(Dispatchers.IO) { executeExtra(call) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AgentUserException) {
            ToolOutcome(false, e.userMessage)
        } catch (_: Exception) {
            ToolOutcome(false, "عملیات تأیید نشد. ممکن است الگوی سرویس آماده نباشد، قانون مشابه فعال باشد، اطلاعات تغییر کرده باشد یا سرویس ترافیک در دسترس نباشد. کارهای خودکار/تب مربوط را بررسی کنید؛ موفقیت فرضی اعلام نمی‌کنم.")
        }
    }

    private suspend fun periodComparison(period: String): ToolOutcome {
        val today = Jalali.todayString()
        val windows = if (period == "week") ReportWindow.weekToDate(today) else ReportWindow.monthToDate(today)
        val data = repo.comparison(windows.first, windows.second).first()
        fun n(value: Double) = Analytics.formatNumber(value)
        fun group(key: String): String = when (key) { "fuel" -> "سوخت"; "maintenance" -> "نگهداری"; else -> PurchaseCategory.fromWire(key.removePrefix("personal:")).label }
        if (!data.current.hasData && !data.previous.hasData) return ToolOutcome(true, "برای این دو بازه ثبتی وجود ندارد؛ نتیجه‌ای حدس نمی‌زنم.")
        return ToolOutcome(true, buildString {
            append("مقایسهٔ ${windows.first.days} روز هم‌طول\n")
            append("این بازه: ${windows.first.start} تا ${windows.first.end}\n")
            append("بازهٔ قبل: ${windows.second.start} تا ${windows.second.end}\n")
            append("درآمد: ${n(data.current.income)} · هزینه: ${n(data.current.expense)} · خالص: ${n(data.current.net)} تومان\n")
            if (data.previous.hasData) {
                append("خالص بازهٔ قبل: ${n(data.previous.net)} تومان\n")
                if (data.current.hasData) append("تفاوت خالص: ${n(data.current.net - data.previous.net)} تومان\n")
            } else append("برای بازهٔ قبل سابقه‌ای نداریم؛ درصد رشد نمی‌سازم.\n")
            append("خالص خودرو/کیلومتر: ${data.current.vehicleNetPerKm?.let(::n) ?: "—"} تومان\n")
            append("خالص خودرو/ساعت: ${data.current.vehicleNetPerHour?.let(::n) ?: "—"} تومان\n")
            append("خالص خودرو = درآمد سرویس منهای سوخت و نگهداری؛ فقط ثبت‌های موجود.\n")
            data.increases.forEach { append((if (data.previous.hasData) "افزایش" else "هزینهٔ ثبت‌شدهٔ") + " ${group(it.key)}: ${n(it.increase)} تومان\n") }
        }.trim())
    }

    private suspend fun executeExtra(c: ValidatedCall): ToolOutcome = when (c.spec.name) {
        "repeat_expense" -> {
            val source = repo.insights.expense(requireNotNull(c.str("source_id")))
            userCheck(source != null && source.toString() == c.str("source_fingerprint"), "هزینهٔ مبنا بعد از خلاصه تغییر کرده یا حذف شده؛ دوباره درخواست بده. چیزی ثبت نشد.")
            val saved = repo.quickEntries.save(QuickDraft.from(requireNotNull(source), requireNotNull(c.str("date"))), requireNotNull(c.str("operation_id")), expectedExpenseSource = source)
            ToolOutcome(true, "تکرار هزینه در پایگاه‌داده تأیید شد: ${saved.draft.title} · ${Analytics.formatNumber(saved.draft.amount)} تومان · ${saved.draft.date}")
        }
        "get_period_comparison" -> periodComparison(c.str("period") ?: "month")
        "get_automation_health" -> {
            val holidays = repo.manualHolidayDatesOnce()
            val now = java.time.Instant.now()
            val rows = store.rules.value
            ToolOutcome(!store.storageError, if (store.storageError) "فایل کارهای خودکار قابل خواندن نیست؛ موفقیت فرضی اعلام نمی‌کنم."
                else if (rows.isEmpty()) "هنوز کار خودکاری تعریف نشده است."
                else rows.joinToString("\n\n") { rule -> "${rule.title}: ${rule.health(now, holidays).label}\nآخرین ثبت تأییدشده: ${rule.lastDate.ifBlank { "ندارد" }}\nآخرین نتیجه: ${rule.lastMessage}" } +
                    "\nزمان‌بندی اندروید تقریبی است؛ منتظر بودن به‌معنای خطای قطعی نیست. روزهای ازدست‌رفته خودکار جبران نمی‌شوند.")
        }
        "open_app_section" -> AgentSection.fromWire(c.str("section"))?.let { ToolOutcome(true, "بازکردن بخش ${it.label}", it) }
            ?: ToolOutcome(false, "بخش مجاز پیدا نشد.")
        "list_automations" -> ToolOutcome(!store.storageError, if (store.storageError) "فایل قوانین قابل خواندن نیست؛ هیچ قانون مخفیانه فعال نمی‌شود."
            else if (store.rules.value.isEmpty()) "هنوز کار خودکاری ندارید. مثلاً بگویید: روزای کاری هزینه قهوه‌مو ثبت کن."
            else store.rules.value.joinToString("\n\n") { "${it.id.take(8)} · ${if (it.enabled) "فعال" else "متوقف"}\n${ruleSummary(it)}\nآخرین نتیجه: ${it.lastMessage}" }.take(AiLimits.MAX_TOOL_RESULT_CHARS))
        "get_service_preset" -> ToolOutcome(true, "الگوی ذخیره‌شدهٔ سرویس:\n${template().summary()}\nتا تأیید نکنید ثبت نمی‌شود.")
        "get_traffic_status" -> {
            val key = repo.settingsNow.neshanApiKey
            if (key.isBlank()) ToolOutcome(false, "برای ترافیک زنده، اینترنت و کلید نشان در تب ترافیک لازم است؛ کلید را در چت نفرستید. دستیار آفلاین ترافیک را حدس نمی‌زند.")
            else {
                val result = TrafficRepository().fetch(key)
                val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(result.checkedAt)
                ToolOutcome(true, "اطلاعات دریافت‌شده از نشان · ساعت $time\n" + TrafficRepository.CORRIDORS.joinToString("\n") { route ->
                    val status = result.statuses.getValue(route.id)
                    "${route.title}: ${status.labelFa} · ${status.detail}"
                } + "\nبرآورد بر پایهٔ زمان مسیر و زمان مرجع است؛ پوشش تمام خیابان‌ها یا پیش‌بینی قطعی ترافیک نیست.")
            }
        }
        "set_traffic_alerts" -> {
            val on = c.str("state") == "on"
            if (on && repo.settingsNow.neshanApiKey.isBlank()) ToolOutcome(false, "ابتدا کلید نشان را در تب ترافیک ذخیره کنید؛ چیزی فعال نشد.")
            else {
                repo.updateSettings { it.copy(trafficNotifyEnabled = on) }
                TrafficCheckScheduler.sync(app)
                ToolOutcome(repo.settingsNow.trafficNotifyEnabled == on,
                    (if (on) "بررسی خودکار ترافیک فعال شد؛ اتصال زنده هنوز با این تغییر آزمایش نشده است." else "هشدار ترافیک متوقف شد.") +
                        if (on && !Notifications.canPost(app)) "\nمجوز اعلان بسته است؛ برای دیدن هشدار آن را در تنظیمات گوشی فعال کنید." else "")
            }
        }
        "create_expense_rule", "create_service_rule", "create_traffic_rule" -> {
            val rule = ruleFrom(c)
            val saved = store.change { rows ->
                val existing = rows.find { it.id == rule.id }
                if (existing != null) rows else {
                    check(rows.size < AutomationCodec.MAX_RULES)
                    ensureNoConflict(rule, rows)
                    rows + rule
                }
            }
            check(saved.any { it.id == rule.id && it.valid() })
            AgentAutomationScheduler.checkNow(app)
            ToolOutcome(true, "قانون «${rule.title}» ذخیره و فعال شد (${rule.id.take(8)}).\n${rule.schedule.summary()}\nاولین ثبت فقط بعد از رسیدن زمان قانون و در فرصت اجرای اندروید انجام می‌شود." + notificationNote())
        }
        "set_automation_enabled", "update_expense_rule" -> {
            var changed: AutomationRule? = null
            store.change { rows ->
                val target = rows.single { it.id == c.str("id") }
                userCheck(target.revision == c.int("expected_revision"), "این قانون بعد از نمایش خلاصه تغییر کرده است. دوباره آن را از کارهای خودکار انتخاب کن؛ تغییری اعمال نشد.")
                val next = if (c.spec.name == "update_expense_rule") edited(target, c)
                    else target.copy(enabled = c.str("state") == "on", revision = target.revision + 1, failures = 0)
                check(next.valid())
                if (next.enabled) ensureNoConflict(next, rows.filter { it.id != next.id })
                changed = next
                rows.map { if (it.id == next.id) next else it }
            }
            AgentAutomationScheduler.checkNow(app)
            ToolOutcome(true, "قانون «${changed!!.title}» ${if (changed!!.enabled) "به‌روز و فعال" else "متوقف"} شد. سوابق قبلی تغییر نکردند.")
        }
        "remove_automation_rule" -> {
            store.change { rows ->
                val target = rows.single { it.id == c.str("id") }
                userCheck(target.revision == c.int("expected_revision"), "قانون تغییر کرده؛ آن را دوباره انتخاب و بررسی کن.")
                rows.filter { it.id != target.id }
            }
            AgentAutomationScheduler.sync(app)
            ToolOutcome(true, "قانون حذف و اجراهای آینده‌اش متوقف شد؛ هیچ رکورد مالی یا سرویس قبلی پاک نشد.")
        }
        "add_service_from_preset" -> {
            val date = c.str("date") ?: Jalali.todayString()
            val rule = AutomationRule(requireNotNull(c.str("operation_id")), RuleKind.SERVICE, "سرویس تأییدشده از الگو",
                RepeatSchedule(RepeatFrequency.DAILY, date), service = snapshot(c))
            repo.recordAutomation(rule, date, rule.occurrenceId(date))
            ToolOutcome(true, "سرویس $date در دیتابیس ثبت و بازخوانی شد. آن را در تقویم و گزارش‌ها می‌بینید.")
        }
        else -> ToolOutcome(false, "ابزار مجاز نیست.")
    }

    private fun notificationNote() = if (!Notifications.canPost(app)) "\nمجوز اعلان بسته است؛ ثبت‌ها اجرا می‌شوند اما اعلانشان را نمی‌بینید." else ""
    private fun snapshot(c: ValidatedCall) = AutomationCodec.templateFromJson(JSONObject(requireNotNull(c.str("service_snapshot"))))
    private fun find(id: String?): AutomationRule = store.rules.value.single { id != null && it.id.startsWith(id) }
    private fun frequency(wire: String?) = RepeatFrequency.entries.first { it.wire == wire }
    private fun schedule(c: ValidatedCall): RepeatSchedule = RepeatSchedule(frequency(c.str("frequency")), requireNotNull(c.str("start_date")),
        requireNotNull(c.str("time")), c.str("zone") ?: ZoneId.systemDefault().id, c.str("end_date").orEmpty(),
        c.str("weekdays")?.split(',')?.map { it.trim().toInt() }?.toSet().orEmpty())

    private fun ruleFrom(c: ValidatedCall): AutomationRule = if (c.spec.name == "create_expense_rule")
        AutomationRule(requireNotNull(c.str("operation_id")), RuleKind.EXPENSE, requireNotNull(c.str("title")), schedule(c),
            requireNotNull(c.dbl("amount")), c.str("category") ?: "other")
        else if (c.spec.name == "create_traffic_rule") AutomationRule(requireNotNull(c.str("operation_id")), RuleKind.TRAFFIC, "گزارش ترافیک", schedule(c))
        else AutomationRule(requireNotNull(c.str("operation_id")), RuleKind.SERVICE, "سرویس رفت‌وآمد", schedule(c), service = snapshot(c))

    private fun edited(r: AutomationRule, c: ValidatedCall): AutomationRule {
        check(r.kind == RuleKind.EXPENSE)
        val f = c.str("frequency")?.let(::frequency) ?: r.schedule.frequency
        val days = c.str("weekdays")?.split(',')?.map { it.trim().toInt() }?.toSet() ?: r.schedule.weekdays
        return r.copy(amount = c.dbl("amount") ?: r.amount, revision = r.revision + 1,
            schedule = r.schedule.copy(frequency = f, startDate = c.str("start_date") ?: r.schedule.startDate,
                endDate = c.str("end_date") ?: r.schedule.endDate, time = c.str("time") ?: r.schedule.time,
                zoneId = c.str("zone") ?: r.schedule.zoneId, weekdays = if (f == RepeatFrequency.WEEKDAYS) days else emptySet()))
    }

    private fun ensureNoConflict(r: AutomationRule, others: List<AutomationRule>) {
        userCheck(r.valid(), "زمان، روزهای انتخابی، منطقهٔ زمانی، مبلغ یا بازهٔ این قانون معتبر نیست؛ چیزی فعال نشد.")
        userCheck(!store.storageError, "فایل قوانین قابل خواندن نیست؛ برای ایمنی قانون جدیدی فعال نمی‌شود.")
        if (r.kind == RuleKind.SERVICE && r.schedule.startDate <= Jalali.todayString()) userCheck(repo.settingsNow.autoServiceLastRunDate != Jalali.todayString(), "ثبت خودکار قدیمی سرویس امروز را پردازش کرده است؛ برای جلوگیری از ثبت دوگانه، شروع قانون جدید را «از فردا» بگذار.")
        if (r.kind == RuleKind.SERVICE) userCheck(!repo.settingsNow.autoServiceEnabled, "ثبت خودکار قدیمی سرویس در تنظیمات فعال است. برای جلوگیری از ثبت دوگانه، اول همان را خاموش کن؛ یا از همان قابلیت استفاده کن. هنوز قانون جدیدی فعال نشده است.")
        if (r.kind == RuleKind.TRAFFIC) userCheck(repo.settingsNow.neshanApiKey.isNotBlank(), "ابتدا کلید نشان را در تب ترافیک ذخیره کن؛ این قانون به اینترنت و سهمیهٔ نشان نیاز دارد، نه API هوش مصنوعی.")
        userCheck(others.none { it.enabled && it.kind == r.kind &&
            (r.kind == RuleKind.SERVICE || it.title == r.title) &&
            (it.schedule.endDate.isEmpty() || it.schedule.endDate >= r.schedule.startDate) &&
            (r.schedule.endDate.isEmpty() || r.schedule.endDate >= it.schedule.startDate) }, "یک قانون مشابه در این بازه فعال است؛ از کارهای خودکار همان را ویرایش یا متوقف کن تا ثبت دوگانه ایجاد نشود.")
    }

    private fun userCheck(condition: Boolean, message: String) { if (!condition) throw AgentUserException(message) }

    companion object {
        fun ruleSummary(r: AutomationRule): String = buildString {
            append(r.title)
            if (r.kind == RuleKind.EXPENSE) append(" · ").append(Analytics.formatNumber(r.amount)).append(" تومان در هر نوبت")
            append('\n').append(r.schedule.summary())
            r.service?.let { append('\n').append(it.summary()).append("\nدرآمد طبق نرخ سال هر ثبت؛ کیلومتر خودرو به‌روز می‌شود.") }
        }
    }
}
