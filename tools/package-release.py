#!/usr/bin/env python3
"""Give verified binaries clear, dynamic versioned names. Never packages signing material."""
import hashlib
import json
import shutil
from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent

def main():
    evidence = json.loads((ROOT / '.verification/release-verification.json').read_text())
    version = evidence['final']['versionName']
    directory = ROOT / 'deliverables' / f'KhodroYar-v{version}-release'
    directory.mkdir(parents=True, exist_ok=True)
    for key, name in [('final', f'KhodroYar-v{version}-release.apk'), ('optionalOcr', f'KhodroYar-Persian-OCR-v{version}.apk')]:
        source = ROOT / evidence[key]['file']
        assert hashlib.sha256(source.read_bytes()).hexdigest() == evidence[key]['sha256']
        shutil.copyfile(source, directory / name)
    data = ROOT / 'deliverables/ocr-data/fas.traineddata'
    lock = json.loads((ROOT / 'docs/persian-ocr-data-lock.json').read_text())
    assert data.stat().st_size == lock['bytes'] and hashlib.sha256(data.read_bytes()).hexdigest() == lock['sha256']
    shutil.copyfile(data, directory / 'fas.traineddata')
    for name in ['OFFLINE-RECONCILIATION.md', 'THIRD-PARTY-OCR.md', 'persian-ocr-data-lock.json']:
        shutil.copyfile(ROOT / 'docs' / name, directory / name)
    for file in (ROOT / 'docs/licenses').glob('LICENSE*'): shutil.copyfile(file, directory / file.name)
    shutil.copyfile(ROOT / '.verification/release-verification.json', directory / 'release-verification.json')
    (directory / 'README.txt').write_text(f'''خودرویار نسخه {version}

۱. KhodroYar-v{version}-release.apk را به‌صورت به‌روزرسانی نصب کنید؛ برنامهٔ قبلی را حذف نکنید.
۲. برای OCR آفلاین، KhodroYar-Persian-OCR-v{version}.apk اختیاری را نصب کنید.
۳. افزونهٔ OCR را باز کنید و فایل fas.traineddata همین بسته را وارد کنید.
۴. در پنل ابزارهای خودرویار، مغایرت‌گیری کارکرد را باز کنید.

OCR بدون شبکه و کلید API کار می‌کند. تمام ردیف‌ها و کامل بودن صفحات باید بازبینی شوند.
ورود دستی بدون افزونه هم ممکن است. هیچ سرویس موجودی خودکار تغییر نمی‌کند.
گواهی امضا، اندازه‌ها و آزمون‌های اجراشده در release-verification.json ثبت شده‌اند.
این پوشه هیچ keystore، رمز عبور یا کلید خصوصی ندارد.
''', encoding='utf-8')
    entries = sorted(p for p in directory.iterdir() if p.is_file() and p.name != 'SHA256SUMS.txt')
    (directory / 'SHA256SUMS.txt').write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in entries))
    print(directory)

if __name__ == '__main__': main()
