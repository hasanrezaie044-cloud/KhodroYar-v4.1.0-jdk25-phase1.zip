#!/usr/bin/env python3
"""Protocol/parser fixtures only. They do not run Android or establish APK proof."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

path = Path(__file__).with_name('summarize-instrumentation.py')
spec = importlib.util.spec_from_file_location('instrumentation_summary', path)
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)


def record(code):
    return (f'INSTRUMENTATION_STATUS: class={summary.CLASS}\n'
            f'INSTRUMENTATION_STATUS: test={summary.TEST}\n'
            'INSTRUMENTATION_STATUS: numtests=1\n'
            f'INSTRUMENTATION_STATUS_CODE: {code}\n')


SUCCESS = record(1) + record(0) + 'INSTRUMENTATION_RESULT: stream=\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n'


class InstrumentationSummaryTest(unittest.TestCase):
    def test_complete_exact_single_test(self):
        self.assertEqual((True, True), summary.analyze(SUCCESS)[:2])
        self.assertTrue(summary.analyze(SUCCESS.replace('\n', '\r\n'))[0])

    def test_exit_without_tests_or_start_without_finish_is_not_a_pass(self):
        for raw in ('', 'INSTRUMENTATION_CODE: -1\n', record(1), 'OK (1 test)\n'):
            with self.subTest(raw=raw):
                self.assertFalse(summary.analyze(raw)[0])

    def test_fail_skipped_and_assumption_results_are_not_passes(self):
        for code in (-1, -2, -3, -4):
            with self.subTest(code=code):
                self.assertFalse(summary.analyze(SUCCESS.replace('STATUS_CODE: 0', f'STATUS_CODE: {code}'))[0])

    def test_wrong_scope_count_or_duplicate_execution_is_not_a_pass(self):
        for raw in (SUCCESS.replace(summary.CLASS, 'another.Class'), SUCCESS.replace(summary.TEST, 'anotherTest'),
                    SUCCESS.replace('numtests=1', 'numtests=2'), SUCCESS + SUCCESS):
            with self.subTest(raw=raw):
                self.assertFalse(summary.analyze(raw)[0])

    def test_runner_failure_or_missing_final_summary_is_not_a_pass(self):
        for raw in (SUCCESS.replace('INSTRUMENTATION_CODE: -1', 'INSTRUMENTATION_CODE: 0'),
                    SUCCESS.replace('OK (1 test)', ''), SUCCESS + 'INSTRUMENTATION_FAILED: crashed\n',
                    SUCCESS.replace('OK (1 test)', 'FAILURES!!!')):
            with self.subTest(raw=raw):
                self.assertFalse(summary.analyze(raw)[0])

    def test_xml_counts_come_from_the_protocol_and_errors_exit_nonzero(self):
        with tempfile.TemporaryDirectory() as directory:
            log, report = Path(directory) / 'raw.txt', Path(directory) / 'TEST-signed.xml'
            for raw, count, errors, code in ((SUCCESS, 1, 0, 0), ('', 0, 1, 1), (record(1), 1, 1, 1)):
                log.write_text(raw)
                result = subprocess.run([sys.executable, str(path), str(log), str(report)], capture_output=True)
                self.assertEqual(code, result.returncode)
                suite = ET.parse(report).getroot()
                self.assertEqual(str(count), suite.get('tests'))
                self.assertEqual(str(errors), suite.get('errors'))
                self.assertEqual(count, len(suite.findall('testcase')))


if __name__ == '__main__':
    unittest.main()
