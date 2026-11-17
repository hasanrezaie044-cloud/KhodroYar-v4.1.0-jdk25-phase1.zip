#!/usr/bin/env python3
"""End-to-end encrypted CI-to-owner handoff; NEVER publish an installable APK.

Only a public RSA key is passed to CI. A fresh AES-256-GCM key is wrapped with
RSA-OAEP/SHA-256. The private key remains owner-local. Transport is either an HTTPS
ciphertext receiver or two unreferenced Git objects containing ciphertext and its
public manifest (no branch, tag, Release or plaintext APK artifact is created).
Git-object transport is useful when sandbox previews require platform traffic
credentials that must NOT be exposed to a public workflow. Objects cannot be
installed/decrypted without the owner's private RSA key.
"""
import argparse
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

CHUNK = 512 * 1024
ORIGINAL_CERT = '03bfbc3575667403c3259fae345f3eb1ad3045373cb62123be376ef4898caaf0'


def request(base, suffix, data=None, headers=None, method=None):
    for attempt in range(4):
        try:
            req = urllib.request.Request(base + suffix, data=data, headers=headers or {}, method=method)
            return urllib.request.urlopen(req, timeout=90)
        except (urllib.error.URLError, TimeoutError, OSError):
            if attempt == 3:
                raise RuntimeError(f'Private transfer failed at {suffix.split("/")[1]} (no capability is logged)') from None
            time.sleep(2 ** attempt)


