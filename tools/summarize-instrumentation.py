"""Strict AndroidJUnitRunner protocol verification for the single signed APK test.

A shell/adb exit code alone is not a passing test. Require the exact expected test
start, successful finish, one discovered test, runner summary and final result.
JUnit XML, if requested, is derived from actual instrumentation protocol records.
"""
import argparse
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

CLASS = 'com.khodroyar.app.ui.ReleaseApkSmokeTest'
TEST = 'signedOnlineOnlyReviewAndBazaarIpcDoNotNeedAnOfflineOcrEngine'


def analyze(text):
    records = []
    fields = {}
    last_key = None
    completion = []
    for line in text.replace('\r', '').splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key, separator, value = line[len('INSTRUMENTATION_STATUS: '):].partition('=')
            if not separator:
                return False, False, 'Malformed instrumentation status field'
            fields[key] = value
            last_key = key
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code = int(line.partition(': ')[2])
            records.append((code, fields))
            fields = {}
            last_key = None
        elif line.startswith('INSTRUMENTATION_CODE: '):
            completion.append(int(line.partition(': ')[2]))
            last_key = None
        elif line.startswith('INSTRUMENTATION_'):
            last_key = None
        elif last_key is not None:
            fields[last_key] += '\n' + line
    starts = [fields for code, fields in records if code == 1]
    started = bool(starts)
    if (len(starts) != 1 or starts[0].get('class') != CLASS or starts[0].get('test') != TEST
            or starts[0].get('numtests') != '1'):
        return False, started, 'The exact single signed-runtime test did not start once: ' + repr(records)[:600] + '\nRaw protocol tail: ' + text[-2600:]
    finished = [(code, fields) for code, fields in records if code != 1]
    if (len(finished) != 1 or finished[0][0] != 0
            or finished[0][1].get('class') != CLASS or finished[0][1].get('test') != TEST
            or finished[0][1].get('numtests') != '1'):
        return False, True, 'Signed-runtime test failed, skipped, crashed or never finished: ' + repr(finished)[:3500]
    if completion != [-1] or not re.search(r'^OK \(1 test\)\s*$', text, re.M):
        return False, True, 'Runner did not report complete successful execution of exactly one test'
    if 'INSTRUMENTATION_FAILED:' in text or 'FAILURES!!!' in text:
        return False, True, 'Instrumentation reported a failure despite an earlier result'
    return True, True, 'One actual signed-runtime test started and passed; runner completed'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('log')
    parser.add_argument('report')
    args = parser.parse_args()
    path = Path(args.log)
    if not path.is_file() or path.stat().st_size > 5_000_000:
        raise SystemExit('Missing or oversized raw instrumentation protocol')
    passed, started, detail = analyze(path.read_text(errors='replace'))
    suite = ET.Element('testsuite', name=CLASS, tests=str(int(started)), failures='0', errors=str(int(not passed)), skipped='0')
    if started:
        case = ET.SubElement(suite, 'testcase', classname=CLASS, name=TEST)
        if not passed:
            ET.SubElement(case, 'error', message='Signed-runtime instrumentation did not pass').text = detail
    else:
        ET.SubElement(suite, 'system-err').text = detail
    report = Path(args.report)
    report.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(suite).write(report, encoding='utf-8', xml_declaration=True)
    print(('::notice' if passed else '::error') + ' title=Signed instrumentation protocol::' + detail.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A'))
    if not passed:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
