# گزارش ۵.۶.۴ — افزودن دوبارهٔ Gemma آفلاین روی پایهٔ ۵.۶.۳

## پایه و پشتیبان
- پایهٔ واقعی: commit `cc8b9509eecde5c27a1dc6233666e21fce365835`، نسخهٔ `5.6.3`, `versionCode 79`.
- این commit در شاخهٔ ریموت `arena/b68f3f03-khodroyar-v4-1-0-jdk25-phase1` باقی مانده و دست‌نخورده است؛ اجرای دستی GitHub Actions روی همین SHA در ۱۴ نوامبر ۲۰۲۶ موفق بوده (APK release + UI smoke). آن شاخه منبعِ بازیابی نسخهٔ ۵.۶.۳ است.
- پشتیبان محلی Git نیز ثبت شد: `refs/arena-safety/5.6.3-source-cc8b950` → همین SHA. snapshot پیش از اعتبارسنجی روی شاخهٔ کاری: `refs/arena-safety/c66-before-validation-20261116` به‌علاوهٔ patch فایل‌های tracked در `.git/arena-safety-c66-before-validation-20261116.patch` و archive فایل untracked در `.git/arena-safety-c66-untracked-20261116.tar.gz`.
- سابقهٔ این session نشان می‌دهد یک `reset --hard` پیش از ساخته‌شدن پشتیبان قبلی اجرا شده بود؛ این snapshot جدید تغییرات باقی‌مانده را حفظ می‌کند اما **قادر به بازیابی تغییرات محلیِ کنارگذاشته‌شده در آن reset نیست**. شاخهٔ منبع ۵.۶.۳ همچنان بازیابی‌پذیر است.
- شاخهٔ فعال Arena برای ذخیرهٔ تغییرات ثابت است؛ تغییرات این کار روی شاخهٔ فعال session می‌مانند، نه روی شاخهٔ منبع.

## چرا نسخهٔ خروجی ۵.۶.۴ شد
برای نصب APK جدید به‌صورت update روی ۵.۶.۳ باید `versionCode` از 79 بالاتر باشد. بنابراین خروجی این تغییرات `versionCode 80 / versionName 5.6.4` است؛ تمام کد و قابلیت‌های موجود ۵.۶.۳ پایهٔ آن هستند.

## مدل انتخاب‌شده برای Galaxy S23 FE (۸GB)
| مشخصه | مقدار |
|---|---|
| مدل | Gemma-4-E2B-it، پشتیبانی متن + Vision |
| فرمت رسمی LiteRT-LM اندروید | `gemma-4-E2B-it.litertlm` |
| شناسهٔ مخزن | `litert-community/gemma-4-E2B-it-litert-lm` |
| commit ثابت | `7fa1d78473894f7e736a21d920c3aa80f950c0db` |
| اندازهٔ رسمی | `2,583,085,056` بایت |
| حداقل RAM طبق allowlist رسمی Google AI Edge Gallery | ۸GB — برابر مشخصات گوشی هدف اعلام‌شده توسط کاربر |
| runtime | `com.google.ai.edge.litertlm:litertlm-android:0.19.1` |

E4B رد شد چون allowlist آن حداقل ۱۲GB RAM می‌خواهد. مدل 3n-E2B نیاز ۸GB دارد اما فایلش ۳.۶۶GB و از E2B Gemma 4 بزرگ‌تر است. مدل‌های رسمی با نیاز ۶GB در همان allowlist ورودی Vision ندارند؛ برای نیازِ عکس، E2B Gemma 4 انتخاب کم‌حجم‌ترِ رسمی میان گزینه‌های واجد شرایط است.

> این تطابق با حداقل رسمی، تضمین عملکرد واقعی روی هر S23 FE نیست. دستگاه واقعی در این محیط وجود ندارد؛ سرعت، RAM پیک، گرما و پایداری هنوز اندازه‌گیری نشده‌اند.

