# معماری دستیار هوشمند (5.1.9)

```
UI (AiAssistantScreen) → AiAssistantViewModel → AgentOrchestrator → AIProvider(GapGPTProvider) → [HTTPS] → backend → GapGPT
                                         │
                                         └→ AgentActionValidator → AgentToolExecutor → AppRepository (Room)
```

## جریان یک درخواست
1. کاربر پیام می‌فرستد (فقط پس از پذیرش اعلان حریم خصوصی).
2. Orchestrator پرامپت سیستمی (تاریخ شمسی امروز + فهرست ابزارها + قواعد) و حداکثر ۱۰ پیام اخیر (خلاصهٔ محلی برای پیام‌های قدیمی‌تر) را به بک‌اند می‌فرستد.
3. مدل یک JSON برمی‌گرداند: `{"action":"reply",...}` یا `{"action":"tool","tool":...,"args":{...}}`. این روش به Tool/Function Calling سرویس وابسته نیست. متن غیر JSON فقط نمایش داده می‌شود و هرگز ابزار اجرا نمی‌کند.
4. `AgentActionValidator` نام ابزار و همهٔ آرگومان‌ها را اعتبارسنجی می‌کند (نوع، بازه، تاریخ شمسی معتبر و غیرآینده، ENUMها، قالب شناسه). آرگومان ناشناخته حذف می‌شود.
5. ابزار READ فوراً اجرا و نتیجه به مدل برگردانده می‌شود (حداکثر ۵ گام). ابزار WRITE/DELETE متوقف می‌شود و کارت تأیید با خلاصهٔ ساخته‌شده توسط خود برنامه نشان داده می‌شود.
6. پس از تأیید، `AgentToolExecutor` از طریق Repository موجود اجرا می‌کند و **با خواندن دوبارهٔ دیتابیس** موفقیت را راستی‌آزمایی می‌کند؛ نتیجهٔ واقعی با برچسب «نتیجهٔ واقعی عملیات» نمایش و ذخیره می‌شود.

## ابزارها
خواندن: `get_today_expenses`, `get_monthly_report`, `get_yearly_report`, `get_totals`, `list_maintenance`, `list_services`, `list_expenses`, `list_fuel`, `get_active_reminders`, `analyze_fuel`, `get_vehicle_status`, `create_financial_report`
ثبت: `add_expense`, `add_repair`, `add_car_service`, `add_fuel`, `create_reminder`
ویرایش: `update_expense`, `update_maintenance`, `update_fuel`
حذف (تأیید جداگانه): `delete_expense`, `delete_maintenance`, `delete_fuel`

## داده‌ای که ممکن است به سرور برود
متن پیام کاربر، و فقط نتیجهٔ ابزارهایی که مدل درخواست می‌کند (مبلغ، تاریخ، دسته، عنوان کوتاه رکوردها، کیلومتر). هرگز: پرسنل/تلفن، موقعیت، مخاطبین، پیامک، فایل‌ها/پیوست‌ها، PIN، کلیدها.

## محدودیت‌های هزینه
ورودی ≤ ۱۰۰۰ نویسه در اپ؛ خروجی ≤ ۷۰۰ توکن؛ تاریخچهٔ ارسالی ≤ ۱۰ پیام / ۶۰۰۰ نویسه؛ نتیجهٔ ابزار ≤ ۳۰۰۰ نویسه؛ ≤ ۵ گام Agent در هر پیام؛ و سقف‌های سمت سرور (نرخ، بودجهٔ روزانه، کلید قطع).

## نکته دربارهٔ دقت
مصرف سوخت بر پایهٔ «کیلومتر سرویس‌های ثبت‌شده» حساب می‌شود و همیشه برابر کیلومتر واقعی خودرو نیست؛ دستیار این را در پاسخ می‌آورد.
