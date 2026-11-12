#!/usr/bin/env python3
"""Bounded capability-protected private APK receiver/download server for Agent Mode.

Run outside the app on a sandbox preview port. The config (0600, outside Git)
contains a private RSA key path, independent random upload/download tokens and an
expiry. Only the ciphertext upload URL/public RSA key may be given to CI. The user
capability/private key and CI's HMAC readback capability are never public. Upload
is NOT publication: GET is sealed until a local ready.json pins the verified SHA
and commit. Directory listings, arbitrary paths and public APK artifacts do not exist.
"""
import argparse
import base64
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import threading
import time
import urllib.parse
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

ORIGINAL_CERT = "03bfbc3575667403c3259fae345f3eb1ad3045373cb62123be376ef4898caaf0"
FILE_NAME = "KhodroYar-5.4.1-release-signed.apk"


def byte_range(value, size):
    if value is None:
        return 0, size - 1, False
    hit = re.fullmatch(r"bytes=(\d*)-(\d*)", value)
    if not hit or not any(hit.groups()):
        raise ValueError("Invalid range")
    left, right = hit.groups()
    if not left:
        length = int(right)
        if length <= 0:
            raise ValueError("Invalid suffix range")
        return max(0, size - length), size - 1, True
    start, end = int(left), min(int(right), size - 1) if right else size - 1
    if start < 0 or start >= size or end < start:
        raise ValueError("Unsatisfiable range")
    return start, end, True


