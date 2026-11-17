# گزارش تغییرات KhodroYar 5.2.0 — دستیار آفلاین Gemma 4 E2B (افزونهٔ جدید، بدون بازنویسی)

> صداقت اول: در محیط ساخت این تغییرات **Android SDK / Gradle / دستگاه واقعی در دسترس نبود**؛ کدهای
> مرتبط با رابط اندرویدی (LiteRT-LM Engine، Compose، ViewModel) **کامپایل/اجرا نشده‌اند** و هرجا
> تأیید نشده صادقانه نوشته شده است. بخش خالص-JVM (پارسرها، دانلودر، مقایسه‌گر، حلقهٔ عامل) با
> kotlinc 2.3.10 محلی کامپایل و با **۷۰ تست واحد واقعی اجرا و پاس شده**؛ تست‌های قبلی
> (`AgentActionValidatorTest` و غیره) نیز با همان ابزار اجرا و **۲۶/۲۶ پاس** شدند تا از عدم
> رگرسیون مطمئن شویم.

## ۱) چه چیزی اضافه شد

دستیار چندوجهی آفلاین (متن + تصویر) با مدل **Gemma 4 E2B** روی **LiteRT-LM** — به‌عنوان **چهارمین
ورودی جداگانه** در کشوی ابزارها با آیکون متمایز (Psychology)، در کنار دستیار آنلاین موجود (GapGPT) که
**هیچ تغییری در رفتارش نکرده است**. هیچ provider قدیمی حذف نشد، هیچ provider جدید آنلاین اضافه نشد.

### معماری (همه در پوشهٔ `ai/gemma/`)
| فایل | نقش |
|---|---|
| `GemmaModelSpec.kt` | pin رسمی مدل از allowlist گوگل: repo `litert-community/gemma-4-E2B-it-litert-lm`، فایل `gemma-4-E2B-it.litertlm`، اندازهٔ دقیق **۲٬۵۸۳٬۰۸۵٬۰۵۶ بایت**، commit `7fa1d784…c0db`، حداقل RAM رسمی ۸GB |
| `ModelStore.kt` | فضای خصوصی اپ: `files/gemma_model/` + حالت‌های NotInstalled/Interrupted/Ready/Corrupt/… + meta JSON + `deleteAll()` تمام‌گیر |
| `ModelVerifier.kt` | SHA-256 واقعی + اندازه؛ پرچم `digestTrusted` تا جایی که checksum در دسترس نبود **ادعای «تأیید شد» نشود** |
| `ModelDownloadManager.kt` | دانلود `Range`-محورِ قابل‌توقف/ادامه/تلاش‌مجدد با تأیید checksum + انتقال اتمیک (promote) فقط **پس از** تأیید، روی HTTPS ثابت از commit pin‌شده؛ نمایش پیشرفت/سرعت/مانده |
| `HttpUrlTransport` | پیادهٔ پیش‌فرض دانلود با `HttpURLConnection` |
| `LocalLlmEngine.kt` | رابط motor آفلاین مستقل از اندروید (Request/Reply/EngineError) تا کل حلقهٔ عامل قابل تست باشد |
| `GemmaJsonParser.kt` | پارسر متحمل به خطای خروجی مدل کوچک: فنس‌ها، توکن‌های خاص، متن دور JSON، alias کلیدها |
| `GemmaPrompts.kt` | پرامپت دست‌ساز فارسی برای چت/استخراج/توضیح مقایسه |
| `LocalAgentOrchestrator.kt` | حلقهٔ عامل آفلاین؛ از همان `AgentActionValidator`/`AgentToolRegistry`/`AgentToolExecutorApi`/`PendingAction`/`AgentStep` دستیار آنلاین استفاده می‌کند؛ حداکثر ۴ گام ابزار |
| `CompanyReportParser.kt` | استخراج سخت‌گیرانهٔ جدول گزارش شرکت از متن مدل با تاریخ جلالی، ارقام فارسی و بدون حدس |
| `CompanyReportComparator.kt` | مقایسهٔ قطعی با `servicesByPrefixOnce` — بدون هیچ نوشتن در DB؛ خروجی جدول فارسی با matched/differs/missing/extra/duplicates و بررسی مجموع‌های چاپی |
| `DeviceProfile.kt` | دروازهٔ RAM/فضای ذخیره واقعی (ActivityManager/StatFs) |
| `ReportImagePrep.kt` | چرخش EXIF، کاهش به ≤۱۲۸۰px و JPEG — مطابق محدودیت سندی LiteRT-LM (≈۲۵۲۰ patch) |
| `LiteRtLmEngine.kt` | motore واقعی: یک Engine کل‌پردازه، Conversation جدید به‌ازای هر درخواست، انحصار GPU vision با fallback CPU، نمایان‌سازی مموری/تاخیر در logcat (`GemmaEngine`) |
| `GemmaRuntime.kt` | singleton سطح Application؛ engine فقط «موقع اولین استفادهٔ واقعی» ساخته می‌شود و آزادسازی RAM اگر مدل لود نشده بود هرگز ساید-افکت ندارد |
| `GemmaAssistantViewModel.kt` | مدیر مدل + چت + تصویر + تحلیل گزارش + تاریخچهٔ مستقل |
| `ui/screens/GemmaAssistantScreen.kt` | صفحهٔ Compose: دروازهٔ مدل/دانلود/حباب‌ها/کارت تأیید/مدیریت مدل/تاریخچه |

