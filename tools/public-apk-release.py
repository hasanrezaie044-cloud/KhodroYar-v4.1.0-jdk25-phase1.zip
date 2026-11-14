#!/usr/bin/env python3
"""Explicitly authorised public delivery, only after the workflow's release/UI gates.

Never signs, rebuilds, changes or re-signs an APK. Pin the original audited bytes
and signed-runtime proof, re-audit the APK, publish via gh, then anonymously read
back the entire actual HTTPS asset. Private delivery remains the workflow default.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

CERTIFICATE = '03bfbc3575667403c3259fae345f3eb1ad3045373cb62123be376ef4898caaf0'
BASELINE = '797d7061c5f669cf4fa3e7361c0b3c2dca10490c'
OFFLINE_REVIEW = dict(airplane_mode='1', wifi_on='0', active_network=False)
REVIEW = dict(passed=True, text_source='user-entered synthetic fixture; NOT image OCR', rows=3, count_per_row='1',
    period_start='1405/07/01', period_end='1405/07/30', first=dict(date='1405/07/05', count='1', km='', hours=''),
    metrics_blank=True, count_review_required=False, reports_entry=True, main_metric_editors_absent=True,
    example=dict(date='1405/07/05', company=2, app=3, difference=1), corrected_company=4, corrected_difference=-1,
    correction_reverted=True, app_services_after=3, consent_cancelled=True)
BAZAAR = dict(passed=True, source='synthetic IPC fixture; NOT real Bazaar', automatic_entry_prompt=True,
    method='getRemoteVersionCode', queried_package='com.khodroyar.app', update_uri='bazaar://details/modal?id=com.khodroyar.app')


def validate_runtime(runtime):
    """Exact signed-app UI/IPC evidence. Deliberately NOT live OCR/Bazaar proof."""
    if (runtime.get('passed') is not True or runtime.get('target_debuggable') is not False
            or runtime.get('target_package') != 'com.khodroyar.app'
            or runtime.get('instrumentation_package') not in ('com.khodroyar.app.releasesmoke', 'com.khodroyar.app.releasesmoke.test')
            or type(runtime.get('target_uid')) is not int or type(runtime.get('instrumentation_uid')) is not int
            or runtime['target_uid'] == runtime['instrumentation_uid']
            or runtime.get('ocr_mode') != 'online-only' or runtime.get('live_ocr_exercised') is not False
            or runtime.get('review') != REVIEW or runtime.get('bazaar') != BAZAAR
            or any(runtime.get('offline_review', {}).get(phase) != OFFLINE_REVIEW for phase in ('before', 'after'))):
        raise ValueError('Required actual signed-app count-first/consent/simulated-Bazaar IPC evidence did not pass')
    return runtime


def digest(path):
    result = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(512 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def verified_proof(apk, verification, expected_head):
    """Metadata/hash gates; fixture tests of this function are NOT actual APK tests."""
    apk, verification = Path(apk), Path(verification)
    if not re.fullmatch(r'[0-9a-f]{40}', expected_head) or verification.stat().st_size > 131072:
        raise ValueError('Invalid expected commit or oversized proof')
    proof = json.loads(verification.read_text())
    size = apk.stat().st_size
    if (apk.is_symlink() or not 0 < size < 30_000_000 or proof.get('bytes') != size
            or proof.get('sha256') != digest(apk) or proof.get('head_sha') != expected_head
            or proof.get('package') != 'com.khodroyar.app' or proof.get('certificate_sha256') != CERTIFICATE
            or proof.get('debuggable') is not False or proof.get('baseline') != BASELINE
            or proof.get('room_version') != 6 or proof.get('zipalign_16kb') != 'passed'
            or proof.get('zip_crc') != 'passed' or proof.get('ocr_mode') != 'online-only'
            or proof.get('offline_model_assets') != 'absent (online-only)' or proof.get('offline_native_engine') != 'absent'
            or proof.get('native_64_bit_16kb_alignment') != 'passed' or proof.get('bundled_notices_fonts') != 'all hashes verified'
            or not re.fullmatch(r'\d+(?:\.\d+){1,3}', proof.get('version_name', ''))
            or type(proof.get('version_code')) is not int or proof['version_code'] < 75):
        raise ValueError('Public APK does not match the original audited online-only candidate')
    validate_runtime(proof.get('signed_runtime', {}))
    return proof


def audit_actual_apk(apk):
    spec = importlib.util.spec_from_file_location('actual_apk_audit', Path(__file__).with_name('audit-ocr-apk.py'))
    auditor = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(auditor)
    auditor.audit(apk)  # Actual CRC, retired-engine/model absence, size and ELF alignment.


def filename(proof):
    return f"KhodroYar-{proof['version_name']}-release-signed.apk"


def prepare(apk, verification, directory, expected_head):
    proof = verified_proof(apk, verification, expected_head)
    audit_actual_apk(apk)
    root = Path(directory)
    root.mkdir(parents=True, exist_ok=False)
    name = filename(proof)
    shutil.copyfile(apk, root / name)
    shutil.copyfile(verification, root / 'verification.json')
    (root / 'SHA256SUMS').write_text(f"{proof['sha256']}  {name}\n")
    # No credentials, keystores, private delivery keys, or test APK are copied.
    policy_version = '۳' if proof['version_code'] >= 76 else '۲'
    agent_notes = """## ارتقای ۵.۶ از ۵.۵.۸: ایجنت فارسی فقط‌رایگان

