#!/usr/bin/env python3
"""Create a complete SOURCE archive from current tracked/nonignored project files.
Build caches, Git metadata, APKs and private signing files are intentionally excluded.
"""
import hashlib
import re
import subprocess
import zipfile
from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent

def main():
    props = dict(line.split('=', 1) for line in (ROOT / 'android/version.properties').read_text().splitlines() if '=' in line)
    version = props['versionName']; assert re.fullmatch(r'[0-9]+(?:\.[0-9]+){2}', version)
    output = ROOT / 'deliverables' / f'KhodroYar-v{version}-complete-source.zip'
    output.parent.mkdir(parents=True, exist_ok=True)
    listed = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT).decode().split('\0')
    files = sorted({name for name in listed if name and (ROOT / name).is_file()})
    assert files and 'android/gradle/wrapper/gradle-wrapper.jar' in files
    assert not any(name.startswith(('.git/', '.verification/', 'deliverables/')) or name.endswith(('.jks', '.keystore', '.apk', '.aab')) for name in files)
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name in files: archive.write(ROOT / name, f'KhodroYar-v{version}/{name}')
    with zipfile.ZipFile(output) as archive:
        assert archive.testzip() is None
        assert archive.read(f'KhodroYar-v{version}/android/version.properties') == (ROOT / 'android/version.properties').read_bytes()
        mode = archive.getinfo(f'KhodroYar-v{version}/android/gradlew').external_attr >> 16
        assert mode & 0o111, 'Wrapper executable bit lost'
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix(output.suffix + '.sha256').write_text(f'{digest}  {output.name}\n')
    print(f'{output}\n{len(files)} source files, {output.stat().st_size} bytes\nSHA-256 {digest}')

if __name__ == '__main__': main()