### مشخصه‌های کلیدی خواسته‌شده
- **بدون کلید API و بدون هیچ تماس کلودی در inference.** فقط دانلود مدل از منبع pin‌شده آنلاین است و آن هم فقط با کلیک صریح کاربر.
- **مدل در APK نیست** (Cafe Bazaar APK اندازه‌اش تغییر نمی‌کند؛ چک CI هم آن را تأیید می‌کند).
- **ادغام با منطق موجود**: هر ثبت/ویرایش/حذف از همان `AppRepository` و نرخ‌ها و `IncomeCalculator` رد می‌شود؛ مدل فقط «نام ابزار + آرگومان‌ها» را می‌گوید و فقط پس از تأیید کاربر انجام می‌گیرد. هیچ مسیر typingٔ جدا یا OCR دوم وجود ندارد.
- **ابزارهای جدید مشترک برای هر دو دستیار**: `add_service`، `update_service`، `delete_service` — با اعتبارسنجی سخت (تاریخ جلالی آینده‌دار غ، ساعت 24h، km/hours محدود، enum نوع سرویس «auto = تشخیص خودکار برنامه»، تفکیک زمان‌های start/end و هشدار تکرار احتمالی).
- **مقایسهٔ برگهٔ شرکت با DB**: فقط از روی اعداد واقعی؛ هر ردیف مبهم با 🟡 (اعتماد کم) علامت می‌خورد و **هیچ‌وقت خودکار** از تصویر چیزی ساخته نمی‌شود.

## ۲) تغییرات در فایل‌های موجود (هر تغییر حداقلی است)
| فایل | تغییر |
|---|---|
| `build.gradle` | افزودن `com.google.ai.edge.litertlm:litertlm-android:0.19.1`؛ نسخه → `versionCode 55 / versionName '5.2.0'`؛ کامنت «مدل در APK نیست» |
| `proguard-rules.pro` | keep قوانین LiteRT-LM/Gson تا رنامینگ کلاس JNI نشکند |
| `AndroidManifest.xml` | ۳ `<uses-native-library>` برای GPUهای OpenCL/Vulkan (required=false؛ استفادهٔ اختیاری، بدون نیاز به مجوز تازه) |
| `ai/AgentTools.kt` | ابزارهای سرویس جدید + `ParamType.TIME` + رفع باگ بحرانیِ «نرمال‌سازی ارقام» که متن نمایشی فارسی را خراب می‌کرد (فضا/کما/ZWNJ پاک می‌شدند) |
| `ai/AgentToolExecutor.kt` | اکنون رابط `AgentToolExecutorApi` را implement می‌کند (بدون تغییر رفتار) تا حلقهٔ آفلاین با fake قابل تست باشد + executorهای write/delete تازه |
| `ai/AgentOrchestrator.kt` | خارج شدن `PendingAction`/`AgentStep` به فایل جدا (خالص-JVM) برای استفادهٔ مشترک دو assistant |
| `ai/AiModels.kt` | `ChatEntry` صاحب `imagePath` اختیاری شد |
| `ai/ConversationRepository.kt` | نام فایل پارامتردار (`FILE_ONLINE`/`FILE_GEMMA`) + round-trip تصویر |
| `ui/nav/AppNavigation.kt` + `Shell.kt` | ورودی منو و tutorial دوزبانهٔ دستیار آفلاین |
| `.github/workflows/android-release.yml` | گام جدید «Verify offline model source availability» که در CI وجود فایل `litert-community/…@7fa1d784…` و اندازهٔ بایت‌در‌بایت لحظهٔ build را چک می‌کند؛ گام verify-version از خواندن پویا از `build.gradle` استفاده می‌کند تا نسخه بعدی نیاز به تغییر دستی نداشته باشد |

