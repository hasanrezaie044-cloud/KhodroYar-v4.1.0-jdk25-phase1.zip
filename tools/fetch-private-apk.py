#!/usr/bin/env python3
"""Recover CI's encrypted Git-object handoff privately; never fetch a public APK.

Supply the manifest blob and exact commit/APK SHA256 from the trusted successful
workflow check. AES/RSA authentication, Git blob IDs, ciphertext/plaintext hashes
and the real APK auditor must all pass. The owner download stays sealed: this
script intentionally does not create ready.json or assert physical-device proof.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

FILE_NAME = 'KhodroYar-5.4.1-release-signed.apk'
CERTIFICATE = '03bfbc3575667403c3259fae345f3eb1ad3045373cb62123be376ef4898caaf0'


def blob(repository, sha, maximum):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository) or not re.fullmatch(r'[0-9a-f]{40}', sha):
        raise ValueError('Invalid GitHub object identifier')
    result = json.loads(subprocess.check_output(['gh', 'api', f'repos/{repository}/git/blobs/{sha}']))
    if result.get('encoding') != 'base64' or not 0 < result['size'] <= maximum:
        raise ValueError('Invalid encrypted Git object size/encoding')
    data = base64.b64decode(result['content'].replace('\n', ''), validate=True)
    if (len(data) != result['size'] or result.get('sha') != sha
            or hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest() != sha):
        raise ValueError('Encrypted Git object was truncated or changed')
    return data


def decrypt(meta, cipher, private_key, expected_head, expected_sha):
    proof = meta['verification']
    if (not re.fullmatch(r'[0-9a-f]{40}', expected_head) or not re.fullmatch(r'[0-9a-f]{64}', expected_sha)
            or proof.get('head_sha') != expected_head or meta.get('sha256') != expected_sha
            or proof.get('sha256') != expected_sha or proof.get('bytes') != meta.get('bytes')
            or not 0 < meta['bytes'] < 100_000_000 or len(cipher) != meta['bytes']
            or meta.get('cipher_bytes') != len(cipher) or hashlib.sha256(cipher).hexdigest() != meta.get('cipher_sha256')
            or proof.get('package') != 'com.khodroyar.app' or proof.get('certificate_sha256') != CERTIFICATE
            or proof.get('debuggable') is not False or proof.get('signed_runtime', {}).get('passed') is not True):
        raise ValueError('APK handoff does not match the pinned successful workflow proof')
    key = private_key.decrypt(base64.b64decode(meta['wrapped_key'], validate=True),
        padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
    nonce, tag = base64.b64decode(meta['nonce'], validate=True), base64.b64decode(meta['tag'], validate=True)
    if len(key) != 32 or len(nonce) != 12 or len(tag) != 16:
        raise ValueError('Invalid authenticated-encryption parameters')
    plain = AESGCM(key).decrypt(nonce, cipher + tag, json.dumps(proof, sort_keys=True, separators=(',', ':')).encode())
    if len(plain) != meta['bytes'] or hashlib.sha256(plain).hexdigest() != expected_sha:
        raise ValueError('Decrypted APK is not the exact audited candidate')
    return plain


def recover(repository, manifest_sha, key_path, directory, expected_head, expected_sha):
    meta = json.loads(blob(repository, manifest_sha, 100000))
    cipher = blob(repository, meta['cipher_git_blob'], 99_999_999)
    key = serialization.load_pem_private_key(Path(key_path).read_bytes(), password=None)
    plain = decrypt(meta, cipher, key, expected_head, expected_sha)
    root = Path(directory)
    root.mkdir(parents=True, exist_ok=True); root.chmod(0o700)
    final = root / FILE_NAME
    if final.exists() and hashlib.sha256(final.read_bytes()).hexdigest() != expected_sha:
        raise ValueError('An owner reset is required before replacing an existing candidate')
    temporary = root / 'verified-recovery.tmp'
    try:
        temporary.write_bytes(plain); temporary.chmod(0o600)
        # This is the actual APK, not a fixture: CRC, source-pinned models and ABI/ELF audit.
        subprocess.run([sys.executable, str(Path(__file__).with_name('audit-ocr-apk.py')), str(temporary)], check=True)
        temporary.replace(final)
        (root / 'received-manifest.json').write_text(json.dumps(meta, indent=2) + '\n')
        (root / 'verification.json').write_text(json.dumps(meta['verification'], indent=2) + '\n')
    finally:
        temporary.unlink(missing_ok=True)
    print(f'Exact signed APK privately recovered and independently audited: {len(plain)} bytes; SHA256 {expected_sha}. Owner download remains sealed until the whole workflow is confirmed.')
    return final


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', required=True)
    parser.add_argument('--manifest', required=True)
    parser.add_argument('--private-key', required=True)
    parser.add_argument('--directory', required=True)
    parser.add_argument('--expected-head', required=True)
    parser.add_argument('--expected-sha256', required=True)
    args = parser.parse_args()
    recover(args.repository, args.manifest, args.private_key, args.directory, args.expected_head, args.expected_sha256)
