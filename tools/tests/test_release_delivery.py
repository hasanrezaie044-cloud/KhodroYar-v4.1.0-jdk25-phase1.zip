import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

spec = importlib.util.spec_from_file_location('delivery', Path(__file__).resolve().parents[1] / 'verify-release-delivery.py')
delivery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(delivery)


def make_apk(manifest=True):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w') as z:
        z.writestr('AndroidManifest.xml' if manifest else 'not-an-apk.txt', b'synthetic Android manifest')
    return stream.getvalue()


def make_archive(apk, name='app-release.apk', duplicate=False):
    stream = io.BytesIO()
    with warnings.catch_warnings():
        warnings.simplefilter('ignore', UserWarning)
        with zipfile.ZipFile(stream, 'w') as z:
            z.writestr(name, apk)
            if duplicate:
                z.writestr(name, apk)
    return stream.getvalue()


class DeliveryVerificationTest(unittest.TestCase):
    def inspect(self, data, apk, archive_hash=None, apk_hash=None):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / 'artifact.zip'
            target = root / 'app.apk'
            source.write_bytes(data)
            with patch.multiple(delivery, ARCHIVE_BYTES=len(data), APK_BYTES=len(apk),
                                ARCHIVE_SHA=archive_hash or hashlib.sha256(data).hexdigest(),
                                APK_SHA=apk_hash or hashlib.sha256(apk).hexdigest()):
                result = delivery.inspect_archive(source, target)
            self.assertEqual(target.read_bytes(), apk)
            return result

    def test_valid_archive_keeps_exact_apk_bytes(self):
        apk = make_apk()
        proof = self.inspect(make_archive(apk), apk)
        self.assertEqual(proof['outerZipCrc'], 'passed')
        self.assertEqual(proof['apkZipCrc'], 'passed')
        self.assertEqual(proof['apkSha256'], hashlib.sha256(apk).hexdigest())

    def test_archive_checksum_mismatch_is_rejected(self):
        apk = make_apk()
        with self.assertRaisesRegex(ValueError, 'Archive SHA256'):
            self.inspect(make_archive(apk), apk, archive_hash='0' * 64)

    def test_truncated_archive_is_rejected_before_reading(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / 'bad.zip'
            source.write_bytes(b'PK')
            with self.assertRaisesRegex(ValueError, 'Archive size'):
                delivery.inspect_archive(source, Path(folder) / 'app.apk')

    def test_apk_hash_mismatch_is_rejected(self):
        apk = make_apk()
        with self.assertRaisesRegex(ValueError, 'APK hash'):
            self.inspect(make_archive(apk), apk, apk_hash='0' * 64)

    def test_traversal_paths_are_rejected(self):
        apk = make_apk()
        for name in ('../app-release.apk', '/app-release.apk', 'dir\\app-release.apk'):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'Unsafe archive'):
                self.inspect(make_archive(apk, name=name), apk)

    def test_duplicate_apks_are_rejected(self):
        apk = make_apk()
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            self.inspect(make_archive(apk, duplicate=True), apk)

    def test_outer_crc_is_checked_even_when_archive_hash_matches(self):
        apk = make_apk()
        data = make_archive(apk)
        index = data.index(apk)
        data = data[:index] + bytes([data[index] ^ 1]) + data[index + 1:]
        with self.assertRaises((ValueError, zipfile.BadZipFile)):
            self.inspect(data, apk)

    def test_inner_apk_crc_is_checked(self):
        apk = make_apk()
        index = apk.index(b'synthetic Android manifest')
        apk = apk[:index] + b'X' + apk[index + 1:]
        with self.assertRaises((ValueError, zipfile.BadZipFile)):
            self.inspect(make_archive(apk), apk)

    def test_non_apk_zip_cannot_be_delivered(self):
        apk = make_apk(manifest=False)
        with self.assertRaisesRegex(ValueError, 'Not an Android APK'):
            self.inspect(make_archive(apk), apk)

    def test_callback_is_restricted_to_one_time_workspace_https_url(self):
        path = '/receive/' + 'a' * 64
        valid = 'https://8765-abc123.e2b.app' + path
        self.assertEqual(delivery.validate_callback(valid), valid)
        for bad in ('http://8765-abc123.e2b.app' + path,
                    'https://example.com' + path,
                    'https://8765-abc123.e2b.app.attacker.com' + path,
                    'https://user:pass@8765-abc123.e2b.app' + path,
                    'https://8765-abc123.e2b.app:443' + path,
                    valid + '?token=anything', valid + '#fragment',
                    'https://8765-abc123.e2b.app/receive/not-a-nonce'):
            with self.subTest(url=bad), self.assertRaises(ValueError):
                delivery.validate_callback(bad)

    def test_callback_never_follows_redirects(self):
        self.assertIsNone(delivery.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://example.com'))


if __name__ == '__main__':
    unittest.main()