## ۳) آنچه واقعاً تست شد (در این محیط)
| تست | نتیجه |
|---|---|
| کامپایل با kotlinc 2.3.10 (خالص-JVM): ۱۵ فایل `main` + ۹ کلاس تست | ✅ بدون خطا |
| ۷۰ تست واحد جدید (پین مدل، JSON parser، report parser، comparator، ModelStore/Verifier/DownloadManager، اعتبارسنجی سرویس، حلقهٔ local agent با engine و executor ساختگی) | ✅ ۷۰ از ۷۰ |
| ۲۶ تست قبلی `AgentActionValidatorTest` + `ServiceToolValidationTest` | ✅ ۲۶ از ۲۶ (عدم رگرسیون ریل) |

## ۳٫۵) ممیزی سازگاری با چک‌لیست رسمی LiteRT-LM (Skill «create-litert-lm-android-demo-app»)

گوگل در خود ریپوی LiteRT-LM یک چک‌لیست رسمی Compliance برای اپ‌های مصرف‌کننده دارد
(`agents/skills/create-litert-lm-android-demo-app/references/compliance_checklist_inference.md` @ v0.19.1).
نتیجهٔ ممیزی این پروژه بر اساس آن چک‌لیست، با مرجع دقیق کد:

| الزام رسمی | وضعیت | مدرک |
|---|---|---|
| `libOpenCL.so` (+ `libvndksupport.so`) در manifest با `required="false"` | Pass | `AndroidManifest.xml` (سه `<uses-native-library>` در `<application>`) |
| Data classes با آرگومان نام‌دار (نه Builder) | Pass | `LiteRtLmEngine.kt: createEngine()` → `EngineConfig(modelPath=…, backend=…, visionBackend=…, maxNumTokens=…, maxNumImages=…, cacheDir=…)` و `ConversationConfig(systemInstruction=…, initialMessages=…, samplerConfig=SamplerConfig(topK=64, topP=0.95, temperature=0.1), maxOutputToken=…, thinkingConfig=ThinkingConfig(enableThinking=false))` |
| `engine.initialize()` صریح | Pass | `LiteRtLmEngine.kt: ensureEngine()` (`.apply { initialize() }`) |
| `Conversation` (نه Session) | Pass | `e.createConversation(config).use { … }` در همان فایل |
| آبشار سه‌مرحله‌ای Init: چندوجهی-GPU → چندوجهی-CPU → متن-محض-CPU | Pass | `ensureEngine`: حلقهٔ `listOf(true, false)` برای vision GPU/CPU، سپس `createEngine(vision=false)`؛ پرچم `visionFailed` تا vision «یک‌بار» بشکند نه هر بار |
| ترتیب محتوا: `Content.Text` **قبل از** مدیا | Pass (باگ پیدا و اصلاح شد) | `complete()`: `contents.add(Content.Text(...))` سپس `contents.add(Content.ImageBytes(...))` |
| اجرای واقعی (بدون mock) در محصول | Pass | هیچ mock/fake در مسیر `main` نیست؛ fakeها فقط در `src/test` برای حلقهٔ عامل |
| MTP برای GPU | Skipped | MTP (speculative decoding) اختیاری است و پیش‌فرض runtime OFF است؛ برای E2B نیاز مؤثر نیست |
| Audio executor = CPU | Skipped | ورودی صوت در این اپ پیاده‌سازی نشده؛ `audioBackend` تنظیم نشده (NULL = executor صوت ساخته نمی‌شود) |

