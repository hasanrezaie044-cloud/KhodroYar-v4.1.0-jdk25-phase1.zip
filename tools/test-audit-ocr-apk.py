#!/usr/bin/env python3
"""Small ZIP/ELF fixtures, NOT APK/signature or device test evidence."""
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('apk_audit', Path(__file__).with_name('audit-ocr-apk.py'))
auditor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(auditor)


class OnlineOnlyAuditFixtures(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'not-a-real-apk.zip'

    def archive(self, entries):
        with zipfile.ZipFile(self.path, 'w') as archive:
            for resource in auditor.PACKAGED_RESOURCES:
                archive.writestr('res/' + resource.name, resource.read_bytes())
            for name, value in entries.items():
                archive.writestr(name, value)
        return self.path

    def test_absent_engine_is_reported_honestly(self):
        result = auditor.audit(self.archive({'assets/example.txt': b'synthetic ZIP only'}))
        self.assertEqual('online-only', result['ocr_mode'])
        self.assertEqual('absent (online-only)', result['offline_model_assets'])
        self.assertEqual('absent', result['offline_native_engine'])

    def test_packaged_font_and_notices_cannot_be_omitted(self):
        with zipfile.ZipFile(self.path, 'w') as archive:
            archive.writestr('assets/example.txt', b'synthetic fixture')
        with self.assertRaisesRegex(AssertionError, 'packaged resource'):
            auditor.audit(self.path)

    def test_any_traineddata_or_retired_asset_directory_is_rejected(self):
        for name in ('assets/ocr/fas.traineddata', 'assets/ocr/manifest.json', 'assets/hidden/model.traineddata'):
            with self.subTest(name=name), self.assertRaises(AssertionError):
                auditor.audit(self.archive({name: b'forbidden fixture'}))

    def test_old_native_ocr_libraries_are_rejected(self):
        for name in ('lib/arm64-v8a/libtesseract.so', 'lib/x86_64/liblept.so', 'lib/x86/libleptonica.so'):
            with self.subTest(name=name), self.assertRaises(AssertionError):
                auditor.audit(self.archive({name: b'forbidden fixture'}))

    def test_invalid_native_file_is_not_treated_as_aligned(self):
        with self.assertRaises(AssertionError):
            auditor.audit(self.archive({'lib/arm64-v8a/libother.so': b'not ELF'}))

    def test_64_bit_elf_requires_16kb_load_alignment(self):
        def elf(alignment):
            data = bytearray(120)
            data[:6] = b'\x7fELF\x02\x01'
            struct.pack_into('<Q', data, 32, 64)
            struct.pack_into('<HH', data, 54, 56, 1)
            struct.pack_into('<IIQQQQQQ', data, 64, 1, 0, 0, 0, 0, 0, 0, alignment)
            return data
        with self.assertRaises(AssertionError):
            auditor.audit(self.archive({'lib/arm64-v8a/libother.so': elf(4096)}))
        result = auditor.audit(self.archive({'lib/arm64-v8a/libother.so': elf(16384)}))
        self.assertEqual('passed', result['native_64_bit_16kb_alignment'])


if __name__ == '__main__':
    unittest.main()