class PrivateDelivery:
    def __init__(self, directory, config):
        self.root = Path(directory)
        self.root.mkdir(parents=True, exist_ok=True)
        self.root.chmod(0o700)
        self.config = json.loads(Path(config).read_text())
        for key in ("upload_token", "download_token"):
            if not re.fullmatch(r"[A-Za-z0-9_-]{40,100}", self.config[key]):
                raise ValueError("A random capability is required")
        if self.config["upload_token"] == self.config["download_token"]:
            raise ValueError("Upload and download capabilities must be independent")
        self.private_key = serialization.load_pem_private_key(Path(self.config["rsa_private_key"]).read_bytes(), password=None)
        self.lock = threading.Lock()
        self.manifest = None
        path = self.root / "received-manifest.json"
        if path.is_file():
            self.manifest = json.loads(path.read_text())

    def authorized(self, path, kind):
        parts = path.split("/")
        prefix = "upload" if kind == "upload" else "download"
        return (time.time() < self.config["expires_at"] and len(parts) >= 4 and parts[1] == prefix
                and hmac.compare_digest(parts[2], self.config[kind + "_token"]))

    def unwrap(self, meta):
        wrapped = base64.b64decode(meta["wrapped_key"], validate=True)
        key = self.private_key.decrypt(wrapped, padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
        if len(key) != 32:
            raise ValueError("Invalid delivery key")
        return key

    def can_verify(self, suffix):
        try:
            if self.manifest is None or not re.fullmatch(r"verify/[0-9a-f]{64}", suffix):
                return False
            expected = hmac.new(self.unwrap(self.manifest), b"khodroyar-ci-download:" + self.manifest["sha256"].encode(), hashlib.sha256).hexdigest()
            return hmac.compare_digest(suffix.split("/")[1], expected)
        except (ValueError, TypeError, KeyError):
            return False

    def ready(self):
        try:
            ready = json.loads((self.root / "ready.json").read_text())
            return (self.manifest is not None and ready["sha256"] == self.manifest["sha256"]
                    and ready["head_sha"] == self.manifest["verification"]["head_sha"]
                    and (self.root / FILE_NAME).is_file())
        except (OSError, ValueError, KeyError, TypeError):
            return False

    def body(self, request, limit):
        length = int(request.headers.get("Content-Length", "-1"))
        if not 0 <= length <= limit:
            raise ValueError("Invalid length")
        data = request.rfile.read(length)
        if len(data) != length:
            raise ValueError("Truncated body")
        return data

    def receive(self, request, path):
        if not self.authorized(path, "upload"):
            request.send_error(404)
            return
        suffix = "/".join(path.split("/")[3:])
        with self.lock:
            if suffix == "manifest":
                meta = json.loads(self.body(request, 65536))
                size, sha = meta["bytes"], meta["sha256"]
                proof = meta["verification"]
                if not (isinstance(size, int) and 0 < size < 100_000_000 and re.fullmatch(r"[0-9a-f]{64}", sha)
                        and meta["chunk_bytes"] == 512 * 1024 and meta["cipher_bytes"] == size
                        and re.fullmatch(r"[0-9a-f]{64}", meta["cipher_sha256"])
                        and len(base64.b64decode(meta["nonce"], validate=True)) == 12
                        and len(base64.b64decode(meta["tag"], validate=True)) == 16
                        and proof["sha256"] == sha and proof["bytes"] == size
                        and proof["certificate_sha256"] == ORIGINAL_CERT and proof["package"] == "com.khodroyar.app"
                        and re.fullmatch(r"[0-9a-f]{40}", proof["head_sha"])):
                    raise ValueError("Invalid APK proof")
                self.unwrap(meta)  # Wrong recipient/corrupt RSA wrapping is rejected before any file write.
                if self.manifest is None or self.manifest["sha256"] != sha:
                    (self.root / "ready.json").unlink(missing_ok=True)
                    for file in self.root.glob("part-*.bin"):
                        file.unlink()
                self.manifest = meta
                (self.root / "received-manifest.json").write_text(json.dumps(meta, indent=2) + "\n")
                request.json_response({"ok": True})
            elif self.manifest is None or request.headers.get("X-File-SHA256") != self.manifest["sha256"]:
                raise ValueError("No matching upload")
            elif re.fullmatch(r"part/\d{1,3}", suffix):
                index = int(suffix.split("/")[1])
                meta = self.manifest
                count = math.ceil(meta["bytes"] / meta["chunk_bytes"])
                if index >= count:
                    raise ValueError("Chunk index out of bounds")
                data = self.body(request, meta["chunk_bytes"])
                expected = min(meta["chunk_bytes"], meta["bytes"] - index * meta["chunk_bytes"])
                if len(data) != expected or hashlib.sha256(data).hexdigest() != request.headers.get("X-Chunk-SHA256"):
                    raise ValueError("Invalid chunk")
                file = self.root / f"part-{index:03d}.bin"
                temporary = file.with_suffix(".tmp")
                temporary.write_bytes(data)
                temporary.replace(file)
                request.json_response({"ok": True})
            elif suffix == "complete":
                self.body(request, 64)
                meta = self.manifest
                final = self.root / FILE_NAME
                if final.is_file() and final.stat().st_size == meta["bytes"] and hashlib.sha256(final.read_bytes()).hexdigest() == meta["sha256"]:
                    request.json_response({"ok": True, "bytes": meta["bytes"], "sha256": meta["sha256"]})
                    return
                temp = self.root / "received-apk.tmp"
                key = self.unwrap(meta)
                decryptor = Cipher(algorithms.AES(key), modes.GCM(base64.b64decode(meta["nonce"]), base64.b64decode(meta["tag"]))).decryptor()
                decryptor.authenticate_additional_data(json.dumps(meta["verification"], sort_keys=True, separators=(",", ":")).encode())
                plain_hash, cipher_hash = hashlib.sha256(), hashlib.sha256()
                try:
                    with temp.open("wb") as stream:
                        for index in range(math.ceil(meta["bytes"] / meta["chunk_bytes"])):
                            data = (self.root / f"part-{index:03d}.bin").read_bytes()
                            cipher_hash.update(data)
                            decoded = decryptor.update(data)
                            stream.write(decoded); plain_hash.update(decoded)
                        last = decryptor.finalize()
                        stream.write(last); plain_hash.update(last)
                    if (temp.stat().st_size != meta["bytes"] or plain_hash.hexdigest() != meta["sha256"]
                            or cipher_hash.hexdigest() != meta["cipher_sha256"]):
                        raise ValueError("APK encrypted or plaintext hash mismatch")
                except (ValueError, InvalidTag, OSError):
                    temp.unlink(missing_ok=True)
                    raise ValueError("Private APK authentication/decryption failed") from None
                temp.chmod(0o600)
                # Independently recheck CRC, pinned model hashes, size, ABIs and ELF alignment.
                subprocess.run([sys.executable, str(Path(__file__).with_name("audit-ocr-apk.py")), str(temp)],
                               check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=90)
                temp.replace(self.root / FILE_NAME)
                (self.root / "verification.json").write_text(json.dumps(meta["verification"], indent=2) + "\n")
                for file in self.root.glob("part-*.bin"):
                    file.unlink()
                print(f"Private APK received and independently audited: {meta['bytes']} bytes, {meta['sha256']}", flush=True)
                request.json_response({"ok": True, "bytes": meta["bytes"], "sha256": meta["sha256"]})
            else:
                request.send_error(404)


class Handler(BaseHTTPRequestHandler):
    server_version = "PrivateDownload"
    sys_version = ""

    def setup(self):
        super().setup()
        self.connection.settimeout(120)

    def log_message(self, format, *args):
        # The default HTTP access log would disclose the capabilities.
        pass

    def common_headers(self):
        self.send_header("Cache-Control", "private, no-store")
        self.send_header("X-Robots-Tag", "noindex, nofollow, noarchive")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Content-Type-Options", "nosniff")

    def json_response(self, value):
        data = json.dumps(value).encode()
        self.send_response(200)
        self.common_headers()
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        try:
            self.server.delivery.receive(self, urllib.parse.urlsplit(self.path).path)
        except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError):
            self.send_error(400, "Invalid or incomplete private transfer")

    def do_HEAD(self):
        self.get_file(head=True)

    def do_GET(self):
        self.get_file(head=False)

    def get_file(self, head):
        path = urllib.parse.urlsplit(self.path).path
        delivery = self.server.delivery
        if path == "/":
            data = b"Private KhodroYar download service. Use the private, temporary link supplied in the conversation. No public file listing."
            self.send_response(200)
            self.common_headers()
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            if not head:
                self.wfile.write(data)
            return
        suffix = "/".join(path.split("/")[3:])
        upload_verify = delivery.authorized(path, "upload") and delivery.can_verify(suffix)
        download = delivery.authorized(path, "download") and delivery.ready() and suffix in (FILE_NAME, "verification.json", "test-summary.md")
        if not (upload_verify or download):
            self.send_error(404)
            return
        file = delivery.root / (FILE_NAME if upload_verify else suffix)
        if not file.is_file():
            self.send_error(404)
            return
        size = file.stat().st_size
        try:
            first, last, partial = byte_range(self.headers.get("Range"), size)
        except ValueError:
            self.send_response(416)
            self.send_header("Content-Range", f"bytes */{size}")
            self.end_headers()
            return
        self.send_response(206 if partial else 200)
        self.common_headers()
        self.send_header("Content-Type", "application/vnd.android.package-archive" if file.suffix == ".apk" else "text/plain; charset=utf-8")
        self.send_header("Content-Disposition", f'attachment; filename="{file.name}"')
        self.send_header("Accept-Ranges", "bytes")
        if partial:
            self.send_header("Content-Range", f"bytes {first}-{last}/{size}")
        self.send_header("Content-Length", str(last - first + 1))
        self.end_headers()
        if not head:
            try:
                with file.open("rb") as stream:
                    stream.seek(first)
                    remaining = last - first + 1
                    while remaining:
                        data = stream.read(min(512 * 1024, remaining))
                        if not data:
                            break
                        self.wfile.write(data)
                        remaining -= len(data)
            except (BrokenPipeError, ConnectionResetError):
                pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True)
    parser.add_argument("--config", required=True)
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    server.delivery = PrivateDelivery(args.directory, args.config)
    print(f"Private delivery service listening on port {args.port}; files sealed pending complete CI verification", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