- اتصال مستقیم OpenRouter با کلید شخصیِ Keystore-encrypted، مدل اولیه openrouter/free و رضایت تازه؛ بدون بک‌اند اجباری. فهرست مدل/قیمت صفر پیش از هر درخواست، max_price صفر، no paid fallback و منع خرید اعتبار.
- native tool_calls با schema مجاز، برنامهٔ چندمرحله‌ای و ادامه پس از تأیید؛ هر ثبت/ویرایش/حذف دارای snapshot، پیش‌نمایش، دکمهٔ تأیید، تراکنش و readback است. همان محاسبات/دیتابیس و journal قبلی استفاده می‌شوند؛ Room 6 و امکانات دستی آفلاین تغییر نکرده‌اند.
- سرویس تکی/بازه/گروهی، مقایسهٔ مالی، ریز قیمت واقعی، بیمه/نگهداری و تحلیل فقط‌خواندنی گزارش شرکت؛ تاریخ و مقدار نامشخص ساخته نمی‌شود. چت RTL متنی، SSE محدود، تاریخچه و نتایج واقعی ابزار؛ بدون صدا/میکروفون/مدل آفلاین.
- تست‌های ایجنت و free-only با پاسخ HTTP/SSE ساختگی‌اند؛ کیفیت واقعی فارسی، سهمیهٔ حساب و اتصال زندهٔ OpenRouter را اثبات نمی‌کنند. کلید شخصی یا سند خصوصی وارد CI نشده است. نتایج دقیق JVM/بومی در artifactهای همان Actions run است؛ آزمون APK امضاشدهٔ زیر مربوط به فرم‌ها/گزارش/بازار مصنوعی است.
- راهنمای کامل: docs/OPENROUTER-AGENT.fa.md. کاربر کلید تازه‌اش را فقط در برنامه وارد می‌کند؛ دیتابیس کامل یا اصل سند به OpenRouter upload نمی‌شود.

## قابلیت‌ها و بررسی‌های قبلیِ حفظ‌شده

""" if proof['version_code'] >= 76 else ''
    (root / 'test-summary.md').write_text(f"""# KhodroYar {proof['version_name']}

بر پایهٔ ۵.۴.۰ بازیابی‌شده؛ بستهٔ `com.khodroyar.app`، کد {proof['version_code']}، Room 6.

