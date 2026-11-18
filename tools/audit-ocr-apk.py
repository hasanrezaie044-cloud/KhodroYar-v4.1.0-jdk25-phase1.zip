#!/usr/bin/env python3
"""Audit the actual online-only APK: CRC, retired-engine absence, size and ELF alignment."""
import hashlib
import json
from pathlib import Path
import struct
import sys
import zipfile


MAIN = Path(__file__).resolve().parents[1] / 'android/app/src/main'
PACKAGED_RESOURCES = [MAIN / 'res/font' / f'vazirmatn_{weight}.ttf' for weight in ('regular', 'medium', 'semibold', 'bold')] + [
    MAIN / 'res/raw/vazirmatn_license.txt', MAIN / 'res/raw/bazaar_updater_license.txt']


def audit(path):
    path = Path(path)
    assert 0 < path.stat().st_size < 30_000_000, 'Online-only APK must stay below 30 MB'
    libraries = []
    with zipfile.ZipFile(path) as z:
        assert z.testzip() is None, 'APK ZIP CRC failure'
        names = z.namelist()
        assert not any(n.startswith('assets/ocr/') or n.endswith('.traineddata') for n in names), 'Retired offline models still packaged'
        assert not any('tesseract' in n.lower() or 'leptonica' in n.lower() or n.endswith('/liblept.so') for n in names), 'Retired native OCR engine still packaged'
        # Resources may have optimised names. Check the exact packaged bytes,
        # not a source-only claim that fonts/notices will survive shrinking.
        sizes = {path.stat().st_size for path in PACKAGED_RESOURCES}
        hashes = {hashlib.sha256(z.read(info)).hexdigest() for info in z.infolist()
                  if info.filename.startswith('res/') and info.file_size in sizes}
        for resource in PACKAGED_RESOURCES:
            assert hashlib.sha256(resource.read_bytes()).hexdigest() in hashes, f'Missing/changed packaged resource: {resource.name}'
        for name in names:
            if not name.startswith('lib/') or not name.endswith('.so'):
                continue
            data = z.read(name)
            assert data[:4] == b'\x7fELF', name
            if name.split('/')[1] in ('arm64-v8a', 'x86_64'):
                assert data[4:6] == b'\x02\x01', name
                offset = struct.unpack_from('<Q', data, 32)[0]
                size, count = struct.unpack_from('<HH', data, 54)
                loads = [struct.unpack_from('<IIQQQQQQ', data, offset + i * size) for i in range(count)]
                assert all(row[7] >= 16384 for row in loads if row[0] == 1), f'Not 16 KB aligned: {name}'
            libraries.append(name)
    return {'apk': path.name, 'bytes': path.stat().st_size, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
            'zip_crc': 'passed', 'ocr_mode': 'online-only', 'offline_model_assets': 'absent (online-only)',
            'offline_native_engine': 'absent', 'bundled_notices_fonts': 'all hashes verified', 'native_64_bit_16kb_alignment': 'passed', 'libraries': libraries}


if __name__ == '__main__':
    result = audit(sys.argv[1])
    print(json.dumps(result, indent=2))
    if len(sys.argv) > 2:
        Path(sys.argv[2]).write_text(json.dumps(result, indent=2) + '\n')
