#!/usr/bin/env python3
"""End-to-end encrypted CI-to-owner APK delivery, never a public APK artifact.

Only a PUBLIC RSA key and a ciphertext receiver URL are workflow inputs. A fresh
AES-256-GCM key is wrapped with RSA-OAEP/SHA-256. The private RSA key and independent
user download capability stay in the owner's workspace, never in GitHub. CI's
one-use plaintext readback capability is HMAC-derived from the unlogged AES key.
These are development tools, not Android runtime dependencies or permissions.
"""
import argparse
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
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


def request(base, suffix, data=None, headers=None, method=None):
    for attempt in range(4):
        try:
            req = urllib.request.Request(base + suffix, data=data, headers=headers or {}, method=method)
            return urllib.request.urlopen(req, timeout=90)
        except (urllib.error.URLError, TimeoutError, OSError):
            if attempt == 3:
                raise RuntimeError(f"Private transfer failed at {suffix.split('/')[1]} (no capability is logged)") from None
            time.sleep(2 ** attempt)


def send(apk, verification, base, public_pem):
    url = urllib.parse.urlsplit(base)
    if url.scheme != "https" or not url.hostname or url.username or url.password or url.query or url.fragment:
        raise ValueError("An HTTPS ciphertext receiver endpoint is required")
    public_key = serialization.load_pem_public_key(public_pem.encode())
    if not isinstance(public_key, rsa.RSAPublicKey) or public_key.key_size < 3072:
        raise ValueError("A minimum 3072-bit RSA public delivery key is required")
    apk = Path(apk)
    size = apk.stat().st_size
    if not 0 < size < 100_000_000:
        raise ValueError("APK exceeds product size limit")
    sha = hashlib.sha256(apk.read_bytes()).hexdigest()
    proof = json.loads(Path(verification).read_text())
    if proof.get("bytes") != size or proof.get("sha256") != sha:
        raise ValueError("APK and verification proof do not match")
    if proof.get("certificate_sha256") != "03bfbc3575667403c3259fae345f3eb1ad3045373cb62123be376ef4898caaf0":
        raise ValueError("Original release certificate was not verified")
    key, nonce = secrets.token_bytes(32), secrets.token_bytes(12)
    wrapped = public_key.encrypt(key, padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
    encryptor = Cipher(algorithms.AES(key), modes.GCM(nonce)).encryptor()
    encryptor.authenticate_additional_data(json.dumps(proof, sort_keys=True, separators=(",", ":")).encode())
    with tempfile.TemporaryDirectory(prefix="khodroyar-encrypted-delivery-") as temporary:
        cipher = Path(temporary) / "apk.ciphertext"
        digest = hashlib.sha256()
        with apk.open("rb") as source, cipher.open("wb") as target:
            for chunk in iter(lambda: source.read(CHUNK), b""):
                data = encryptor.update(chunk)
                target.write(data); digest.update(data)
            last = encryptor.finalize()
            target.write(last); digest.update(last)
        meta = {"bytes": size, "sha256": sha, "chunk_bytes": CHUNK, "verification": proof,
                "cipher_bytes": cipher.stat().st_size, "cipher_sha256": digest.hexdigest(),
                "wrapped_key": base64.b64encode(wrapped).decode(), "nonce": base64.b64encode(nonce).decode(),
                "tag": base64.b64encode(encryptor.tag).decode()}
        headers = {"Content-Type": "application/json", "X-File-SHA256": sha}
        with request(base, "/manifest", json.dumps(meta).encode(), headers) as response:
            if not json.load(response).get("ok"):
                raise RuntimeError("Receiver rejected encrypted manifest")
        with cipher.open("rb") as stream:
            for index, chunk in enumerate(iter(lambda: stream.read(CHUNK), b"")):
                h = {"Content-Type": "application/octet-stream", "X-File-SHA256": sha,
                     "X-Chunk-SHA256": hashlib.sha256(chunk).hexdigest()}
                with request(base, f"/part/{index}", chunk, h) as response:
                    if not json.load(response).get("ok"):
                        raise RuntimeError("Receiver rejected encrypted chunk")
        with request(base, "/complete", b"{}", headers) as response:
            receipt = json.load(response)
    if receipt.get("sha256") != sha or receipt.get("bytes") != size:
        raise RuntimeError("Private decryption/readback does not match the APK")
    # Unlogged, private CI capability. Knowing the PUBLIC upload URL/key cannot derive this.
    token = hmac.new(key, b"khodroyar-ci-download:" + sha.encode(), hashlib.sha256).hexdigest()
    downloaded = hashlib.sha256()
    downloaded_size = 0
    with request(base, "/verify/" + token, method="GET") as response:
        for chunk in iter(lambda: response.read(CHUNK), b""):
            downloaded.update(chunk); downloaded_size += len(chunk)
    if downloaded_size != size or downloaded.hexdigest() != sha:
        raise RuntimeError("Private HTTPS APK download is corrupt or incomplete")
    print(f"::notice title=Private APK delivery::End-to-end encrypted upload AND full private HTTPS APK download verified; {size} bytes; SHA256 {sha}. No public APK upload.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk")
    parser.add_argument("verification")
    args = parser.parse_args()
    try:
        send(args.apk, args.verification, os.environ.get("KHODROYAR_ENCRYPTED_APK_UPLOAD_URL", "").rstrip("/"),
             os.environ.get("KHODROYAR_DELIVERY_PUBLIC_KEY", ""))
    except (ValueError, RuntimeError, OSError, KeyError, json.JSONDecodeError) as error:
        print(f"::error::Private APK delivery did not finish: {error}", file=sys.stderr)
        sys.exit(1)