## ۳٫۶) وضعیت نسخه‌ها (تأیید با سورس واقعی)
| مؤلفه | نسخهٔ pin‌شده | چرا |
|---|---|---|
| LiteRT-LM AAR | `0.19.1` (آخرین stable در `maven.google.com` metadata، ۲۰۲۶‑۱۱‑۰۵) | API در تگ `v0.19.1` سمبل‌به‌سمبل با مصرف ما تطبیق داده شد (Engine/EngineConfig/Backend/Conversation/ConversationConfig/Message/Contents/Content.Text/ImageBytes/SamplerConfig/ThinkingConfig) |
| مدل | commit `7fa1d78473894f7e736a21d920c3aa80f950c0db` از `litert-community/gemma-4-E2B-it-litert-lm` (allowlist رسمی Gallery) | commit-pin = تغییرناپذیر؛ اندازه و RAM حداقل توسط Google تأیید شده؛ CI هر بار چک می‌کند فایل در همان commit هنوز با همان اندازه هست |
| Kotlin (پروژه) | 2.3.10 ≥ حداقل 2.3 مورد نیاز 0.17+/0.18/0.19 | در CHANGES 5.1.9 هم مصداق داشت |

### مدل واجد Vision در allowlist رسمی، برای دستگاه ۸GB (S23 FE)
| مدل | حجم | RAM رسمی | روی ۸GB |
|---|---|---|---|
| **Gemma-4-E2B-it** ← انتخاب ما | ۲٫۵۸GB | ۸GB | 🟢 پوشش دقیق |
| Gemma-4-E4B-it | ۳٫۶۵GB | ۱۲GB | 🔴 ناسازگار |
| Gemma-3n-E2B-it | ۳٫۶۶GB | ۸GB | 🟡 سنگین‌تر و قدیمی‌تر |
| هر مدل ۶GB-RAM | ۰٫۵۸–۱٫۸GB | ۶GB | ✅ ولی بدون vision |

## ۳٫۷) رفتار اختصاصی برای دستگاه ۸GB تنگ
- `hasHeadroomForInference()`: روی دستگاه ۸GB حداقل ≈۲.۵GB حافظهٔ واقعی آزاد قبل از لود مدل
  لازم است (۲.۵۸GB فایل mمی‌شود + vision prefill + KV)؛ اگر نباشد به‌جای کرش/خاموشی پیام
  «چند برنامهٔ باز را ببندید» می‌دهد. روی دستگاه ۱۲GB+ آستانه ۱.۴GB است.
- `ramVerdict()` همچنان RAM کلی دستگاه را با حداقل مدل چک می‌کند (پیام، نه کرش)، و storage
  قبل از دانلود صریح بررسی می‌شود.
- اگر vision executor روی دستگاه خطا بدهد، متن همچنان کار می‌کند و برای عکس پیغام
  «پردازش تصویر روی این دستگاه در دسترس نیست» چاپ می‌شود.

## ۳٫۸) ساختن APK
بیلد خودکار روی GitHub Actions انجام می‌شود و حالا روی **هر** برنچ (از جمله این جلسه) trigger
می‌شود. مسیر: Actions → workflow «KhodroYar Android APK Release» → ران موفق → artifacts
(`khodroyar-release-signed-5.2.0`). همان APK امضاشدهٔ release، آمادهٔ نصب «به‌روزرسانی» روی
گوشی شماست.

## ۴) آنچه تست نشد / نیاز به CI یا دستگاه
- کلاس‌های صرفاً-اندروید (`LiteRtLmEngine`، `ReportImagePrep`، `DeviceProfile`، `GemmaRuntime`،
  `GemmaAssistantViewModel`، `GemmaAssistantScreen`) با **بازبینی امضا به امضا در برابر سورس واقعی
  LiteRT-LM 0.19.1 کنترل شدند** اما با کامپایلر اندروید build نشده‌اند. وظیفهٔ CI: `./gradlew testDebugUnitTest assembleRelease`، و سپس گام جدید `Verify offline model source availability` که اندازهٔ سرور را با پین در `GemmaModelSpec` مساوی می‌خواهد.
