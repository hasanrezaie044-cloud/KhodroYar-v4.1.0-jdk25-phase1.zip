package com.khodroyar.app.ai

/** Read-only insight tools and one explicitly approved repeat. Provider JSON cannot supply snapshots. */
object DailyToolSpecs {
    val tools = listOf(
        ToolSpec("get_period_comparison", ToolKind.READ, "مقایسهٔ هم‌طول درآمد، هزینه و خالص؛ شاخص خودرو/کیلومتر و خودرو/ساعت از ثبت‌های واقعی",
            listOf(ToolParam("period", ParamType.ENUM, options = listOf("month", "week")))),
        ToolSpec("get_automation_health", ToolKind.READ, "وضعیت واقعی نتیجهٔ کارهای خودکار: انجام‌شدهٔ امروز، ناموفق، منتظر فرصت اجرا و متوقف"),
        ToolSpec("repeat_expense", ToolKind.WRITE, "تکرار یک هزینهٔ مشخص از سابقه؛ مبلغ و عنوان از دیتابیس، با خلاصه و تأیید تازه",
            listOf(ToolParam("source_date", ParamType.DATE, required = true), ToolParam("date", ParamType.DATE, required = true),
                ToolParam("title", ParamType.TEXT, desc = "عنوان برای رفع ابهام چند هزینه در یک روز"))),
    )
}
