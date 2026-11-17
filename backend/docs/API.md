# API بک‌اند قدیمی — قرارداد بدون سهمیهٔ محلی

این proxy در اتصال‌های فعال اندروید۵.۶.۳ نیست؛ هیچ fallback پنهانی به آن وجود ندارد. مستندات زیر برای نگهداری مستقل سرور قدیمی‌اند. همهٔ پاسخ‌ها JSON هستند؛ base URL ریشهٔ استقرار امن خودِ مالک است.

## احراز هویت و حفاظت حافظه

اگر `CLIENT_TOKEN` تنظیم باشد، `/v1/*` هدر `Authorization: Bearer <client-token>` می‌خواهد. `/admin/usage` همیشه به `ADMIN_TOKEN` تنظیم‌شده نیاز دارد. این‌ها کلید upstream نیستند. `X-Client-Id` دیگر gate نرخ یا مصرف ایجاد نمی‌کند. کلید قطع اضطراری مدیر `AI_ENABLED=0` باقی است.

بدنهٔ درخواست حداکثر۳۲MiB برای حفاظت heap پذیرفته می‌شود؛ محدودیت روزانه/توکن/تعداد پیام/طول متن و رزرو مصرف نداریم. تنظیمات قدیمی سقف‌ها نادیده گرفته می‌شوند.

## GET /health

بدون احراز هویت:

```json
{"ok":true,"service":"khodroyar-ai-backend","configured":true,"enabled":true}
```

`configured` فقط وجود کلید روی سرور است؛ کلید یا اعتبار حساب افشا/اثبات نمی‌شود.

## GET /v1/status

```json
{"enabled":true,"configured":true,"defaultModel":"gpt-4o-mini","allowedModels":["gpt-4o-mini"],"requestBodyMaxBytes":33554432}
```

نام مدل نمونه است، نه تضمین موجودبودن، رایگان‌بودن یا سهمیه. پاسخ فیلد `limits` ندارد.

## GET /v1/models

فقط GET فرادادهٔ upstream و تقاطع allowlist سرور:

```json
{"models":["gpt-4o-mini"],"defaultModel":"gpt-4o-mini","defaultModelAvailable":true}
```

## POST /v1/chat

```json
{
  "messages": [{"role":"user","content":"متن پرسش"}],
  "model": "gpt-4o-mini",
  "temperature": 0.2,
  "json": false
}
```

- `messages` آرایهٔ ناتهی از متن با roleهای `system`، `user` و `assistant` است؛ پیام آخر نباید `assistant` باشد. این proxy قدیمی native tools، تصویر یا SSE ارائه نمی‌کند؛ اتصالات بومی اندروید قرارداد دیگری دارند.
- `model` اختیاری است و باید در allowlist مدیر باشد. هیچ انتخاب خودکار مدل/سرویس جایگزین نداریم.
- `max_tokens` اختیاری: فقط اگر کلاینت صریحاً یک عدد صحیح مثبت بدهد، عیناً ارسال می‌شود؛ بدون آن حداکثر محلی یا پیش‌فرض۷۰۰ نداریم.
- `json:true` یعنی یک درخواست با `response_format=json_object`. اگر upstream آن را رد کند، درخواست بدون آن تکرار **نمی‌شود**.
- timeout یا هر خطای quota/credit/auth/network/server هم فقط نتیجهٔ همان یک تلاش را برمی‌گرداند؛ خرید یا retry خودکار وجود ندارد.

پاسخ موفق نمونه:

```json
{"content":"پاسخ","model":"gpt-4o-mini","finishReason":"stop","usage":{"promptTokens":120,"completionTokens":40}}
```

`usage` فقط مقدار اعلامی سرویس است و ممکن است `null` یا ناقص باشد؛ تخمین توکن یا بودجه نیست.

## GET /admin/usage

با `Authorization: Bearer <admin-token>`: `date` (برچسب UTC)، `requests`، `errors`، `promptTokens` و `completionTokens`. هیچ متن، تصویر، کلید یا سقف اعمال‌شونده‌ای ذخیره نمی‌شود. شمارنده‌ها با روز UTC عوض می‌شوند، اما هرگز درخواست را رد نمی‌کنند.

## خطاها

قالب: `{"error":{"code":"...","message":"...","retryAfterSec":N?}}`. متن خام upstream و کلید بازتاب داده نمی‌شوند؛ `Retry-After` فقط راهنمای سرویس است، نه زمان‌بندی retry برنامه.

| HTTP | code | معنی |
|---|---|---|
|400|`invalid_request` / `invalid_json` / `model_not_allowed` / `upstream_bad_request`|پروتکل/مدل نامعتبر یا رد upstream|
|401|`unauthorized`|توکن کلاینت/ادمین نادرست|
|402|`credit_exhausted`|اعتبار واقعی upstream تمام شده|
|413|`payload_too_large`|حفاظت حافظهٔ بدنه|
|429|`provider_rate_limited`|محدودیت واقعی upstream، نه نرخ محلی|
|500|`provider_error` / `internal_error`|خطای سرویس یا داخلی|
|502|`provider_auth_failed`|کلید upstream سرور نامعتبر/غیرمجاز|
|502|`upstream_not_found` / `upstream_unreachable`|مسیر/مدل پیدا نشد یا اتصال برقرار نشد|
|503|`assistant_disabled` / `not_configured`|کلید قطع مدیر یا نبود کلید سرویس|
|504|`upstream_timeout`|مهلت شبکه تمام شد|

کدهای قدیمی `budget_exceeded`، `message_too_long`، `conversation_too_long` و `too_many_messages` دیگر از این پیاده‌سازی تولید نمی‌شوند.
