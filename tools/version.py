#!/usr/bin/env python3
"""Read actual metadata. --bump uses max(current, verified release inventory), never archive names."""
import argparse
import json
import re
import subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent

def properties():
    return dict(line.strip().split('=', 1) for line in (ROOT / 'android/version.properties').read_text().splitlines() if '=' in line and not line.lstrip().startswith('#'))

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--bump', choices=['patch', 'minor'])
    parser.add_argument('--base-ref', help='For initial restore, read the immutable source Gradle metadata instead of the new properties')
    args = parser.parse_args()
    current = properties()
    if args.base_ref:
        source = subprocess.check_output(['git', 'show', args.base_ref + ':android/app/build.gradle'], cwd=ROOT, text=True)
        current = {'versionName': re.search(r"versionName\s+'([^']+)'", source)[1], 'versionCode': int(re.search(r'versionCode\s+(\d+)', source)[1])}
    if args.bump:
        inventory = json.loads((ROOT / 'docs/version-lineage.json').read_text())
        candidates = [current, inventory['source'], *inventory['observedReleases']]
        def semver(item): return tuple(map(int, item['versionName'].split('.')))
        major, minor, patch = max(map(semver, candidates))
        if args.bump == 'minor': minor += 1; patch = 0
        else: patch += 1
        current = {'versionName': f'{major}.{minor}.{patch}', 'versionCode': max(int(x['versionCode']) for x in candidates) + 1}
        (ROOT / 'android/version.properties').write_text('\n'.join(f'{k}={v}' for k, v in current.items()) + '\n')
    print(json.dumps({'applicationId': 'com.khodroyar.app', **current}, ensure_ascii=False))

if __name__ == '__main__': main()
