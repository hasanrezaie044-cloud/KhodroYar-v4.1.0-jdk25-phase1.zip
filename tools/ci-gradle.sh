#!/usr/bin/env bash
# Preserve the actual exit status, emit bounded diagnostics to the Checks API on failure.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$ROOT/.verification"
NAME="${CI_CHECK_NAME:-gradle}"
LOG="$ROOT/.verification/$NAME.log"
cd "$ROOT/android"
./gradlew "$@" --stacktrace > "$LOG" 2>&1
STATUS=$?
tail -100 "$LOG"
if [[ $STATUS -ne 0 ]]; then
  python3 - "$LOG" <<'PY'
import sys
from pathlib import Path
lines = Path(sys.argv[1]).read_text(errors='replace').splitlines()
interesting = [line for line in lines if line.startswith('e:') or 'error:' in line.lower() or 'Could not' in line or 'What went wrong' in line or line.startswith('> ') or 'failed' in line.lower()]
text = '\n'.join(interesting if interesting else lines[-80:])[-40000:]
print('::error title=Gradle diagnostics::' + text.replace('%','%25').replace('\r','%0D').replace('\n','%0A'))
import xml.etree.ElementTree as ET
import json
findings = []
for file in Path('app/build/reports').glob('lint-results-*.xml'):
 for issue in ET.parse(file).getroot().findall('issue'):
  if issue.get('severity') in ('Error','Fatal'):
   findings.append({'id': issue.get('id'), 'message': issue.get('message'), 'locations': [loc.attrib for loc in issue.findall('location')]})
if findings: print('::notice title=Lint findings::' + json.dumps(findings, ensure_ascii=False).replace('%','%25'))
PY
fi
exit "$STATUS"
