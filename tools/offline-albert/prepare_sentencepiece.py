#!/usr/bin/env python3
"""Fetch pinned Apache-2.0 tokenizer source at BUILD time; never on a user's phone."""
import hashlib
from pathlib import Path
import shutil
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
REV = '31646a467d2051eb904e0b45de3a73e91fe1c1e3'
SHA = 'b4eb17e6bea5c9380ddd79d04d01d6aa5e280be8f3e3cef1f745a80e6d9451ce'
folder = ROOT / 'android/.cache/sentencepiece'
archive = ROOT / 'android/.cache/sentencepiece-source.tar.gz'
archive.parent.mkdir(parents=True, exist_ok=True)
if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != SHA:
    with urllib.request.urlopen(f'https://codeload.github.com/google/sentencepiece/tar.gz/{REV}', timeout=90) as r, archive.open('wb') as f:
        shutil.copyfileobj(r, f)
assert hashlib.sha256(archive.read_bytes()).hexdigest() == SHA, 'SentencePiece source checksum mismatch'
if not (folder / 'KHODROYAR-SOURCE-PIN').exists() or (folder / 'KHODROYAR-SOURCE-PIN').read_text() != REV:
    if folder.exists(): shutil.rmtree(folder)  # generated, verified build cache only
    with tarfile.open(archive) as tar:
        prefix = f'sentencepiece-{REV}/'
        members = []
        for member in tar.getmembers():
            assert member.name.startswith(prefix) or member.name == prefix[:-1]
            assert not member.issym() and not member.islnk(), 'Unexpected source archive symlink'
            if not member.name.startswith(prefix): continue
            member.name = member.name[len(prefix):]
            assert not Path(member.name).is_absolute() and '..' not in Path(member.name).parts
            if member.name: members.append(member)
        tar.extractall(folder, members=members)
    (folder / 'KHODROYAR-SOURCE-PIN').write_text(REV)
print('Verified SentencePiece build source:', REV)
