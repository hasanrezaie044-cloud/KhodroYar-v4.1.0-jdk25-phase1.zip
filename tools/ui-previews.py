#!/usr/bin/env python3
"""Bounded synthetic-emulator previews for visual review via the GitHub checks API.

Full-resolution PNGs remain in the normal artifact. Only manual CI runs publish these
small WebP notices, so a reviewer can inspect native rendering when artifact-download
hosts are not reachable. No phone data is used by the emulator fixtures.
"""
from base64 import b64encode
from io import BytesIO
from pathlib import Path
import sys

from PIL import Image

root = Path(sys.argv[1])
for name in ("01-home-light-fa", "20-home-line-timeline-right", "18-dashboard-bars", "19-home-dark-320-large-text", "21-reports-annual-statistics", "22-reports-recent-services", "23-report-table-320-large-text", "25-quick-entry-320-large-text", "26-quick-entry-review", "28-quick-fuel-dark-review", "29-maintenance-forecast", "31-financial-comparison", "32-automation-health", "debug-quick-edit-state", "36-count-first-320-large-font", "35b-isfahan-full-road-catalogue"):
    if len(sys.argv) > 2 and sys.argv[2] != name:
        continue
    path = next(root.rglob(f"{name}.png"), None)
    if path is None:
        continue
    with Image.open(path) as image:
        image = image.convert("RGB")
        image.thumbnail((480, 1000))
        encoded = ""
        for quality in (76, 65, 50, 35):
            buffer = BytesIO()
            image.save(buffer, "WEBP", quality=quality, method=6)
            encoded = b64encode(buffer.getvalue()).decode("ascii")
            if len(encoded) <= 18_000:
                break
        if len(encoded) <= 18_000:
            # Check annotations retain at most 4096 message characters. Keep each
            # piece under 3100; at most six notices per image / forty-two per preview group.
            pieces = [encoded[i:i + 3000] for i in range(0, len(encoded), 3000)]
            for index, piece in enumerate(pieces, 1):
                print(f"::notice title=native-preview/{name}/{index}-of-{len(pieces)}::ORB2:{index}:{len(pieces)}:{piece}")