{agent_notes}- APK اصلی Release با امضای اصلی، R8 و resource shrinking فعال، غیرقابل‌دیباگ.
- حذف موتور/مدل‌های OCR آفلاین؛ خواندن تصویر فقط با FastOCR، کلید شخصی رمزنگاری‌شده و تأیید صریح هر ارسال است. ثبت سوابق و محاسبات عادی آفلاین باقی می‌مانند.
- حفظ بایت‌به‌بایت PDF تک‌صفحه‌ای بدون چرخش؛ آماده‌سازی تصاویر/صفحه‌های دیگر تا ۶ مگاپیکسل، بدون مدل دانلودی.
- بازیابی قالب تاریخ و شماره‌ردیف چسبیده، هشدار ردیف‌های نخوانده و «در متن یافت نشد» به‌جای ادعای قطعی نبود سرویس؛ هیچ تاریخ/سرویس از تعدادهای برنامه ساخته نمی‌شود.
- بازخوانی متن خصوصی ذخیره‌شده بدون ارسال/هزینه، و ورود صریح متن یک صفحه برای بازبینی. پیش‌نویس قدیمی حفظ می‌شود.
- گزارش ← مقایسهٔ شرکت: جدول فشردهٔ تاریخ/شرکت/برنامه/اختلاف، بدون شرط کیلومتر، ساعت یا تأیید جزئیات. اختلاف = برنامه − شرکت؛ مثال ۲/۳ برابر +۱ است.
- اصلاح صریح تعداد شرکت با علامت *، جدا از ردیف‌های OCR و سوابق برنامه، قابل بازگشت است؛ هیچ سرویس خودکار برای برابرکردن تعداد ساخته یا تغییر داده نمی‌شود.
- جزئیات کیلومتر/ساعت در بخش اختیاری باقی می‌مانند؛ ساعت نامشخص همچنان صفر نیست.
- اصلاح ترافیک: روش دریافت و تحلیلِ نسخهٔ سالم ۵.۵.۶ بازگردانده شد؛ هر شش مسیر فقط با type=car، origin و destination از نشان پرسیده می‌شوند. پارامترهای اضافی و شرط polyline/تطابق هندسه حذف شدند؛ همان duration و distance در legs مبنا هستند. مبدأ/مقصد و جهت سه مسیر جدید کشوری، میثمی و حبیب‌اللهی حفظ شده‌اند؛ مسیر عملی را نشان انتخاب می‌کند، نه کنترل هندسهٔ برنامه.
- آزمون بومی ترافیک با HTTP ساختگیِ همان قالب قدیمی بدون polyline: نمایش وضعیت هر شش مسیر و به‌روزرسانی دستی، بدون استفاده از کلید شخصی یا ادعای آزمون زندهٔ نشان. این رفتار ۵.۵.۸ در ارتقا حفظ شده است.
- متن حریم خصوصی/سلب مسئولیت نسخهٔ {policy_version}، شامل خطای ورودی، OCR، خدمات ثالث و غیررسمی بودن خروجی‌ها؛ نسخهٔ قابل استفاده برای بازار در مخزن است.
- اعلان نسخهٔ جدید هنگام ورود، فقط پس از پاسخ نسخهٔ بازار؛ دکمهٔ به‌روزرسانی صفحهٔ همان بسته را در خود بازار باز می‌کند. نبود بازار/شبکه ورود را مسدود نمی‌کند.
- آزمون همان APK امضاشده روی امولاتور API 35: ثبت ۳ سرویس مصنوعی از فرم واقعی، SAF، رضایت/انصراف FastOCR، ورود صریح متن مصنوعیِ بدون کیلومتر/ساعت، نمایش شرکت ۲ / برنامه ۳ / اختلاف +۱ در ۱۴۰۵/۰۷/۰۵، اصلاح دستی و بازگشت، حفظ تعداد برنامه، IPC و لینک به‌روزرسانی با **بازار شبیه‌سازی‌شده**.
- آزمون ادغام آنلاین از پاسخ HTTP ساختگی استفاده می‌کند؛ **آزمون زندهٔ FastOCR، انتشار واقعی در بازار یا آزمایش فیزیکی Samsung ادعا نمی‌شود**. سند خصوصی کاربر در مخزن/CI نیست؛ کامل‌خوانی هر اسکن تضمین نمی‌شود.
- ابزار CI، APK تست و بازار مصنوعی جزو فایل تحویلی نیستند. بسته، گواهی اصلی، Room 6 و محاسبات مالی حفظ شده‌اند؛ مجوز نصب/دسترسی عمومی فایل اضافه نشده است.

برای انتشار بازار همین APK را با همین بسته/امضا بارگذاری کنید. نمایش اعلان برای کاربران نسخه‌های حاوی این قابلیت، پس از انتشار کد بالاتر در بازار ممکن است؛ ۵.۵.۵ قبلی هنوز اعلان جدید را ندارد.

اندازه: {proof['bytes']:,} بایت؛ سقف جدید ۳۰٬۰۰۰٬۰۰۰ بایت.

SHA-256 فایل: `{proof['sha256']}`

SHA-256 گواهی اصلی: `{CERTIFICATE}`

