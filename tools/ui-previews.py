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
for name in ("01-home-light-fa", "06-report-donut", "13-chart-dark-en", "18-dashboard-bars", "19-home-dark-320-large-text"):
    path = root / f"{name}.png"
    if not path.is_file():
        continue
    with Image.open(path) as image:
        image = image.convert("RGB")
        image.thumbnail((480, 1000))
        encoded = ""
        for quality in (76, 65, 50, 35):
            buffer = BytesIO()
            image.save(buffer, "WEBP", quality=quality, method=6)
            encoded = b64encode(buffer.getvalue()).decode("ascii")
            if len(encoded) < 56_000:
                break
        if len(encoded) < 56_000:
            print(f"::notice title=native-preview/{name}::ORB1:{encoded}")
