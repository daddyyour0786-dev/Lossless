#!/usr/bin/env python3
"""Write every Lossless icon from one drawing.

The mark itself lives in lossless_mark.py; this script only decides sizes and
colours, and writes the results into the places Android and the repo read them
from:

  app/src/main/res/drawable{,-night}/ic_launcher_foreground.xml  adaptive layers
  app/src/main/res/values{,-night}/ic_launcher_background.xml    the tile behind it
  app/src/main/res/mipmap-anydpi{,-v26}/ic_launcher*.xml         adaptive definitions
  app/src/main/res/mipmap-*/ic_launcher*.webp                    legacy launcher icons
  app/src/main/res/drawable/ic_logo.xml                          in-app wordmark
  app/src/main/res/drawable/ic_launcher_monochrome.xml           themed layer
  app/src/main/res/drawable/ic_notification_logo*.xml            status-bar glyph
  sharedUi/src/commonMain/composeResources/drawable/ic_logo.xml  the same wordmark
  Logo.png / Logo.svg / LogoTransparent.svg / Banner.png         the repo's own
  docs/play-store-icon-512.png                                   store listing

Run it after changing the geometry:  python3 tools/generate_icons.py
Requires Pillow for the raster outputs (SVG/XML need nothing).
"""

import math
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import lossless_mark as mark

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
COMPOSE_RES = ROOT / "sharedUi/src/commonMain/composeResources/drawable"

# The sheet's colours: near-white tile with a black mark, black tile with a white
# one, and whichever of the two the launcher's dark theme asks for.
LIGHT_TILE = "#F7F7F9"
LIGHT_MARK = "#000000"
DARK_TILE = "#000000"
DARK_MARK = "#FFFFFF"

LEGACY_SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    print(f"  wrote {path.relative_to(ROOT)}")


def rgba(hex_colour, alpha=255):
    h = hex_colour.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), alpha)


# ---- raster ----------------------------------------------------------------

def render_mark(size, colour, supersample=4):
    """The mark alone, transparent, at `size` x `size` pixels.

    Drawn with Pillow rather than through an SVG rasteriser so the generator has
    no dependency beyond Pillow: the ring is an ellipse and the strike a densely
    sampled polyline, both at [supersample] x and scaled down, which is enough to
    keep the curves clean at launcher sizes.
    """
    from PIL import Image, ImageDraw

    s = size * supersample
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    scale = s / 100.0
    stroke = mark.STROKE * scale

    # Ring. Pillow's ellipse `width` is drawn inward from the bounding box, so
    # the box is the outer edge: radius + half a stroke.
    outer = (mark.R + mark.STROKE / 2) * scale
    cx, cy = mark.CX * scale, mark.CY * scale
    draw.ellipse([cx - outer, cy - outer, cx + outer, cy + outer],
                 outline=rgba(colour), width=max(1, round(stroke)))

    # Strike: the sine, sampled finely, with round caps.
    steps = 256
    pts = []
    for i in range(steps + 1):
        x = mark.CX - mark.TIP + (2 * mark.TIP) * i / steps
        y = mark.CY + mark.AMPLITUDE * math.sin(2 * math.pi * (x - mark.CX) / mark.PERIOD)
        pts.append((x * scale, y * scale))
    draw.line(pts, fill=rgba(colour), width=max(1, round(stroke)), joint="curve")
    r = stroke / 2
    for x, y in (pts[0], pts[-1]):
        draw.ellipse([x - r, y - r, x + r, y + r], fill=rgba(colour))

    return img.resize((size, size), Image.LANCZOS)


