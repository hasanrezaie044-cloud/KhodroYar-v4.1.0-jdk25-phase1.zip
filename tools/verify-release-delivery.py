#!/usr/bin/env python3
"""Verify the exact delivered 5.5.1 archive; never rebuild, re-sign, or publish a release.

Optional one-time POST callback transfers the verified archive privately to a workspace.
GitHub credentials are used only by gh against GitHub, never sent to the callback.
"""
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import time
import urllib.parse
import urllib.request
import zipfile

REPO = 'hasanrezaie044-cloud/KhodroYar-v4.1.0-jdk25-phase1.zip'
RUN = 43084276600
ARTIFACT = 13885833894
SOURCE = '06bf8750b3b1fb62dbd256dd94152c6d9beff82b'
ARCHIVE_BYTES = 36_778_304
ARCHIVE_SHA = 'a09f42685d075e533b2addd4e257d7911e859d475fe9fdea90724038e58ce7c3'
APK_BYTES = 77_962_115
APK_SHA = '58ef24fd3b8f9e37b3349b7ad75f07b33de685ec5851af8d5332a0c7374c54d7'
CERT_SHA = '03bfbc3575667403c3259fae345f3eb1ad3045373cb62123be376ef4898caaf0'
APK_NAME = 'KhodroYar-5.5.1.apk'
CHUNK_BYTES = 1_048_576


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def inspect_archive(archive, destination):
    """Validate outer and inner CRCs/hashes before writing the exact signed APK."""
    archive, destination = Path(archive), Path(destination)
    require(archive.stat().st_size == ARCHIVE_BYTES, 'Archive size differs from the delivered artifact')
    data = archive.read_bytes()
    require(sha256(data) == ARCHIVE_SHA, 'Archive SHA256 differs from GitHub artifact digest')
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        entries = z.infolist()
        names = [entry.filename for entry in entries]
        require(len(names) == len(set(names)) and len(names) <= 20, 'Unexpected/duplicate archive entries')
        for name in names:
            path = PurePosixPath(name)
            require(not path.is_absolute() and '..' not in path.parts and '\\' not in name, 'Unsafe archive path')
        require(z.testzip() is None, 'Outer ZIP CRC verification failed')
        apks = [entry for entry in entries if not entry.is_dir() and PurePosixPath(entry.filename).name == 'app-release.apk']
        require(len(apks) == 1 and apks[0].file_size == APK_BYTES, 'Missing or unexpected release APK')
        apk = z.read(apks[0])
        methods = sorted({entry.compress_type for entry in entries})
    require(sha256(apk) == APK_SHA and len(apk) < 100_000_000, 'APK hash/size mismatch; do not deliver')
    with zipfile.ZipFile(io.BytesIO(apk)) as inner:
        require(inner.testzip() is None, 'APK ZIP CRC verification failed')
        require('AndroidManifest.xml' in inner.namelist(), 'Not an Android APK')
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix('.verified-part')
    temporary.write_bytes(apk)
    temporary.replace(destination)
    return {
        'producerRun': RUN, 'sourceCommit': SOURCE, 'artifactId': ARTIFACT,
        'archiveBytes': len(data), 'archiveSha256': sha256(data), 'outerZipCrc': 'passed',
        'outerZipCompressionMethods': methods, 'apkBytes': len(apk), 'apkSha256': sha256(apk),
        'apkZipCrc': 'passed', 'apkFilename': APK_NAME,
    }


def gh_json(endpoint):
    result = subprocess.run(['gh', 'api', endpoint], capture_output=True, text=True, timeout=60)
    require(result.returncode == 0, 'GitHub metadata request failed')
    return json.loads(result.stdout)


def download_original(archive):
    for attempt in range(1, 4):
        with archive.open('wb') as output:
            try:
                result = subprocess.run(['gh', 'api', f'repos/{REPO}/actions/artifacts/{ARTIFACT}/zip'],
                                        stdout=output, stderr=subprocess.PIPE, timeout=120)
                ok = result.returncode == 0
            except subprocess.TimeoutExpired:
                ok = False
        if ok:
            return
        print(f'Archive download attempt {attempt} failed; no downloaded bytes have been trusted', flush=True)
        if attempt < 3:
            time.sleep(attempt * 2)
    raise RuntimeError('Unable to retrieve the original artifact; not claiming archive health')


def android_tool(name):
    roots = {Path(os.environ[key]) for key in ('ANDROID_HOME', 'ANDROID_SDK_ROOT') if os.environ.get(key)}
    candidates = sorted(path for root in roots for path in (root / 'build-tools').glob('*/' + name) if path.is_file())
    require(bool(candidates), f'{name} is required to verify the existing APK')
    return str(candidates[-1])


