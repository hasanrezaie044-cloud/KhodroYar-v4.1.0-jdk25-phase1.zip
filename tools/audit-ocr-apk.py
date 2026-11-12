#!/usr/bin/env python3
"""Inspect the real APK: bundled model bytes, ZIP CRC, size and 16-KB native alignment."""
import hashlib
import json
from pathlib import Path
import struct
import sys
import zipfile


def audit(path):
    path = Path(path)
    assert 0 < path.stat().st_size < 100_000_000, 'Whole APK must be below 100,000,000 bytes'
    libraries = []
    with zipfile.ZipFile(path) as z:
        assert z.testzip() is None, 'APK ZIP CRC failure'
        expected_manifest = (Path(__file__).resolve().parents[1] / 'android/app/src/main/assets/ocr/manifest.json').read_bytes()
        assert z.read('assets/ocr/manifest.json') == expected_manifest, 'Packaged OCR provenance differs from verified source'
        manifest = json.loads(expected_manifest)
        for entry in manifest['files']:
            data = z.read('assets/ocr/' + entry['name'])
            assert len(data) == entry['bytes'], entry['name']
            assert hashlib.sha256(data).hexdigest() == entry['sha256'], entry['name']
        assert {r['name'] for r in manifest['files'] if r['name'].endswith('.traineddata')} == {'fas.traineddata', 'ara.traineddata', 'eng.traineddata'}
        reference_manifest = (Path(__file__).resolve().parents[1] / 'android/app/src/main/assets/ocr/print-references.json').read_bytes()
        assert z.read('assets/ocr/print-references.json') == reference_manifest, 'Packaged print-reference provenance differs'
        references = json.loads(reference_manifest)['files']
        sizes = {e['bytes'] for e in references}
        # aapt2 may shorten resource filenames; verify the original bytes, not names.
        resource_hashes = {hashlib.sha256(z.read(info)).hexdigest() for info in z.infolist() if info.filename.startswith('res/') and info.file_size in sizes}
        assert {e['sha256'] for e in references} <= resource_hashes, 'Missing/changed printed-number fonts or their OFL licence'
        for name in z.namelist():
            if not name.startswith('lib/') or not name.endswith('.so'):
                continue
            data = z.read(name)
            assert data[:4] == b'\x7fELF', name
            abi = name.split('/')[1]
            if abi in ('arm64-v8a', 'x86_64'):
                assert data[4:6] == b'\x02\x01', name
                offset = struct.unpack_from('<Q', data, 32)[0]
                size, count = struct.unpack_from('<HH', data, 54)
                loads = [struct.unpack_from('<IIQQQQQQ', data, offset + i * size) for i in range(count)]
                assert all(row[7] >= 16384 for row in loads if row[0] == 1), f'Not 16 KB ELF aligned: {name}'
            libraries.append(name)
        for abi in ('arm64-v8a', 'armeabi-v7a', 'x86_64'):
            assert any(n.startswith('lib/' + abi + '/') and n.endswith('/libtesseract.so') for n in libraries), 'Missing OCR ABI ' + abi
    return {'apk': path.name, 'bytes': path.stat().st_size, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
            'zip_crc': 'passed', 'offline_model_assets': 'all hashes verified', 'print_reference_assets': 'all hashes verified', 'native_64_bit_16kb_alignment': 'passed', 'libraries': libraries}


if __name__ == '__main__':
    result = audit(sys.argv[1])
    print(json.dumps(result, indent=2))
    if len(sys.argv) > 2:
        Path(sys.argv[2]).write_text(json.dumps(result, indent=2) + '\n')
