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
        self.apk = self.root / 'KhodroYar-5.5.8-release-signed.apk'
        self.apk.write_bytes(b'NOT AN APK: metadata and HTTP fixture only')
        self.path = self.root / 'verification.json'
        self.proof = dict(bytes=self.apk.stat().st_size, sha256=publication.digest(self.apk), head_sha=HEAD,
            package='com.khodroyar.app', certificate_sha256=publication.CERTIFICATE, debuggable=False,
            baseline=publication.BASELINE, room_version=6, version_name='5.5.8', version_code=75,
            zipalign_16kb='passed', zip_crc='passed', ocr_mode='online-only', offline_model_assets='absent (online-only)', offline_native_engine='absent',
            bundled_notices_fonts='all hashes verified', native_64_bit_16kb_alignment='passed', signed_runtime=dict(passed=True, target_debuggable=False,
                target_package='com.khodroyar.app', instrumentation_package='com.khodroyar.app.releasesmoke',
                target_uid=10209, instrumentation_uid=10210,
                ocr_mode='online-only', live_ocr_exercised=False, review=copy.deepcopy(publication.REVIEW), bazaar=copy.deepcopy(publication.BAZAAR),
                offline_review=dict(before=publication.OFFLINE_REVIEW.copy(), after=publication.OFFLINE_REVIEW.copy())))
        self.save()

    def save(self):
        self.path.write_text(json.dumps(self.proof))

    def verify(self):
        self.save()
        return publication.verified_proof(self.apk, self.path, HEAD)

    def test_metadata_fixture_is_accepted_but_is_not_an_apk_audit(self):
        self.assertEqual(self.verify()['sha256'], publication.digest(self.apk))

    def test_current_delivery_notes_never_reintroduce_automatic_evaluations_or_paid_fallback(self):
        self.proof.update(version_code=78, version_name='5.6.2')
        self.save()
        output = self.root / 'prepared-current'
        with patch.object(publication, 'audit_actual_apk'):
            publication.prepare(self.apk, self.path, output, HEAD)
        notes = (output / 'test-summary.md').read_text()
        self.assertIn('گفتگوی عادی هرگز ارزیابی مدل ندارد', notes)
        self.assertIn('بررسی اتصال و پس‌زمینه فقط فراداده', notes)
        self.assertIn('بدون retry/fallback', notes)
        self.assertIn('سیاست۵', notes)
        self.assertNotIn('جایگزینی خودکار حداکثر یک بار', notes)
        self.assertTrue((output / 'KhodroYar-5.6.2-release-signed.apk').is_file())

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

    def test_bazaar_ipc_entry_prompt_and_explicit_deep_link_are_required(self):
        original = copy.deepcopy(self.proof['signed_runtime']['bazaar'])
        for key, value in [('passed', False), ('automatic_entry_prompt', False), ('method', 'getVersionCode'),
                ('queried_package', 'wrong.package'), ('update_uri', 'https://elsewhere.example/app.apk')]:
            self.proof['signed_runtime']['bazaar'] = dict(original, **{key: value})
            with self.subTest(key=key):
                with self.assertRaises(ValueError): self.verify()
        self.proof['signed_runtime']['bazaar'] = original
        self.assertEqual(self.verify()['version_code'], 75)

    def test_previous_release_proof_cannot_be_relabelled_as_the_traffic_fix(self):
        self.proof['version_code'] = 74
        with self.assertRaises(ValueError): self.verify()

    def test_live_or_offline_ocr_claim_cannot_replace_actual_text_review_evidence(self):
        original = self.proof['signed_runtime'].copy()
        for key, value in [('live_ocr_exercised', True), ('ocr_mode', 'offline'), ('review', {})]:
            self.proof['signed_runtime'] = dict(original, **{key: value})
            with self.subTest(key=key):
                with self.assertRaises(ValueError): self.verify()

    def test_missing_or_failed_actual_runtime_is_rejected(self):
        for runtime in [{}, dict(passed=False)]:
            with self.subTest(runtime=runtime):
                self.proof['signed_runtime'] = runtime
                with self.assertRaises(ValueError): self.verify()

    def test_shared_target_and_instrumentation_uid_are_rejected(self):
        self.proof['signed_runtime']['instrumentation_uid'] = 10209
        with self.assertRaises(ValueError): self.verify()

    def test_local_text_review_is_proven_separately_from_online_image_ocr(self):
        original = copy.deepcopy(self.proof['signed_runtime']['offline_review'])
        for phase in ('before', 'after'):
            for key, value in [('wifi_on', '1'), ('airplane_mode', '0'), ('active_network', True)]:
                self.proof['signed_runtime']['offline_review'] = copy.deepcopy(original)
                self.proof['signed_runtime']['offline_review'][phase][key] = value
                with self.subTest(phase=phase, key=key):
                    with self.assertRaises(ValueError): self.verify()

    def test_wrong_reviewed_date_count_or_unknown_hour_policy_is_rejected(self):
        self.proof['signed_runtime']['review']['first']['km'] = '0'
        with self.assertRaises(ValueError): self.verify()

    def test_primary_count_first_no_metric_gate_and_explicit_upload_cancellation_are_required(self):
        for key, bad in [('count_review_required', True), ('metrics_blank', False), ('reports_entry', False),
                ('main_metric_editors_absent', False), ('correction_reverted', False), ('consent_cancelled', False)]:
            original = self.proof['signed_runtime']['review'][key]
            self.proof['signed_runtime']['review'][key] = bad
            with self.subTest(key=key):
                with self.assertRaises(ValueError): self.verify()
            self.proof['signed_runtime']['review'][key] = original

    def test_opposite_difference_or_manufactured_app_equality_is_rejected(self):
        for key, bad in [('company', 3), ('app', 2), ('difference', -1)]:
            original = self.proof['signed_runtime']['review']['example'][key]
            self.proof['signed_runtime']['review']['example'][key] = bad
            with self.subTest(key=key):
                with self.assertRaises(ValueError): self.verify()
            self.proof['signed_runtime']['review']['example'][key] = original

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
            publication.anonymous_readback('https://github.com/example/repo/releases/download/v5.5.8/app.apk', len(data), hashlib.sha256(data).hexdigest())
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
        url = 'https://github.com/example/repo/releases/download/v5.5.8/' + self.apk.name
        draft = dict(id=9, target_commitish=HEAD, draft=True, tag_name='v5.5.8',
            assets=[dict(name=self.apk.name, size=self.apk.stat().st_size, browser_download_url=url)],
            html_url='https://github.com/example/repo/releases/tag/v5.5.8')
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