def render_tile(size, tile_colour, mark_colour, round_mask=False, span=0.52,
                supersample=4):
    """A finished launcher tile: a filled background with the mark on it."""
    from PIL import Image, ImageDraw

    s = size * supersample
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    if round_mask:
        draw.ellipse([0, 0, s - 1, s - 1], fill=rgba(tile_colour))
    else:
        draw.rectangle([0, 0, s, s], fill=rgba(tile_colour))

    glyph_px = int(round(s * span / (2 * mark.TIP + mark.STROKE) * 100))
    glyph = render_mark(max(1, glyph_px), mark_colour, supersample=supersample)
    img.alpha_composite(glyph, ((s - glyph.width) // 2, (s - glyph.height) // 2))

    return img.resize((size, size), Image.LANCZOS)


def save(img, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, format="WEBP", quality=95, method=6) if path.suffix == ".webp" \
        else img.save(path)
    print(f"  wrote {path.relative_to(ROOT)}")


# ---- Android resources ------------------------------------------------------

def android():
    print("Android resources")

    # Adaptive layers. The foreground carries the mark, and its colour is the
    # only thing the night variant changes — the tile behind it flips too, so a
    # light-theme phone gets a near-white tile with a black mark and a dark-theme
    # phone the reverse, exactly as the design sheet lays out.
    write(RES / "drawable/ic_launcher_foreground.xml", mark.adaptive_foreground(LIGHT_MARK))
    write(RES / "drawable-night/ic_launcher_foreground.xml", mark.adaptive_foreground(DARK_MARK))
    write(RES / "values/ic_launcher_background.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/generate_icons.py — the adaptive icon's tile colour. -->
<resources>
    <color name="ic_launcher_background">{LIGHT_TILE}</color>
</resources>
""")
    write(RES / "values-night/ic_launcher_background.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/generate_icons.py — the adaptive icon's tile colour, dark. -->
<resources>
    <color name="ic_launcher_background">{DARK_TILE}</color>
</resources>
""")

    # The themed layer: one flat colour, which the system tints from the
    # wallpaper. Opaque white, because from Android 13 that is the only thing the
    # system reads — everything else is thrown away.
    write(RES / "drawable/ic_launcher_monochrome.xml", mark.adaptive_foreground("#FFFFFF"))

    adaptive = """<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/generate_icons.py — the Lossless adaptive icon. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""
    for name in ("ic_launcher", "ic_launcher_round"):
        write(RES / f"mipmap-anydpi-v26/{name}.xml", adaptive)

    # Themed icon entry in Settings: an adaptive icon whose two layers are both
    # the mark, so it previews the way a themed launcher will draw it.
    write(RES / "mipmap-anydpi-v26/ic_monochrome.xml", """<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/generate_icons.py — the themed (monochrome) launcher icon. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_monochrome" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
""")

    # Legacy tiles, for launchers that predate adaptive icons and for anything
    # that reads a bitmap: the dark tile with a white mark, as the sheet's
    # "store / legacy" swatch.
    for density, px in LEGACY_SIZES.items():
        save(render_tile(px, DARK_TILE, DARK_MARK, span=0.52),
             RES / f"mipmap-{density}/ic_launcher.webp")
        save(render_tile(px, DARK_TILE, DARK_MARK, round_mask=True, span=0.52),
             RES / f"mipmap-{density}/ic_launcher_round.webp")

    # In-app wordmark: tight-cropped, so the wide layouts that drew the old
    # 730x484 wordmark keep their proportions (this one is 1.5156 : 1).
    write(RES / "drawable/ic_logo.xml", mark.tight_logo("#FFFFFFFF"))
    write(COMPOSE_RES / "ic_logo.xml", mark.tight_logo("#FFFFFFFF"))

    # Status-bar glyph. The system keeps only the alpha channel, so it is a white
    # silhouette on nothing, sized to the 22dp content box of a 24dp icon.
    write(RES / "drawable/ic_notification_logo.xml", _notification_glyph())


def _notification_glyph():
    scale = 22.0 / (2 * mark.TIP + mark.STROKE)
    dx = (24.0 - 100.0 * scale) / 2.0
    dy = (24.0 - 100.0 * scale) / 2.0
    return mark.vector_drawable(24, 24, scale, dx, dy, "#FFFFFFFF")


# ---- the repo's own copies --------------------------------------------------

def repo_assets():
    print("Repo assets")
    from PIL import Image, ImageDraw, ImageFont

    write(ROOT / "Logo.svg", mark.svg(1024, "#FFFFFF", None, fit=1.0))
    write(ROOT / "LogoTransparent.svg", mark.svg(1024, "#FFFFFF", None, fit=1.0))
    write(ROOT / "LogoBlack.svg", mark.svg(1024, "#000000", None, fit=1.0))

    save(render_tile(1024, DARK_TILE, DARK_MARK, span=0.52), ROOT / "Logo.png")
    save(render_tile(512, DARK_TILE, DARK_MARK, span=0.52),
         ROOT / "docs/play-store-icon-512.png")

    # Banner: the mark and the name, on the same near-black the app's dark theme
    # uses. Text is set in DejaVu Sans Bold, which is what a build box is
    # guaranteed to have; the shape of the letters is not the point of a banner.
    W, H = 1500, 500
    banner = Image.new("RGBA", (W, H), rgba("#0A0A0B"))
    glyph = render_mark(300, "#FFFFFF", supersample=4)
    banner.alpha_composite(glyph, (120, (H - 300) // 2))
    draw = ImageDraw.Draw(banner)
    font, sub = None, None
    for candidate, size in (("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 132),
                            ("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 132)):
        try:
            font = ImageFont.truetype(candidate, size)
            sub = ImageFont.truetype(candidate, 40)
            break
        except OSError:
            continue
    if font:
        draw.text((500, 200), "Lossless", font=font, fill=rgba("#FFFFFF"))
        draw.text((504, 350), "Aesthetic music, bit-perfect", font=sub, fill=rgba("#8A8A8E"))
    save(banner, ROOT / "Banner.png")


if __name__ == "__main__":
    android()
    repo_assets()
    print("done")
