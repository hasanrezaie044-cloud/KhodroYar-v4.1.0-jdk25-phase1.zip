#!/usr/bin/env python3
"""Fail if Gradle didn't actually run tests; publish counts as a GitHub check annotation."""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else "android/app/build/test-results/testDebugUnitTest")
    reports = sorted(root.rglob("TEST-*.xml"))
    if not reports:
        raise SystemExit(f"No JUnit XML reports found under {root}; tests must actually execute")
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    for path in reports:
        suite = ET.parse(path).getroot()
        values = {key: int(suite.get(key, "0")) for key in counts}
        print(f"{suite.get('name', path.name)}: {values}")
        for key, value in values.items():
            counts[key] += value
    summary = ", ".join(f"{key}={value}" for key, value in counts.items())
    print(f"::notice title=Android JVM tests::{summary}; suites={len(reports)}")
    if counts["tests"] == 0 or counts["failures"] or counts["errors"]:
        raise SystemExit("Android unit tests failed or no tests executed")


if __name__ == "__main__":
    main()
