# نصب و استقرار

## چه چیزهایی را باید خودتان تهیه کنید (چیزی از طرف ما ساخته نشده)
- یک سرویس‌دهندهٔ اجرای Node.js یا Docker (VPS یا PaaS مثل Render / Railway / Fly.io / هر میزبان دلخواه)
- یک آدرس **HTTPS** عمومی (دامنهٔ خودتان یا دامنهٔ پیش‌فرض سرویس‌دهنده). اپ اندروید آدرس غیر HTTPS را قبول نمی‌کند.
- حساب GapGPT با اعتبار و **API Key** (`docs/GAPGPT-KEY.md`)
- (اختیاری) یک مقدار دلخواه برای `CLIENT_TOKEN` و `ADMIN_TOKEN`

هیچ آدرس یا حساب نمونه‌ای در این پروژه ساخته نشده است.

## گزینهٔ الف) Docker
```bash
cd backend
docker build -t khodroyar-ai .
docker run -d --name khodroyar-ai --restart unless-stopped -p 8787:8787 \
  -e GAPGPT_API_KEY=... -e CLIENT_TOKEN=... -e ADMIN_TOKEN=... \
  -e TRUST_PROXY=1 khodroyar-ai
```
سپس جلوی آن یک Reverse Proxy با TLS بگذارید (Caddy / Nginx + Let's Encrypt). نمونهٔ Caddy:
```
ai.example-your-domain.com {
  reverse_proxy 127.0.0.1:8787
}
```
(نام دامنه فقط مثال است؛ دامنهٔ واقعی خودتان را بگذارید.)

## گزینهٔ ب) VPS با Node.js ≥ 18.17
```bash
cd backend && cp .env.example .env && chmod 600 .env   # مقادیر را پر کنید
npm start                                              # برای دائمی‌کردن: systemd یا pm2
```

## گزینهٔ ج) PaaS (Render / Railway / Fly.io …)
1. مخزن (یا پوشهٔ `backend`) را متصل کنید؛ Build: خالی/`npm install`؛ Start: `npm start`.
2. در بخش Environment Variables مقادیر `.env.example` را وارد کنید (به‌ویژه `GAPGPT_API_KEY` به‌عنوان Secret، و `TRUST_PROXY=1`).
3. HTTPS را خود سرویس‌دهنده فراهم می‌کند. آدرس نهایی را یادداشت کنید.

## بعد از استقرار
1. `node scripts/test-connection.js https://<آدرس>` (مراحل در `docs/TESTING.md`).
2. در اپ: دستیار هوشمند ← تنظیمات ← آدرس سرور را وارد کنید. یا هنگام ساخت APK: `./gradlew assembleRelease -PaiBackendUrl=https://<آدرس>` (آدرس عمومی است، محرمانه نیست).
3. سقف‌ها را با اعتبار خود هماهنگ کنید: `DAILY_REQUEST_BUDGET`, `DAILY_TOKEN_BUDGET`, `MAX_OUTPUT_TOKENS`.

## نکات امنیتی و عملیاتی
- `CLIENT_TOKEN` فقط هویت اپ شما را نشان می‌دهد و چون داخل دستگاه کاربر قرار می‌گیرد «راز» حساب نمی‌شود؛ محافظ اصلی، محدودیت نرخ و سقف بودجهٔ سرور است. بهتر است توکن را در اپ از تنظیمات وارد کنید نه داخل APK.
- محدودیت نرخ در حافظهٔ یک نمونه است. اگر چند نمونه (replica) اجرا می‌کنید سقف‌ها در هر نمونه جداگانه اعمال می‌شود؛ برای شروع یک نمونه کافی است.
- قطع اضطراری: `AI_ENABLED=0` و ری‌استارت.
- لاگ‌ها فقط متد/مسیر/وضعیت/زمان/تعداد توکن دارند. پیام‌ها ذخیره نمی‌شوند و برای آموزش یا تبلیغات استفاده نمی‌شوند (توجه: سیاست نگهداری داده‌ها در GapGPT مستقل از این سرور است؛ شرایط آن را خودتان بررسی کنید).