def encrypt_payload(apk, verification, public_pem, temporary):
    public_key = serialization.load_pem_public_key(public_pem.encode())
    if not isinstance(public_key, rsa.RSAPublicKey) or public_key.key_size < 3072:
        raise ValueError('A minimum 3072-bit RSA public delivery key is required')
    apk = Path(apk)
    size = apk.stat().st_size
    if not 0 < size < 100_000_000:
        raise ValueError('APK exceeds product size limit')
    sha = hashlib.sha256(apk.read_bytes()).hexdigest()
    proof = json.loads(Path(verification).read_text())
    if proof.get('bytes') != size or proof.get('sha256') != sha:
        raise ValueError('APK and verification proof do not match')
    if (proof.get('certificate_sha256') != ORIGINAL_CERT or proof.get('package') != 'com.khodroyar.app'
            or proof.get('debuggable') is not False or proof.get('signed_runtime', {}).get('passed') is not True
            or not re.fullmatch(r'[0-9a-f]{40}', proof.get('head_sha', ''))):
        raise ValueError('Original signing, non-debuggable release and real signed runtime proof are required')
    key, nonce = secrets.token_bytes(32), secrets.token_bytes(12)
    wrapped = public_key.encrypt(key, padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
    encryptor = Cipher(algorithms.AES(key), modes.GCM(nonce)).encryptor()
    encryptor.authenticate_additional_data(json.dumps(proof, sort_keys=True, separators=(',', ':')).encode())
    cipher = Path(temporary) / 'apk.ciphertext'
    digest = hashlib.sha256()
    with apk.open('rb') as source, cipher.open('wb') as target:
        for chunk in iter(lambda: source.read(CHUNK), b''):
            data = encryptor.update(chunk)
            target.write(data); digest.update(data)
        last = encryptor.finalize()
        target.write(last); digest.update(last)
    meta = dict(bytes=size, sha256=sha, chunk_bytes=CHUNK, verification=proof, cipher_bytes=cipher.stat().st_size,
                cipher_sha256=digest.hexdigest(), wrapped_key=base64.b64encode(wrapped).decode(),
                nonce=base64.b64encode(nonce).decode(), tag=base64.b64encode(encryptor.tag).decode())
    return meta, cipher, key


def send_https(meta, cipher, key, base):
    url = urllib.parse.urlsplit(base)
    if url.scheme != 'https' or not url.hostname or url.username or url.password or url.query or url.fragment:
        raise ValueError('An HTTPS ciphertext receiver endpoint is required')
    sha, size = meta['sha256'], meta['bytes']
    headers = {'Content-Type': 'application/json', 'X-File-SHA256': sha}
    with request(base, '/manifest', json.dumps(meta).encode(), headers) as response:
        if not json.load(response).get('ok'):
            raise RuntimeError('Receiver rejected encrypted manifest')
    with cipher.open('rb') as stream:
        for index, chunk in enumerate(iter(lambda: stream.read(CHUNK), b'')):
            h = {'Content-Type': 'application/octet-stream', 'X-File-SHA256': sha,
                 'X-Chunk-SHA256': hashlib.sha256(chunk).hexdigest()}
            with request(base, f'/part/{index}', chunk, h) as response:
                if not json.load(response).get('ok'):
                    raise RuntimeError('Receiver rejected encrypted chunk')
    with request(base, '/complete', b'{}', headers) as response:
        receipt = json.load(response)
    if receipt.get('sha256') != sha or receipt.get('bytes') != size:
        raise RuntimeError('Private decryption/readback does not match the APK')
    token = hmac.new(key, b'khodroyar-ci-download:' + sha.encode(), hashlib.sha256).hexdigest()
    downloaded = hashlib.sha256()
    downloaded_size = 0
    with request(base, '/verify/' + token, method='GET') as response:
        for chunk in iter(lambda: response.read(CHUNK), b''):
            downloaded.update(chunk); downloaded_size += len(chunk)
    if downloaded_size != size or downloaded.hexdigest() != sha:
        raise RuntimeError('Private HTTPS APK download is corrupt or incomplete')
    print(f'::notice title=Private APK delivery::Encrypted upload and full private HTTPS readback verified; {size} bytes; SHA256 {sha}. No public APK upload.')


def git_blob(repository, data, temporary):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ValueError('Invalid repository')
    payload = Path(temporary) / 'encrypted-object-request.json'
    payload.write_text(json.dumps(dict(encoding='base64', content=base64.b64encode(data).decode())))
    for attempt in range(3):
        try:
            response = json.loads(subprocess.check_output(['gh', 'api', '--method', 'POST', f'repos/{repository}/git/blobs', '--input', str(payload)], timeout=180))
            break
        except subprocess.SubprocessError:
            if attempt == 2:
                raise RuntimeError('Encrypted Git-object transfer failed; no plaintext APK was published') from None
            time.sleep(2 ** attempt)
    expected = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
    if response.get('sha') != expected:
        raise RuntimeError('GitHub did not acknowledge the exact encrypted object')
    payload.unlink()
    return response['sha']


def send_git_objects(meta, cipher, repository, temporary):
    # No ref update, commit, branch, tag or public installable artifact. Only
    # authenticated ciphertext is in these objects; no private RSA/AES key is sent.
    ciphertext_blob = git_blob(repository, cipher.read_bytes(), temporary)
    manifest = meta | dict(cipher_git_blob=ciphertext_blob)
    manifest_blob = git_blob(repository, json.dumps(manifest, sort_keys=True, indent=2).encode(), temporary)
    receipt = dict(manifest_blob=manifest_blob, cipher_blob=ciphertext_blob, head_sha=meta['verification']['head_sha'],
                   bytes=meta['bytes'], sha256=meta['sha256'])
    print('::notice title=Encrypted owner-only APK handoff::' + json.dumps(receipt, sort_keys=True))
    print('Only encrypted, unreferenced Git objects were transferred. No plaintext APK artifact or Release was published. Owner-local RSA key is required to recover the APK.')


def send(apk, verification, base, public_pem, repository=''):
    with tempfile.TemporaryDirectory(prefix='khodroyar-encrypted-delivery-') as temporary:
        meta, cipher, key = encrypt_payload(apk, verification, public_pem, temporary)
        if base:
            send_https(meta, cipher, key, base)
        elif repository:
            send_git_objects(meta, cipher, repository, temporary)
        else:
            raise ValueError('A ciphertext receiver or encrypted Git-object handoff repository is required')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk')
    parser.add_argument('verification')
    args = parser.parse_args()
    try:
        send(args.apk, args.verification, os.environ.get('KHODROYAR_ENCRYPTED_APK_UPLOAD_URL', '').rstrip('/'),
             os.environ.get('KHODROYAR_DELIVERY_PUBLIC_KEY', ''), os.environ.get('GITHUB_REPOSITORY', ''))
    except (ValueError, RuntimeError, OSError, KeyError, subprocess.SubprocessError, json.JSONDecodeError) as error:
        print(f'::error::Private APK handoff did not finish: {error}', file=sys.stderr)
        sys.exit(1)