def verify_android(apk):
    signed = subprocess.run([android_tool('apksigner'), 'verify', '--verbose', '--print-certs', str(apk)],
                            capture_output=True, text=True, timeout=120)
    require(signed.returncode == 0, 'APK signature verification failed')
    certificates = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})', signed.stdout)
    require(certificates == [CERT_SHA], 'The signing certificate does not match the installed-release certificate')
    badging = subprocess.run([android_tool('aapt2'), 'dump', 'badging', str(apk)],
                             capture_output=True, text=True, timeout=60)
    require(badging.returncode == 0, 'Cannot inspect Android package/version')
    require("package: name='com.khodroyar.app' versionCode='62' versionName='5.5.1'" in badging.stdout,
            'Wrong Android package/version')
    return {'certificateSha256': CERT_SHA, 'signatureVerification': 'passed',
            'packageName': 'com.khodroyar.app', 'versionName': '5.5.1', 'versionCode': 62}


def validate_callback(url):
    parsed = urllib.parse.urlsplit(url)
    require(parsed.scheme == 'https' and parsed.username is None and parsed.password is None,
            'Callback must use HTTPS without embedded credentials')
    require(parsed.port is None and not parsed.query and not parsed.fragment, 'Invalid callback URL')
    require(re.fullmatch(r'[0-9]+-[a-z0-9]+\.e2b\.app', parsed.hostname or '') is not None,
            'Only the temporary workspace preview may receive this file')
    require(re.fullmatch(r'/receive/[0-9a-f]{64}', parsed.path) is not None, 'Invalid one-time callback path')
    return url.rstrip('/')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def send_privately(archive, callback):
    """Bounded chunks; no token/header from GitHub is ever forwarded."""
    callback = validate_callback(callback)
    opener = urllib.request.build_opener(NoRedirect)
    def post(suffix, data):
        for attempt in range(3):
            request = urllib.request.Request(callback + suffix, data=data, method='POST',
                                             headers={'Content-Type': 'application/octet-stream'})
            try:
                with opener.open(request, timeout=90) as response:
                    result = json.loads(response.read(16_384))
                require(result.get('ok') is True, 'Workspace did not accept the verified transfer')
                return result
            except (OSError, ValueError):
                if attempt == 2:
                    raise RuntimeError('Private transfer failed; no callback credentials or file URLs are logged') from None
                time.sleep(2)
    with archive.open('rb') as source:
        index = 0
        while data := source.read(CHUNK_BYTES):
            post(f'/chunk/{index}', data)
            index += 1
    result = post('/complete', b'')
    require(result.get('archiveSha256') == ARCHIVE_SHA and result.get('apkSha256') == APK_SHA,
            'Private workspace did not verify the exact archive and APK')
    return {'privateWorkspaceTransfer': 'verified', 'publicReleaseCreated': False}


def main():
    require(os.environ.get('GITHUB_REPOSITORY') == REPO, 'This repair is pinned to the original repository')
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    callback = event.get('inputs', {}).get('delivery_callback', '').strip()
    if callback:
        validate_callback(callback)
        print('::add-mask::' + callback, flush=True)
    root = Path(os.environ.get('RUNNER_TEMP', '/tmp')) / 'khodroyar-delivery-verified'
    root.mkdir(parents=True, exist_ok=True)
    run = gh_json(f'repos/{REPO}/actions/runs/{RUN}')
    require(run['head_sha'] == SOURCE and run['conclusion'] == 'success', 'Original build is not verified')
    artifact = gh_json(f'repos/{REPO}/actions/artifacts/{ARTIFACT}')
    require(not artifact['expired'] and artifact['workflow_run']['id'] == RUN, 'Wrong/expired archive')
    require(artifact['digest'] == 'sha256:' + ARCHIVE_SHA and artifact['size_in_bytes'] == ARCHIVE_BYTES,
            'Artifact metadata does not match the delivered build')
    archive = root / 'original-actions-artifact.zip'
    download_original(archive)
    apk = root / APK_NAME
    proof = inspect_archive(archive, apk)
    proof.update(verify_android(apk))
    # Emit archive/signature proof even if the optional workspace transfer fails.
    print('::notice title=Original archive and APK delivery verification::' + json.dumps(proof, separators=(',', ':')), flush=True)
    if callback:
        proof.update(send_privately(archive, callback))
        print('::notice title=Private APK transfer::verified; no public release created', flush=True)
    (root / 'delivery-verification.json').write_text(json.dumps(proof, indent=2))
    archive.unlink()  # Do not put a redundant ZIP inside the replacement artifact.
    print('Verified the original file; APK is unchanged and not re-signed', flush=True)


if __name__ == '__main__':
    main()
