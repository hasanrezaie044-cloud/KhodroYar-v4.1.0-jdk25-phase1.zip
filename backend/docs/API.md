# مستندات API

Base URL: آدرس استقرار شما، مثلاً `https://<دامنه-شما>`. همهٔ پاسخ‌ها JSON هستند.

اگر `CLIENT_TOKEN` تنظیم شده باشد، هدر `Authorization: Bearer <token>` برای `/v1/*` لازم است. هدر اختیاری `X-Client-Id` (شناسهٔ تصادفی نصب) فقط برای محدودیت نرخ است.

## GET /health
بدون احراز هویت. `{"ok":true,"service":"khodroyar-ai-backend","configured":true,"enabled":true}`
(`configured` یعنی کلید روی سرور هست؛ خود کلید هرگز نمایش داده نمی‌شود.)

## GET /v1/status
`{"enabled","configured","defaultModel","allowedModels","limits":{...}}`

## GET /v1/models
مدل‌های مجازِ موجود در حساب شما و اینکه مدل پیش‌فرض واقعاً وجود دارد یا نه:
`{"models":["gpt-4o-mini"],"defaultModel":"gpt-4o-mini","defaultModelAvailable":true}`

## POST /v1/chat
```json
{
  "messages": [{"role":"system|user|assistant","content":"..."}],
  "model": "اختیاری، باید در ALLOWED_MODELS باشد",
  "max_tokens": 700,
  "temperature": 0.2,
  "json": true
}
```
پاسخ موفق:
```json
{"content":"...","model":"gpt-4o-mini","finishReason":"stop","usage":{"promptTokens":120,"completionTokens":40}}
```
`json:true` یعنی `response_format=json_object` (اگر مدل پشتیبانی نکند، خودکار بدون آن تکرار می‌شود). بک‌اند Tool Calling را فرض نمی‌کند؛ اپ خروجی JSON ساختاریافته را خودش اعتبارسنجی می‌کند.

## GET /admin/usage
با `Authorization: Bearer <ADMIN_TOKEN>`؛ فقط شمارنده‌ها (تعداد درخواست، خطا، توکن). هیچ متنی ذخیره نمی‌شود.

## خطاها
قالب: `{"error":{"code":"...","message":"...","retryAfterSec":N?}}`

| HTTP | code | معنی |
|---|---|---|
| 400 | `invalid_request` / `invalid_json` / `message_too_long` / `conversation_too_long` / `too_many_messages` / `model_not_allowed` / `upstream_bad_request` | ورودی نامعتبر |
| 401 | `unauthorized` | توکن اپ/ادمین نادرست |
| 402 | `credit_exhausted` | اعتبار GapGPT تمام شده |
| 402 | `budget_exceeded` | سقف روزانهٔ خودِ بک‌اند پر شده |
| 413 | `payload_too_large` | بدنهٔ درخواست بزرگ |
| 429 | `rate_limited` / `provider_rate_limited` | سقف درخواست؛ هدر `Retry-After` |
| 500 | `provider_error` / `internal_error` | خطای سرویس یا داخلی |
| 502 | `provider_auth_failed` | کلید روی سرور نامعتبر است (مدیر باید بررسی کند) |
| 502 | `model_unavailable` / `upstream_unreachable` | مدل/سرویس در دسترس نیست |
| 503 | `assistant_disabled` / `not_configured` | `AI_ENABLED=0` یا نبودن کلید |
| 504 | `upstream_timeout` | پایان زمان انتظار |

Retry فقط برای خطای شبکه/Timeout و 502/503/504 و حداکثر `MAX_RETRIES` بار انجام می‌شود؛ هرگز برای خطاهای 4xx.
