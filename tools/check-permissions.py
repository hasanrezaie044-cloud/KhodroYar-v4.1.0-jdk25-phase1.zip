#!/usr/bin/env python3
"""Fail closed: parse uses-permission, never a substring of package/permission names."""
import argparse
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = '{http://schemas.android.com/apk/res/android}'
TOOLS = '{http://schemas.android.com/tools}'
ALLOWED = {
    'android.permission.POST_NOTIFICATIONS', 'android.permission.USE_BIOMETRIC',
    'android.permission.INTERNET', 'android.permission.ACCESS_NETWORK_STATE',
    'com.khodroyar.app.permission.LOCAL_OCR',
    # AndroidX Core's app-owned signature permission, NOT a dangerous system permission.
    'com.khodroyar.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION',
}
REQUIRED = {'android.permission.INTERNET', 'android.permission.ACCESS_NETWORK_STATE'}

def source_permissions(path):
    root = ET.parse(path).getroot()
    return {node.attrib[ANDROID + 'name'] for node in root if node.tag.startswith('uses-permission') and node.attrib.get(TOOLS + 'node') != 'remove'}

def aapt_permissions(text):
    return set(re.findall(r"^uses-permission(?:-sdk-\d+)?:\s+name='([^']+)'", text, flags=re.M))

def audit(permissions):
    problems = []
    for permission in sorted(permissions - ALLOWED):
        problems.append('Permission outside allow-list: ' + permission)
    for permission in sorted(REQUIRED - permissions):
        problems.append('Required existing network permission missing: ' + permission)
    return problems

def main():
    parser = argparse.ArgumentParser()
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--manifest', type=Path); group.add_argument('--aapt', type=Path)
    args = parser.parse_args()
    permissions = source_permissions(args.manifest) if args.manifest else aapt_permissions(args.aapt.read_text())
    for permission in sorted(permissions): print('  ' + permission)
    errors = audit(permissions)
    if errors: raise SystemExit('\n'.join(errors))
    print('Permission audit passed.')

if __name__ == '__main__': main()
