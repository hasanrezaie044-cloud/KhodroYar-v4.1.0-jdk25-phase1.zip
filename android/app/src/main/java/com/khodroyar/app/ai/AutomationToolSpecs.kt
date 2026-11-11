package com.khodroyar.app.ai

/** Shared typed catalogue: natural-language understanding can never expand the authority surface. */
object AutomationToolSpecs {
    private fun schedule(required: Boolean) = listOf(
        ToolParam("frequency", ParamType.ENUM, required, options = listOf("daily", "workdays", "weekdays")),
        ToolParam("start_date", ParamType.DATE, required, allowFuture = true),
        ToolParam("end_date", ParamType.DATE, allowFuture = true),
        ToolParam("time", ParamType.TEXT, required, desc = "زمان محلی HH:mm؛ اجرای اندروید تقریبی است"),
        ToolParam("zone", ParamType.TEXT, desc = "شناسه منطقهٔ زمانی گوشی"),
        ToolParam("weekdays", ParamType.TEXT, desc = "اعداد 0 تا 6 با کاما؛ شنبه=0، فقط برای weekdays"),
    )
    private val id = ToolParam("id", ParamType.ID, true)
    val tools = listOf(
        ToolSpec("open_app_section", ToolKind.READ, "بازکردن بخش مشخص برنامه؛ بدون تغییر اطلاعات",
            listOf(ToolParam("section", ParamType.ENUM, true, options = AgentSection.entries.map { it.wire }))),
        ToolSpec("list_automations", ToolKind.READ, "قانون‌های ثبت خودکار، وضعیت و آخرین نتیجه"),
        ToolSpec("get_service_preset", ToolKind.READ, "الگوی ذخیره‌شدهٔ سرویس رفت‌وآمد؛ نه تعمیرات خودرو"),
        ToolSpec("get_traffic_status", ToolKind.READ, "دریافت آنلاین وضعیت سه مسیر تب ترافیک از نشان؛ مستقل از API هوش مصنوعی"),
        ToolSpec("set_traffic_alerts", ToolKind.WRITE, "فعال/متوقف‌کردن اعلان تغییر وضعیت مسیرهای تب ترافیک؛ نیاز به کلید نشان و اینترنت",
            listOf(ToolParam("state", ParamType.ENUM, true, options = listOf("on", "off")))),
        ToolSpec("create_expense_rule", ToolKind.WRITE, "ایجاد ثبت واقعی هزینهٔ تکراری پس از تأیید؛ پولی پرداخت نمی‌کند",
            listOf(ToolParam("title", ParamType.TEXT, true), ToolParam("amount", ParamType.NUMBER, true, min = 1.0, max = 1_000_000_000.0),
                ToolParam("category", ParamType.ENUM, options = listOf("cigarette", "coffee", "restaurant", "clothing", "food", "transport", "health", "home", "entertainment", "education", "bills", "other"))) + schedule(true)),
        ToolSpec("create_traffic_rule", ToolKind.WRITE, "گزارش ترافیک در روزها/ساعت مشخص از نشان؛ نیاز به اینترنت، سهمیه سرویس و اجازهٔ اعلان", schedule(true)),
        ToolSpec("create_service_rule", ToolKind.WRITE, "ثبت تکراری سرویس رفت‌وآمد با نسخهٔ ثابت الگوی ذخیره‌شده؛ مبلغ درآمد طبق نرخ‌های سال هر ثبت", schedule(true)),
        ToolSpec("add_service_from_preset", ToolKind.WRITE, "ثبت یک سرویس رفت‌وآمد با الگوی ذخیره‌شده و تأیید صریح",
            listOf(ToolParam("date", ParamType.DATE))),
        ToolSpec("set_automation_enabled", ToolKind.WRITE, "توقف یا فعال‌سازی دوبارهٔ یک قانون؛ سوابق مالی قبلی حذف نمی‌شود",
            listOf(id, ToolParam("state", ParamType.ENUM, true, options = listOf("on", "off")))),
        ToolSpec("remove_automation_rule", ToolKind.DELETE, "حذف فقط قانون و توقف اجراهای آینده؛ هیچ رکورد مالی قبلی حذف نمی‌شود", listOf(id)),
        ToolSpec("update_expense_rule", ToolKind.WRITE, "تغییر مبلغ یا برنامهٔ ثبت‌های آیندهٔ هزینه؛ ثبت‌های قبلی دست‌نخورده می‌مانند",
            listOf(id, ToolParam("amount", ParamType.NUMBER, min = 1.0, max = 1_000_000_000.0)) + schedule(false)),
    )
}
