# گزارش نهایی KhodroYar 5.1.9

> صداقت اول: در محیطی که این ارتقا ساخته شد **Android SDK، Gradle، JDK 25 و دسترسی اینترنت وجود نداشت** (فقط JDK 17 و Node.js). بنابراین **کامپایل Kotlin، Gradle، assembleRelease، APK و apksigner اجرا نشدند** و هیچ ادعای «بیلد موفق» نمی‌شود. کد Kotlin با بازبینی دستی و بررسی تعادل ساختاری کنترل شده، نه با کامپایلر. اولین کاری که باید انجام شود اجرای `./gradlew testDebugUnitTest assembleRelease` (یا CI همراه پروژه) روی یک سیستم کامل است.

## ۱) تست‌های واقعاً اجراشده
| بررسی | نتیجه |
|---|---|
| بک‌اند: `node --test` (۱۷ تست: مسیر موفق، نشت کلید، اعتبارسنجی، سقف توکن، خطاهای 400/401/402/429/500، Retry/Timeout، Rate limit، kill switch، توکن کلاینت، بودجه) | ✅ ۱۷ از ۱۷ |
| بک‌اند: اجرای واقعی `server.js` + `scripts/test-connection.js` روی GapGPT **شبیه‌سازی‌شده** (کلید ساختگی) | ✅ همهٔ ۴ مرحله؛ ۰ بار کلید در لاگ |
| `git diff --check` نسبت به پایهٔ 5.1.8 | ✅ بدون خطا |
| `tools/check-permissions.sh` (حالت ایستا روی Manifest منبع) | ✅ فقط ۴ مجوز مجاز: INTERNET، ACCESS_NETWORK_STATE، POST_NOTIFICATIONS، USE_BIOMETRIC؛ مجوزهای خطرناک `tools:node="remove"` |
| `VERIFY.sh` (۱۱ گروه بررسی ایستا، نسخه 54/5.1.9، DB=6، JDK 25، Gradle 9.4.1، AGP 9.2.0، امضا، الگوی کلید API) | ✅ همه گذشت |
| تعادل `{}` `()` `[]` در همهٔ فایل‌های Kotlin جدید/تغییریافته | ✅ |
| اسکن الگوی `return` در توابع expression-body در کل پروژه | ✅ فقط یک کامنت |

**اجرا نشد (نیاز به SDK/اینترنت):** Gradle Compile، Unit testهای اندروید (شامل `AgentActionValidatorTest` و `GoldenParityTest`)، `assembleRelease`، بررسی خروجی APK، applicationId/versionName/versionCode در APK، `apksigner`، تست روی دستگاه (۱۲ تب)، و **تست واقعی با GapGPT** (کلید و اینترنت نبود). تأیید وجود مدل `gpt-4o-mini` در حساب شما فقط با `node scripts/test-connection.js` ممکن است.

