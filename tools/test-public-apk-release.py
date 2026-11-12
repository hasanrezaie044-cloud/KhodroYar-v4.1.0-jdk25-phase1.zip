#!/usr/bin/env python3
"""Publication guard/HTTP fixtures only; never substitutes for real APK/device tests."""
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('publication', Path(__file__).with_name('public-apk-release.py'))
publication = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publication)
HEAD = '4' * 40


class PublicationFixtures(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / 'KhodroYar-5.4.1-release-signed.apk'
        self.apk.write_bytes(b'NOT AN APK: metadata and HTTP fixture only')
        self.path = self.root / 'verification.json'
        self.proof = dict(bytes=self.apk.stat().st_size, sha256=publication.digest(self.apk), head_sha=HEAD,
            package='com.khodroyar.app', certificate_sha256=publication.CERTIFICATE, debuggable=False,
            baseline=publication.BASELINE, room_version=6, version_name='5.4.1', version_code=63,
            zipalign_16kb='passed', zip_crc='passed', offline_model_assets='all hashes verified',
            native_64_bit_16kb_alignment='passed', signed_runtime=dict(passed=True, target_debuggable=False,
                target_package='com.khodroyar.app', instrumentation_package='com.khodroyar.app.releasesmoke',
                target_uid=10209, instrumentation_uid=10210,
                extracted=dict(date='1403/01/01', count='2', km='120.5', hours='3.5'),
                offline=dict(before=publication.OFFLINE.copy(), after=publication.OFFLINE.copy())))
        self.save()

    def save(self):
        self.path.write_text(json.dumps(self.proof))

    def verify(self):
        self.save()
        return publication.verified_proof(self.apk, self.path, HEAD)

    def test_metadata_fixture_is_accepted_but_is_not_an_apk_audit(self):
        self.assertEqual(self.verify()['sha256'], publication.digest(self.apk))

    def test_changed_apk_bytes_are_rejected(self):
        self.apk.write_bytes(b'different fixture bytes')
        with self.assertRaises(ValueError): self.verify()

    def test_wrong_commit_is_rejected(self):
        with self.assertRaises(ValueError): publication.verified_proof(self.apk, self.path, '5' * 40)

    def test_debug_wrong_identity_or_package_are_rejected(self):
        for key, value in [('debuggable', True), ('certificate_sha256', '0' * 64), ('package', 'wrong.app')]:
            old = self.proof[key]
            with self.subTest(key=key):
                self.proof[key] = value
                with self.assertRaises(ValueError): self.verify()
                self.proof[key] = old

    def test_fixed_form_runtime_is_required_for_the_service_comparison_update(self):
        self.proof.update(version_name='5.4.2', version_code=64)
        with self.assertRaises(ValueError):
            self.verify()
        self.proof['signed_runtime']['fixed_company'] = copy.deepcopy(publication.FIXED_COMPANY)
        self.assertEqual(self.verify()['version_code'], 64)
        self.proof['signed_runtime']['fixed_company']['count_per_row'] = '7'
        with self.assertRaises(ValueError):
            self.verify()

    def test_missing_or_failed_actual_runtime_is_rejected(self):
        for runtime in [{}, dict(passed=False)]:
            with self.subTest(runtime=runtime):
                self.proof['signed_runtime'] = runtime
                with self.assertRaises(ValueError): self.verify()

    def test_shared_target_and_instrumentation_uid_are_rejected(self):
        self.proof['signed_runtime']['instrumentation_uid'] = 10209
        with self.assertRaises(ValueError): self.verify()

    def test_both_before_and_after_must_prove_actual_offline_state(self):
        original = copy.deepcopy(self.proof['signed_runtime']['offline'])
        for phase in ('before', 'after'):
            for key, value in [('wifi_on', '1'), ('airplane_mode', '0'), ('active_network', True)]:
                self.proof['signed_runtime']['offline'] = copy.deepcopy(original)
                self.proof['signed_runtime']['offline'][phase][key] = value
                with self.subTest(phase=phase, key=key):
                    with self.assertRaises(ValueError): self.verify()

    def test_wrong_extracted_value_is_rejected(self):
        self.proof['signed_runtime']['extracted']['km'] = '0'
        with self.assertRaises(ValueError): self.verify()

    def test_unsafe_version_is_rejected(self):
        self.proof['version_name'] = '../other'
        with self.assertRaises(ValueError): self.verify()

    def test_size_and_auditor_evidence_are_required(self):
        for key, value in [('bytes', 0), ('zip_crc', 'failed'), ('offline_model_assets', 'missing')]:
            old = self.proof[key]
            with self.subTest(key=key):
                self.proof[key] = value
                with self.assertRaises(ValueError): self.verify()
                self.proof[key] = old

    def test_oversized_proof_is_rejected(self):
        self.path.write_text(' ' * 131073)
        with self.assertRaises(ValueError): publication.verified_proof(self.apk, self.path, HEAD)

    def test_http_fixture_reads_all_bytes_without_authentication(self):
        data = self.apk.read_bytes()
        response = io.BytesIO(data)
        response.status = 200
        with patch.object(publication.urllib.request, 'urlopen', return_value=response) as request:
            publication.anonymous_readback('https://github.com/example/repo/releases/download/v5.4.1/app.apk', len(data), hashlib.sha256(data).hexdigest())
        headers = {k.lower() for k in request.call_args.args[0].headers}
        self.assertNotIn('authorization', headers)
        self.assertNotIn('e2b-traffic-access-token', headers)

    def test_corrupt_http_fixture_is_rejected(self):
        response = io.BytesIO(b'corrupt')
        response.status = 200
        with patch.object(publication.urllib.request, 'urlopen', return_value=response):
            with self.assertRaises(ValueError): publication.anonymous_readback('https://github.com/example/repo/a.apk', 100, '0' * 64)

    def test_existing_release_is_never_overwritten(self):
        with patch.object(publication, 'audit_actual_apk'), patch.object(publication, 'gh_json', return_value=dict(id=1)), patch.object(publication.subprocess, 'run') as command:
            with self.assertRaisesRegex(ValueError, 'already exists'):
                publication.publish(self.root, 'example/repo', HEAD, '123')
            command.assert_not_called()

    def test_publication_fixture_pins_commit_and_verifies_anonymous_asset(self):
        (self.root / 'SHA256SUMS').write_text('fixture checksums')
        (self.root / 'test-summary.md').write_text('Fixture only, not an APK test')
        url = 'https://github.com/example/repo/releases/download/v5.4.1/' + self.apk.name
        draft = dict(id=9, target_commitish=HEAD, draft=True, tag_name='v5.4.1',
            assets=[dict(name=self.apk.name, size=self.apk.stat().st_size, browser_download_url=url)],
            html_url='https://github.com/example/repo/releases/tag/v5.4.1')
        published = dict(draft, draft=False)
        replies = [None, None, draft, published, dict(object=dict(sha=HEAD))]
        with patch.object(publication, 'audit_actual_apk'), patch.object(publication, 'gh_json', side_effect=replies), patch.object(publication.subprocess, 'run') as command, patch.object(publication, 'anonymous_readback') as readback, patch.dict(publication.os.environ, {'GITHUB_STEP_SUMMARY': ''}), patch('builtins.print'):
            receipt = publication.publish(self.root, 'example/repo', HEAD, '123')
        self.assertIn('--draft', command.call_args.args[0])
        self.assertEqual(command.call_args.args[0][command.call_args.args[0].index('--target') + 1], HEAD)
        readback.assert_called_once_with(url, self.proof['bytes'], self.proof['sha256'])
        self.assertEqual(receipt['download_url'], url)
        self.assertEqual(receipt['head_sha'], HEAD)


if __name__ == '__main__':
    unittest.main()