## تغییرات ادغام‌شده با معماری ۵.۶.۳
1. دستیار Gemma در کشوی ابزارها مسیر جدا دارد. حالت `LOCAL` در دستیار چندارائه‌دهندهٔ ۵.۶.۳ همچنان همان دستیار داخلیِ بدون مدل مولد است؛ Gemma آن را جایگزین نمی‌کند و تنظیمات OpenRouter/Gemini/Groq/Claude/GPT یا سیاست هزینه/منطقه را تغییر نمی‌دهد.
2. حلقهٔ عامل آفلاین اکنون از `AgentToolRunner` و `AgentApprovalGate` نسخهٔ ۵.۶.۳ استفاده می‌کند. snapshot/preview عملیات توسط برنامه ساخته می‌شود و WRITE/DELETE تا تأیید کاربر اجرا نمی‌شود.
3. `AgentStep.kt` قدیمی حذف شد تا با تعریف مشترک `AgentExecution.kt` تکراری نباشد. `LocalAgentOrchestratorTest` به fakeای بر پایهٔ رابط جدید مشترک منتقل شد.
4. پیوست چت با `ChatImageRef`/`ChatImageStore` نسخهٔ ۵.۶.۳ نگهداری می‌شود؛ تصاویر Gemma namespace جدا و no-backup دارند. پاک‌کردن history آفلاین فقط تصاویر Gemma خودش را prune می‌کند.
5. `ConversationRepository` اکنون به آرگومان `fileName` احترام می‌گذارد؛ `ai_conversations.json` و `gemma_conversations.json` واقعاً جدا هستند. history Gemma با `AiMode.LOCAL` و scope محلی ثبت می‌شود.
6. در `GemmaModelSpec.metadataUrl` checksum از همان **commit pin‌شده** دریافت می‌شود، نه `tree/main`. تست regression برای این الزام افزوده شد.
7. CI قبل از build اندازه و SHA-256 LFS فایل مدل را از metadata همان commit بررسی می‌کند؛ بعد از build هم رد می‌کند اگر `.litertlm` در APK باشد.
8. دستیار Gemma در Shell نسخهٔ ۵.۶.۳ route می‌شود؛ ورودی منوی آن و نشانگرهای موجودِ دستیار چندارائه‌دهنده با هم باقی می‌مانند.
9. گیت حافظهٔ S23 FE در اجرای مدل حداقل حدود ۲٫۵GB RAM آزاد واقعی قبل از initialize می‌خواهد؛ اگر headroom کافی نباشد عملیات متوقف و پیام واضح داده می‌شود. Vision: GPU → CPU multimodal → CPU text-only؛ پس از شکست Vision تصویر به موتور متن‌محض فرستاده نمی‌شود.

## بررسی و آزمون
- CI قبلی ۵.۶.۳ روی SHA پایه `cc8b9509...` موفق بوده، اما **آن run شامل کد این تغییرات نیست**.
- در این workspace فرمان `bash ./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon` پیش از شروع Gradle متوقف شد: نه `JAVA_HOME` تنظیم است و نه `java` نصب/در PATH است؛ Android SDK نیز در workspace پیدا نشد. بنابراین **کامپایل و تست‌های Android هنوز اجرا نشده‌اند**؛ pass ادعا نمی‌شود.
- تست‌های خارج از Android که اجرا شدند: backend Node.js (۲۲)، تست ابزار انتشار APK (۲۲)، OCR audit (۶)، instrumentation summary (۸)، private delivery (۳) — همگی موفق. `git diff --check`, استخراج metadata توسط همان regexهای workflow و بررسی نبود conflict نیز موفق‌اند. این‌ها جایگزین build/unit tests اندروید نیستند.
- برای این تغییرات، build/test نهایی باید روی SHA جدید با workflow `KhodroYar Android APK Release` اجرا شود؛ تا موفقیت آن، compile/assemble این ادغام را موفق اعلام نمی‌کنیم.
- پس از CI، تست فیزیکی لازم: S23 FE، airplane-mode chat، تحلیل تصویر واقعی، تأیید write/delete، زمان بارگذاری، inference latency و RAM پیک از `GemmaEngine`.
- مدل multi-GB مستقل و با انتخاب صریح کاربر دانلود می‌شود؛ APK شامل آن نیست. تنها مرحلهٔ inference آفلاین است و دانلود به اینترنت نیاز دارد.