## ۲) خطاها/ناسازگاری‌های پیداشده و اصلاحات
1. **مجوزها**: `check-permissions.sh` مجوز `INTERNET` را ممنوع کرده بود در حالی که Manifest آن را (به‌درستی) دارد. اصلاح اصولی: `INTERNET` و `ACCESS_NETWORK_STATE` به فهرست مجاز و الزامی منتقل شدند، مجوز از Manifest حذف نشد؛ ممنوعیت GPS/SMS/مخاطبین/SYSTEM_ALERT_WINDOW و … حفظ شد. بررسی ایستای Manifest منبع هم اضافه شد.
2. **Release signing**: `signingConfigs.release` در زمان configuration استثنا پرتاب می‌کرد، یعنی حتی Sync/Debug/Unit test بدون Secretها شکست می‌خورد. حالا فقط هنگام ساخت واقعی Release (assemble/bundle/package/install) خطا می‌دهد. نام متغیرها، keystore و هویت امضا تغییری نکرد.
3. **`gradle-wrapper.jar` در آرشیو 5.1.8 وجود نداشت** (فقط `gradlew` و properties). `android/gradlew` بدون آن اجرا نمی‌شود. فایل باینری را نمی‌توان بدون Gradle ساخت؛ راه‌حل: یک بار `cd android && gradle wrapper --gradle-version 9.4.1` (CI این را خودکار انجام می‌دهد).
4. **`VERIFY.sh` کهنه بود** (versionCode 24، نسخه 4.2.0، DB نسخهٔ ۲) و با پروژه نمی‌خواند؛ به وضعیت واقعی همگام شد.
5. **BackupFiles.kt (`return false` در expression-body)**: در 5.1.8 قبلاً اصلاح شده بود (با `if (out == null) false else {…}`)؛ تغییری لازم نبود و اعمال کورکورانه نشد.
6. **FinanceScreen.kt (Long/Int)**: با خواندن مدل `LoanPortfolio`، `LoanCalculator.Summary/Installment` و جمع‌زنی‌ها، تایپ‌ها (Long برای مبالغ، Int برای شمارنده‌ها) سازگار دیده شد و مورد ناسازگاری یافت نشد؛ بدون کامپایلر نمی‌توان صددرصد تأیید کرد. تغییری اعمال نشد.
7. نظر Manifest دربارهٔ «برنامه آفلاین است» با واقعیت (اینترنت لازم است) هماهنگ شد.

## ۳) وضعیت Release Signing
تنظیمات امضا و applicationId دست‌نخورده‌اند. برای ساخت باید `ANDROID_KEYSTORE_PATH`، `ANDROID_KEYSTORE_PASSWORD`، `ANDROID_KEY_ALIAS`، `ANDROID_KEY_PASSWORD` تنظیم باشند (یا Secretهای CI: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). هیچ keystore یا رمزی در بسته نیست. درستی امضای APK هنوز با `apksigner verify` بررسی نشده است.

## ۴) آنچه مالک پروژه باید برای اتصال واقعی تکمیل کند
1. حساب GapGPT با اعتبار و **API Key** (رایگان/نامحدود فرض نشده) ← `backend/docs/GAPGPT-KEY.md`
2. استقرار بک‌اند روی یک میزبان با **HTTPS** و دامنه/آدرس خودتان ← `backend/docs/DEPLOY.md`
3. تنظیم `GAPGPT_API_KEY` (و در صورت تمایل `CLIENT_TOKEN`, `ADMIN_TOKEN`, سقف‌ها) فقط در Environment سرور
4. اجرای `node scripts/test-connection.js https://<آدرس>` برای تأیید وجود مدل در حساب (در صورت نبود `DEFAULT_MODEL` را عوض کنید)
5. وارد کردن آدرس بک‌اند در اپ (دستیار ← تنظیمات) یا ساخت با `-PaiBackendUrl=https://<آدرس>`
6. ساخت/امضای APK با keystore خودتان و اجرای تست‌های بخش «اجرا نشد»

## ۵) نکات طراحی و ریسک‌های باقی‌مانده
- دیتابیس Room دست‌نخورده (نسخه ۶، بدون Migration)؛ گفتگوها در فایل JSON خصوصی ذخیره می‌شوند.
- Tool Calling سرویس فرض نشده؛ خروجی JSON ساختاریافته + اعتبارسنجی در اپ.
- «مصرف سوخت» بر پایهٔ کیلومتر سرویس‌های ثبت‌شده است و تقریبی است.
- اگر فیلد Long/Int یا API ای در Kotlin جدید با نسخهٔ کتابخانه‌ها سازگار نبود، اولین خطای کامپایل در `ai/` یا `AiAssistantScreen.kt` خواهد بود؛ حجم کد جدید محدود و مستقل است و حذف ردیف `AI_ASSISTANT` از `ToolDestination` آن را کاملاً از برنامه جدا می‌کند.
- محدودیت نرخ بک‌اند در حافظهٔ یک نمونه است.
