#!/usr/bin/env python3
"""Inspect the WHOLE signed APK, native 16-KB ELF alignment and shipped model."""
import hashlib
import json
from pathlib import Path
import struct
import sys
import zipfile

apk = Path(sys.argv[1])
assert apk.stat().st_size < 100_000_000, 'Whole APK must be below 100,000,000 bytes'
with zipfile.ZipFile(apk) as archive:
    names = set(archive.namelist())
    meta = json.loads(archive.read('assets/albert/manifest.json'))
    for name in ('intent-int8.onnx', 'spiece.model'):
        entry = archive.getinfo('assets/albert/' + name)
        assert entry.compress_type == zipfile.ZIP_STORED, 'Model/tokenizer must be mmap-compatible uncompressed assets'
        data = archive.read(entry)
        assert len(data) == meta['assets'][name]['bytes']
        assert hashlib.sha256(data).hexdigest() == meta['assets'][name]['sha256']
    for abi in ('arm64-v8a', 'armeabi-v7a', 'x86_64'):
        assert f'lib/{abi}/libkhodroyar_tokenizer.so' in names, 'Missing native tokenizer for ' + abi
        assert f'lib/{abi}/libonnxruntime.so' in names, 'Missing CPU runtime for ' + abi
        assert f'lib/{abi}/libonnxruntime4j_jni.so' in names, 'Missing runtime JNI for ' + abi
    checked = []
    for name in sorted(names):
        if not (name.startswith(('lib/arm64-v8a/', 'lib/x86_64/')) and name.endswith('.so')):
            continue
        data = archive.read(name)
        assert data[:6] == b'\x7fELF\x02\x01', 'Expected little-endian ELF64'
        offset = struct.unpack_from('<Q', data, 32)[0]
        size, count = struct.unpack_from('<HH', data, 54)
        for i in range(count):
            header = struct.unpack_from('<IIQQQQQQ', data, offset + i*size)
            if header[0] == 1:
                assert header[7] >= 16384, 'Native ELF is not 16-KB page aligned: ' + name
        checked.append(name)
    for name in ('ALBERT-Apache-2.0.txt', 'KhodroYar-ALBERT-NOTICE.txt', 'ONNX-Runtime-MIT.txt',
                 'ONNX-Runtime-ThirdPartyNotices.txt', 'SentencePiece-LICENSE.txt', 'SentencePiece-protobuf-lite-LICENSE.txt'):
        assert len(archive.read('assets/licenses/' + name)) > 500
    evaluation = json.loads(archive.read('assets/albert/evaluation.json'))
    report = {'apkBytes':apk.stat().st_size,'apkSha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
              'modelBytes':meta['assets']['intent-int8.onnx']['bytes'],
              'modelSha256':meta['assets']['intent-int8.onnx']['sha256'],
              'modelSource':meta['revision'],'native64BitPageAlignedLibraries':len(checked),
              'authoredHoldoutExamples':evaluation['holdoutExamples'],'authoredHoldoutRawCorrect':evaluation['holdoutRawCorrect'],
              'authoredHoldoutAccepted':evaluation['holdoutAccepted'],'authoredHoldoutAcceptedCorrect':evaluation['holdoutAcceptedCorrect']}
    print('::notice title=Bundled ALBERT signed APK verification::'+json.dumps(report,separators=(',',':')))
    Path(sys.argv[2] if len(sys.argv)>2 else str(apk)+'.verification.json').write_text(json.dumps(report,indent=2))
