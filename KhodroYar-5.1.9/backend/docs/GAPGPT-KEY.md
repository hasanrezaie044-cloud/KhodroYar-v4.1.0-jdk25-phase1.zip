# تنظیم کلید GapGPT

> GapGPT برای استفاده از API **نیازمند API Key است و رایگان یا نامحدود فرض نمی‌شود**. هزینهٔ توکن از اعتبار حساب شما کسر می‌شود.

1. در حساب GapGPT خودتان وارد شوید و از بخش API / کلیدها یک کلید جدید بسازید (آدرس دقیق پنل را از مستندات رسمی GapGPT ببینید؛ این پروژه آدرسی حدس نمی‌زند).
2. اعتبار حساب را بررسی/شارژ کنید.
3. کلید را **فقط** به‌صورت Environment Variable روی سرور بگذارید:

   ```
   GAPGPT_API_KEY=<کلید شما>
   ```

   - روی سرویس‌های ابری (Render، Railway، Fly.io، …) از بخش Environment / Secrets استفاده کنید.
   - روی VPS در فایل `.env` کنار سرور (با دسترسی `chmod 600`) یا در `docker run -e` / `--env-file`.
4. کلید را هرگز در Git، پیام‌رسان، اسکرین‌شات، کد اندروید یا APK قرار ندهید. اگر لو رفت، همان لحظه در پنل GapGPT آن را باطل و کلید جدید بسازید.
5. بررسی مدل: مقدار `DEFAULT_MODEL` (پیش‌فرض `gpt-4o-mini`) فقط یک پیشنهاد اولیه است. با

   ```bash
   node scripts/test-connection.js https://<آدرس-بک‌اند-شما>
   ```

   یا `GET /v1/models` ببینید مدل واقعاً در حساب شما هست یا نه. اگر نبود، نام یک مدل موجود را در `DEFAULT_MODEL` (و در صورت نیاز `ALLOWED_MODELS`) بگذارید.
6. کنترل هزینه: `DAILY_REQUEST_BUDGET`، `DAILY_TOKEN_BUDGET`، `MAX_OUTPUT_TOKENS`، `RATE_LIMIT_PER_MINUTE/DAY` را متناسب با اعتبار خود تنظیم کنید. برای قطع فوری همه: `AI_ENABLED=0`.

تغییر ارائه‌دهنده در آینده: هر سرویس سازگار با OpenAI Chat Completions را می‌توان فقط با تغییر `GAPGPT_BASE_URL` و `GAPGPT_API_KEY` و `DEFAULT_MODEL` استفاده کرد.
