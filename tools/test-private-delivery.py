#!/usr/bin/env python3
"""Transport/security unit tests only. These do NOT substitute for APK/native tests."""
import base64
import hashlib
import hmac
from http.server import ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
import secrets
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

spec = importlib.util.spec_from_file_location("receiver", Path(__file__).with_name("receive-private-apk.py"))
receiver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(receiver)

for name, filename in [('sender', 'send-private-apk.py'), ('fetcher', 'fetch-private-apk.py')]:
    module_spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(module_spec)
    module_spec.loader.exec_module(module)
    globals()[name] = module


class PrivateTransferTest(unittest.TestCase):
    def test_range_and_resume_are_bounded(self):
        for value, expected in [(None, (0, 99, False)), ("bytes=0-9", (0, 9, True)),
                                ("bytes=90-", (90, 99, True)), ("bytes=-10", (90, 99, True)),
                                ("bytes=0-1000", (0, 99, True))]:
            self.assertEqual(expected, receiver.byte_range(value, 100))
        for value in ["bytes=-", "bytes=100-", "bytes=-0", "bytes=1-0", "bytes=0-1,4-5", "bad"]:
            with self.assertRaises(ValueError):
                receiver.byte_range(value, 100)

    def test_git_object_encryption_requires_private_key_exact_hash_commit_and_real_runtime_proof(self):
        # Cryptographic unit fixture, NOT an APK/build/signature verification result.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            private = rsa.generate_private_key(public_exponent=65537, key_size=3072)
            public = private.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()
            plain = b'Explicit transport-only non-APK fixture.' * 100
            sha = hashlib.sha256(plain).hexdigest()
            fixture = root / 'fixture.txt'; fixture.write_bytes(plain)
            proof = dict(bytes=len(plain), sha256=sha, head_sha='b' * 40, package='com.khodroyar.app', certificate_sha256=receiver.ORIGINAL_CERT,
                         debuggable=False, signed_runtime=dict(passed=True))
            path = root / 'fixture-proof.json'; path.write_text(json.dumps(proof))
            meta, cipher, key = sender.encrypt_payload(fixture, path, public, root)
            self.assertEqual(plain, fetcher.decrypt(meta, cipher.read_bytes(), private, 'b' * 40, sha))
            with self.assertRaises(ValueError):
                fetcher.decrypt(meta, cipher.read_bytes(), private, 'c' * 40, sha)
            with self.assertRaises(ValueError):
                fetcher.decrypt(meta, cipher.read_bytes()[:-1], private, 'b' * 40, sha)
            wrong_key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
            with self.assertRaises(ValueError):
                fetcher.decrypt(meta, cipher.read_bytes(), wrong_key, 'b' * 40, sha)
            from cryptography.exceptions import InvalidTag
            with self.assertRaises(InvalidTag):
                fetcher.decrypt(meta | dict(verification=proof | dict(signed_runtime=dict(passed=True, changed=True))), cipher.read_bytes(), private, 'b' * 40, sha)
            with self.assertRaises(InvalidTag):
                fetcher.decrypt(meta | dict(tag=base64.b64encode(bytes(16)).decode()), cipher.read_bytes(), private, 'b' * 40, sha)
            data = cipher.read_bytes()
            object_sha = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
            with patch.object(sender.subprocess, 'check_output', return_value=json.dumps(dict(sha=object_sha)).encode()) as github:
                self.assertEqual(object_sha, sender.git_blob('owner/repository', data, root))
                self.assertIn('repos/owner/repository/git/blobs', github.call_args.args[0])
            path.write_text(json.dumps(proof | dict(signed_runtime=dict(passed=False))))
            with self.assertRaises(ValueError):
                sender.encrypt_payload(fixture, path, public, root)

    def test_encrypted_upload_cannot_publish_or_disclose_plaintext_without_private_capability(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            private = rsa.generate_private_key(public_exponent=65537, key_size=3072)
            key_file = root / "key.pem"
            key_file.write_bytes(private.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
            upload, download = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
            config = root / "config.json"
            config.write_text(json.dumps(dict(upload_token=upload, download_token=download, expires_at=time.time() + 300, rsa_private_key=str(key_file))))
            delivery = receiver.PrivateDelivery(root / "files", config)
            server = ThreadingHTTPServer(("127.0.0.1", 0), receiver.Handler)
            server.delivery = delivery
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            base = f"http://127.0.0.1:{server.server_port}"
            def request(suffix, data=None, headers=None):
                return urllib.request.urlopen(urllib.request.Request(base + suffix, data=data, headers=headers or {}), timeout=10)
            plain = b"Transport-only fixture. NOT AN APK. " * 20000
            sha = hashlib.sha256(plain).hexdigest()
            proof = dict(bytes=len(plain), sha256=sha, certificate_sha256=receiver.ORIGINAL_CERT, package="com.khodroyar.app", head_sha="a" * 40)
            key, nonce = secrets.token_bytes(32), secrets.token_bytes(12)
            encrypted = AESGCM(key).encrypt(nonce, plain, json.dumps(proof, sort_keys=True, separators=(",", ":")).encode())
            cipher, tag = encrypted[:-16], encrypted[-16:]
            wrapped = private.public_key().encrypt(key, padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
            meta = dict(bytes=len(plain), sha256=sha, chunk_bytes=512 * 1024, cipher_bytes=len(cipher),
                        cipher_sha256=hashlib.sha256(cipher).hexdigest(), verification=proof,
                        wrapped_key=base64.b64encode(wrapped).decode(), nonce=base64.b64encode(nonce).decode(), tag=base64.b64encode(tag).decode())
            endpoint = "/upload/" + upload
            headers = {"X-File-SHA256": sha}
            try:
                with request(endpoint + "/manifest", json.dumps(meta).encode(), headers) as response:
                    self.assertTrue(json.load(response)["ok"])
                for index, offset in enumerate(range(0, len(cipher), meta["chunk_bytes"])):
                    chunk = cipher[offset:offset + meta["chunk_bytes"]]
                    with request(endpoint + f"/part/{index}", chunk, headers | {"X-Chunk-SHA256": hashlib.sha256(chunk).hexdigest()}) as response:
                        self.assertTrue(json.load(response)["ok"])
                # Explicit transport-only mock. Production always runs the independent APK auditor.
                with patch.object(receiver.subprocess, "run") as auditor, patch("builtins.print"):
                    with request(endpoint + "/complete", b"{}", headers) as response:
                        self.assertEqual(sha, json.load(response)["sha256"])
                    auditor.assert_called_once()
                with request(endpoint + "/complete", b"{}", headers) as response:
                    self.assertEqual(sha, json.load(response)["sha256"])
                for route in [endpoint + "/verify", endpoint + "/verify/" + "0" * 64,
                              "/download/" + download + "/" + receiver.FILE_NAME, "/files/" + receiver.FILE_NAME]:
                    with self.assertRaises(urllib.error.HTTPError) as error:
                        request(route)
                    self.assertEqual(404, error.exception.code)
                # Public-key encryption is not sender authentication. A caller who
                # knows the public endpoint must NOT replace a completed manifest
                # with a new wrapped key and mint a readback token for existing bytes.
                attacker_key = secrets.token_bytes(32)
                attacker_wrapped = private.public_key().encrypt(attacker_key, padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
                replacement = meta | {"wrapped_key": base64.b64encode(attacker_wrapped).decode()}
                with self.assertRaises(urllib.error.HTTPError) as error:
                    request(endpoint + "/manifest", json.dumps(replacement).encode(), headers)
                self.assertEqual(400, error.exception.code)
                self.assertEqual(meta, delivery.manifest)
                attacker_token = hmac.new(attacker_key, b"khodroyar-ci-download:" + sha.encode(), hashlib.sha256).hexdigest()
                with self.assertRaises(urllib.error.HTTPError) as error:
                    request(endpoint + "/verify/" + attacker_token)
                self.assertEqual(404, error.exception.code)
                reloaded = receiver.PrivateDelivery(delivery.root, config)
                self.assertEqual(meta, reloaded.manifest)
                self.assertFalse(reloaded.can_verify("verify/" + attacker_token))
                token = hmac.new(key, b"khodroyar-ci-download:" + sha.encode(), hashlib.sha256).hexdigest()
                with request(endpoint + "/verify/" + token) as response:
                    self.assertEqual(plain, response.read())
                (delivery.root / "ready.json").write_text(json.dumps(dict(sha256=sha, head_sha="a" * 40)))
                with request("/download/" + download + "/" + receiver.FILE_NAME, headers={"Range": "bytes=20-99"}) as response:
                    self.assertEqual(206, response.status)
                    self.assertEqual(plain[20:100], response.read())
                delivery.config["expires_at"] = time.time() - 1
                self.assertFalse(delivery.authorized(endpoint + "/manifest", "upload"))
            finally:
                server.shutdown(); server.server_close(); thread.join(timeout=5)


if __name__ == "__main__":
    unittest.main()
