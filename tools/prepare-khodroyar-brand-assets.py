#!/usr/bin/env python3
"""Generate KhodroYar login and launcher artwork from the approved source image."""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "assets/112237f75cc01879423c4fb9c8be64892ae646168c48532fb6ff0b8adcaddd5e.png"
RES = ROOT / "android/app/src/main/res"
if not SOURCE.is_file():
    raise SystemExit(f"Approved brand image is missing: {SOURCE}")

im = Image.open(SOURCE).convert("RGB")
w, h = im.size
# The supplied square artwork places the launcher tile on the left and phone
# mockup on the right. Coordinates are proportional to tolerate resized files.
def crop_box(x1, y1, x2, y2):
    return (round(w*x1), round(h*y1), round(w*x2), round(h*y2))

# Hero crop: actual phone display's upper branding/car scene, excluding bezel
# and the static mock PIN/fingerprint panel so native Compose controls remain live.
hero = im.crop(crop_box(0.51, 0.025, 0.975, 0.55))
hero.thumbnail((1200, 1500), Image.Resampling.LANCZOS)
hero.save(RES / "drawable-nodpi/login_background.webp", "WEBP", quality=88, method=6)

# Launcher artwork: isolate the left app tile, retaining its white rounded tile,
# emerald sports car, Persian wordmark and navy/green swooshes.
icon = im.crop(crop_box(0.025, 0.265, 0.455, 0.695))
side = min(icon.size)
left = (icon.width-side)//2
top = (icon.height-side)//2
icon = icon.crop((left, top, left+side, top+side)).resize((512,512), Image.Resampling.LANCZOS)

# Legacy launcher raster sizes.
for density, size in (("mdpi",48),("hdpi",72),("xhdpi",96),("xxhdpi",144),("xxxhdpi",192)):
    folder = RES / f"mipmap-{density}"
    folder.mkdir(parents=True, exist_ok=True)
    icon.resize((size,size), Image.Resampling.LANCZOS).save(folder/"ic_launcher.png", optimize=True)
    icon.resize((size,size), Image.Resampling.LANCZOS).save(folder/"ic_launcher_round.png", optimize=True)

# Adaptive foreground: keep the entire artwork inside Android's central safe zone.
for density, size in (("mdpi",108),("hdpi",162),("xhdpi",216),("xxhdpi",324),("xxxhdpi",432)):
    folder = RES / f"mipmap-{density}"
    canvas = Image.new("RGBA", (size,size), (0,0,0,0))
    tile = icon.convert("RGBA").resize((round(size*0.70),round(size*0.70)), Image.Resampling.LANCZOS)
    canvas.alpha_composite(tile, ((size-tile.width)//2,(size-tile.height)//2))
    canvas.save(folder/"ic_launcher_foreground.png", optimize=True)

print("Generated login_background.webp and launcher icons from the approved KhodroYar image.")