Commit: `{expected_head}`
""", encoding='utf-8')
    print(f"Prepared exact originally signed APK: {name}; {proof['bytes']} bytes; {proof['sha256']}")


def gh_json(endpoint, *args, missing_ok=False):
    result = subprocess.run(['gh', 'api', endpoint, *args], capture_output=True, text=True, timeout=120)
    if result.returncode:
        if missing_ok and '(HTTP 404)' in result.stderr:
            return None
        raise RuntimeError(result.stderr.strip() or 'GitHub API failed')
    return json.loads(result.stdout)


def anonymous_readback(url, expected_size, expected_sha):
    """No Authorization header, platform traffic token, or signed-in user required."""
    for attempt in range(4):
        try:
            request = urllib.request.Request(url, headers={'User-Agent': 'KhodroYar-APK-readback'})
            with urllib.request.urlopen(request, timeout=90) as response:
                if response.status != 200:
                    raise ValueError('Public APK GET did not return 200')
                result, size = hashlib.sha256(), 0
                for chunk in iter(lambda: response.read(512 * 1024), b''):
                    size += len(chunk)
                    if size > expected_size:
                        raise ValueError('Public APK exceeds the audited length')
                    result.update(chunk)
            if size != expected_size or result.hexdigest() != expected_sha:
                raise ValueError('Actual public HTTPS download differs from the audited APK')
            return
        except (urllib.error.URLError, TimeoutError, OSError):
            if attempt == 3:
                raise
            time.sleep(2 ** attempt)


def publish(directory, repository, expected_head, run_id):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository) or not re.fullmatch(r'\d{1,20}', run_id):
        raise ValueError('Invalid repository or workflow run identifier')
    root = Path(directory)
    proof_path = root / 'verification.json'
    initial = json.loads(proof_path.read_text())
    if not re.fullmatch(r'\d+(?:\.\d+){1,3}', initial.get('version_name', '')):
        raise ValueError('Invalid APK version')
    apk = root / filename(initial)
    proof = verified_proof(apk, proof_path, expected_head)
    audit_actual_apk(apk)
    tag = 'v' + proof['version_name']
    api = f'repos/{repository}'
    # Never replace an older release/tag or attach a new APK to the wrong commit.
    if gh_json(f'{api}/releases/tags/{tag}', missing_ok=True) is not None:
        raise ValueError(f'Release {tag} already exists; refusing to overwrite assets')
    tag_info = gh_json(f'{api}/git/ref/tags/{tag}', missing_ok=True)
    if tag_info is not None and tag_info.get('object', {}).get('sha') != expected_head:
        raise ValueError(f'Tag {tag} already exists at another object; refusing to reuse it')
    build_url = f'https://github.com/{repository}/actions/runs/{run_id}'
    notes = root / 'test-summary.md'
    with notes.open('a', encoding='utf-8') as stream:
        stream.write(f'\nصفحهٔ بیلد و آزمون‌ها: {build_url}\n')
    assets = [apk, proof_path, root / 'SHA256SUMS', notes]
    subprocess.run(['gh', 'release', 'create', tag, '--repo', repository, '--target', expected_head,
                    '--title', f"KhodroYar {proof['version_name']} — Traffic compatibility restored",
                    '--notes-file', str(notes), '--draft', '--latest=false', *map(str, assets)], check=True, timeout=240)
    release = gh_json(f'{api}/releases/tags/{tag}', missing_ok=True)
    if release is None:
        # A draft can precede the creation of its Git tag; list authenticated
        # drafts if the by-tag endpoint has not exposed this draft yet.
        drafts = [r for r in gh_json(f'{api}/releases?per_page=100') if r['tag_name'] == tag]
        if len(drafts) != 1:
            raise ValueError('Exactly one newly created draft release is required')
        release = drafts[0]
    if release['target_commitish'] != expected_head or not release['draft']:
        raise ValueError('Draft release target does not match the verified source commit')
    release = gh_json(f"{api}/releases/{release['id']}", '--method', 'PATCH', '-F', 'draft=false', '-f', 'make_latest=false')
    published_tag = gh_json(f'{api}/git/ref/tags/{tag}')
    if published_tag.get('object', {}).get('sha') != expected_head:
        raise ValueError('Published release tag does not point to the verified source commit')
    matches = [a for a in release['assets'] if a['name'] == apk.name and a['size'] == proof['bytes']]
    if len(matches) != 1:
        raise ValueError('Published APK asset missing or has a different length')
    url = matches[0]['browser_download_url']
    if not url.startswith(f'https://github.com/{repository}/releases/download/{tag}/'):
        raise ValueError('Unexpected APK asset host/path')
    anonymous_readback(url, proof['bytes'], proof['sha256'])
    receipt = dict(download_url=url, release_url=release['html_url'], build_url=build_url,
                   head_sha=expected_head, sha256=proof['sha256'], bytes=proof['bytes'], anonymous_https_readback='passed')
    print('::notice title=Public signed APK download::' + url)
    print('::notice title=Public APK full HTTPS readback::' + json.dumps(receipt, sort_keys=True))
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as stream:
            stream.write(f"## Signed APK ready\n\n[Download APK]({url}) · [Release]({release['html_url']}) · [Build]({build_url})\n\n")
            stream.write(f"Anonymous full HTTPS download/hash verified: {proof['bytes']:,} bytes; SHA-256 `{proof['sha256']}`.\n")
    return receipt


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    p = commands.add_parser('prepare')
    p.add_argument('apk'); p.add_argument('verification'); p.add_argument('directory'); p.add_argument('--head', required=True)
    p = commands.add_parser('publish')
    p.add_argument('directory'); p.add_argument('--head', required=True); p.add_argument('--repository', required=True); p.add_argument('--run-id', required=True)
    args = parser.parse_args()
    try:
        if args.command == 'prepare':
            prepare(args.apk, args.verification, args.directory, args.head)
        else:
            publish(args.directory, args.repository, args.head, args.run_id)
    except (ValueError, AssertionError, OSError, RuntimeError, subprocess.SubprocessError) as error:
        print(f'::error::Public signed APK delivery did not finish: {error}', file=sys.stderr)
        sys.exit(1)
