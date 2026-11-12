#!/usr/bin/env python3
"""High-confidence credential scan. Prints paths/types only, never matched secret values.
This is a safety check, not a proof that every possible secret encoding is absent.
"""
import argparse
import re
import subprocess
import zipfile
from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent
PATTERNS = {
    'private key': rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',
    'provider secret': rb'\bsk-(?:proj-)?[A-Za-z0-9_-]{24,}\b',
    'GitHub token': rb'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})\b',
    'Google API key': rb'\bAIza[A-Za-z0-9_-]{35}\b',
    'AWS access id': rb'\bAKIA[0-9A-Z]{16}\b',
}

def scan(name, data):
    return [f'{name}: {kind}' for kind, pattern in PATTERNS.items() if re.search(pattern, data)]

def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--apk', action='append', default=[]); parser.add_argument('--history', action='store_true')
    args = parser.parse_args(); findings = []; count = 0
    names = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode().split('\0')
    for name in filter(None, names):
        file = ROOT / name
        if file.is_file(): findings += scan(name, file.read_bytes()); count += 1
    if args.history:
        seen = set()
        for rev in subprocess.check_output(['git', 'rev-list', 'HEAD'], cwd=ROOT, text=True).splitlines():
            for line in subprocess.check_output(['git', 'ls-tree', '-r', rev], cwd=ROOT, text=True).splitlines():
                meta, name = line.split('\t', 1); sha = meta.split()[2]
                if sha not in seen:
                    seen.add(sha); findings += scan(f'{rev[:12]}:{name}', subprocess.check_output(['git', 'cat-file', 'blob', sha], cwd=ROOT))
    for apk in args.apk:
        with zipfile.ZipFile(apk) as archive:
            for member in archive.infolist():
                if not member.is_dir(): findings += scan(f'{Path(apk).name}:{member.filename}', archive.read(member)); count += 1
    if findings: raise SystemExit('\n'.join(findings))
    print(f'No high-confidence embedded credentials found in {count} current files/APK members' + (' and reachable Git history.' if args.history else '.'))

if __name__ == '__main__': main()