- **هیچ نتیجه‌ای از دستگاه (زمان اول، latency، مصرف RAM، حرارت/باتری) به‌صورت اعداد تولیدکننده‌ای در این گزارش نیست** — چون هیچ دستگاهی در محیط نبود. تمام claimهایی که می‌گویند «سبز/آفلاین است» مربوط به کد و تست واحد است، نه تست روی گوشی.

## ۵) تأیید دقت منابع دانلود (پیش از هر تلقی کاربر)
| منبع | مقدار |
|---|---|
| repo رسمی گوگل | `litert-community/gemma-4-E2B-it-litert-lm` (allowlist google-ai-edge/gallery `1_0_11.json`) |
| فایل | `gemma-4-E2B-it.litertlm` |
| commit pin‌شده (نه branch) | `7fa1d78473894f7e736a21d920c3aa80f950c0db` |
| اندازهٔ دقیق از `x-linked-size` در headers | `2583085056` بایت |
| checksum کامل SHA-256 | در لحظهٔ دانلود در اپ از HF `api/models/<repo>/tree/<commit>` (`oid` LFS) خوانده می‌شود و در مقایسه اجباری است |
| اجازه‌نامه | Apache 2.0 |

سیستمِ دانلود هیچ‌گاه نسخهٔ «moving» نمی‌گیرد: همیشه commit pin‌شده. اگر گوگل آپدیت دهد، تعویض نسخه در اپ = فقط به‌روزرسانی `GemmaModelSpec.GEMMA_4_E2B` در یک کامیت.

## ۶) مدیریت حافظه و OOM
- یک Engine به‌ازای کل پردازه (singleton در `GemmaRuntime`).
- Conversation جدید به‌ازای هر درخواست؛ KV هرگز بین گفتگوهای طولانی انباشته نمی‌شود.
- `MAX_CONTEXT_TOKENS = 8192`، حداکثر history ۶ پیام/۳۵۰۰ کاراکتر، حداکثر تصویر ≤۱۲۸۰px.
- `inferenceMutex` تضمین تک‌پردازشی تا روی ۸GB دو prefill هم‌زمان اپ را نکشند.
- درود `onLeaveScreen()` → آزادسازی mapped RAM در همان لحظه، قبل از رفتن به صفحهٔ دیگر.
- خطای `OUT_OF_MEMORY` واقعی به کاربر «چند برنامه را ببندید» می‌گوید نه اینکه ساکت fallback شود.

## ۷) امنیت و اقدامات مؤثر
- هر WRITE/DELETE اول به‌صورت پیام مدل ارائه می‌شود، نه اجرا. خلاصهٔ نمایش‌داده‌شده به کاربر توسط **اپ** ساخته می‌شود (نه مدل) و فقط `execute()` بعد از `AgentActionValidator` به AppRepository می‌رسد.
- تأیید destructive: متن «غیرقابل بازگشت» + پیش‌نمایش رکورد هدف (از DB).
- هر تکرار service از همان Detector rate-based قبلی و همان `findServiceDuplicates` هشدار «مشابه وجود دارد» می‌دهد.
- هیچ مسیر fallback به کلود برای تسک‌های آفلاین وجود ندارد؛ اگر مدل جواب ندهد کاربر همان را می‌بیند.

## ۸) اقدامات تولیدی لازم بعد از این (برای مالک)
1. روی سیستمی با Android SDK: `./gradlew testDebugUnitTest assembleRelease` (CI قابل استفاده است).
2. این نسخه روی گوشی هدف S23 FE: تست airplane-mode چت فارسی، پاسخ به ابزار ثبت سرویس، عکس گزارش شرکت تولیدکنندهٔ توصیهٔ مقایسه؛ سپس اندازه‌گیری `logcat GemmaEngine` (زمان اول و به‌ازای هر inference) و گزارش RAM لحظه‌ای.
3. اگر E2B روی دستگاه ناتوان از توصیهٔ فوق شد، نسخهٔ بزرگ‌تر E4B قابل ارزیابی است ولی pin رسمی آن ۱۲GB RAM می‌خواهد — فعلاً در spec فعال نیست.
4. در صورت تغییر مدل (commit مختلف)، فقط `GemmaModelSpec` آن واحدلحاظ شده و هیچ چیز دیگری تغییر نمی‌کند.
