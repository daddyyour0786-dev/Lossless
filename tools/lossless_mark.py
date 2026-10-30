"""The Lossless mark — "Struck Circle".

A ring struck through by one period of a sine wave, whose ends run out past the
ring and finish in round caps. Defined once, here, in a 100x100 design space, so
every surface — launcher icon, adaptive foreground, themed layer, README logo,
notification glyph — is the same drawing at a different size instead of a dozen
hand-copied PNGs.

The proportions are measured off the design sheet rather than guessed: the ring's
stroke is a quarter of its radius, the wave's amplitude is 0.35 of it, one full
period spans 2.18 of it, and the wave's ends reach 1.58 of it either side of
centre. generate_icons.py consumes these numbers for both the vector drawables
and the raster PNGs.
"""

import math

# ---- geometry, in the 100x100 design space -------------------------------
CX, CY = 50.0, 50.0          # ring centre
R = 27.5                     # ring radius (to the middle of its stroke)
STROKE = R * 0.250           # ring and wave share one weight
AMPLITUDE = R * 0.350        # the wave's crest and trough, from the centre line
PERIOD = R * 2.180           # one full sine across the ring
TIP = R * 1.580              # how far past centre the wave runs, either side

# The wave, sampled as a true sine. Twelve samples is eight more than the eye can
# tell apart once they are turned into cubic segments, and it keeps the path data
# short enough to read in a diff.
_SAMPLES = 12


def wave_points():
    """The centre line of the strike, left tip to right tip, in design units."""
    pts = []
    for i in range(_SAMPLES + 1):
        x = CX - TIP + (2 * TIP) * i / _SAMPLES
        y = CY + AMPLITUDE * math.sin(2 * math.pi * (x - CX) / PERIOD)
        pts.append((x, y))
    return pts


def bounds():
    """The mark's tight bounding box in the design space, as (x0, y0, x1, y1).

    The widest part is the strike, not the ring: its tips run out past the circle.
    Callers crop to this when the mark has to fill a box that is not square —
    the wide in-app wordmark, most of all.
    """
    left = CX - TIP - STROKE / 2
    right = CX + TIP + STROKE / 2
    top = CY - R - STROKE / 2
    bottom = CY + R + STROKE / 2
    return left, top, right, bottom


def _catmull_rom_to_bezier(points):
    """Smooth path through `points`, as cubic Bezier segments (c1, c2, end)."""
    pts = [points[0]] + list(points) + [points[-1]]
    segs = []
    for i in range(1, len(pts) - 2):
        p0, p1, p2, p3 = pts[i - 1], pts[i], pts[i + 1], pts[i + 2]
        c1 = (p1[0] + (p2[0] - p0[0]) / 6.0, p1[1] + (p2[1] - p0[1]) / 6.0)
        c2 = (p2[0] - (p3[0] - p1[0]) / 6.0, p2[1] - (p3[1] - p1[1]) / 6.0)
        segs.append((c1, c2, p2))
    return segs


def _fmt(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return s if s and s != "-0" else "0"


def _map(p, scale, dx, dy):
    return (p[0] * scale + dx, p[1] * scale + dy)


def wave_path_data(scale=1.0, dx=0.0, dy=0.0):
    """The sine strike as SVG / VectorDrawable path data (an open, stroked path)."""
    pts = [_map(p, scale, dx, dy) for p in wave_points()]
    d = [f"M {_fmt(pts[0][0])},{_fmt(pts[0][1])}"]
    for c1, c2, end in _catmull_rom_to_bezier(pts):
        d.append(
            f"C {_fmt(c1[0])},{_fmt(c1[1])} {_fmt(c2[0])},{_fmt(c2[1])} "
            f"{_fmt(end[0])},{_fmt(end[1])}"
        )
    return " ".join(d)


def ring_path_data(scale=1.0, dx=0.0, dy=0.0):
    """The ring as a closed stroked path — two arcs are all a circle needs."""
    x, y = _map((CX, CY), scale, dx, dy)
    rr = R * scale
    return (
        f"M {_fmt(x - rr)},{_fmt(y)} "
        f"a {_fmt(rr)},{_fmt(rr)} 0 1,0 {_fmt(2 * rr)},0 "
        f"a {_fmt(rr)},{_fmt(rr)} 0 1,0 {_fmt(-2 * rr)},0 Z"
    )


def svg(size=512, colour="#FFFFFF", background=None, fit=1.0):
    """A standalone SVG of the mark.

    [fit] scales the drawing inside the canvas — 1.0 leaves the design space
    filling it edge to edge, and smaller values buy the margin an adaptive icon's
    safe zone wants.
    """
    scale = (size / 100.0) * fit
    off = (size - 100.0 * scale) / 2.0
    w = _fmt(STROKE * scale)
    bg = f'<rect width="{size}" height="{size}" fill="{background}"/>' if background else ""
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" \
viewBox="0 0 {size} {size}" fill="none">
{bg}<g stroke="{colour}" stroke-width="{w}" stroke-linecap="round" stroke-linejoin="round" fill="none">
<path d="{ring_path_data(scale, off, off)}"/>
<path d="{wave_path_data(scale, off, off)}"/>
</g>
</svg>
"""


# ---- Android vector drawable -------------------------------------------------
#
# A VectorDrawable cannot read CSS, so the same geometry is emitted as explicit
# XML attributes. `pathData` is shared with the SVG above, so the two can never
# drift into different drawings.

def vector_drawable(vw, vh, scale, dx, dy, colour="#FFFFFFFF", tint=None):
    """A VectorDrawable whose content is the mark, transformed by (scale, dx, dy).

    A VectorDrawable cannot read CSS, so the geometry is emitted as explicit XML
    attributes. `pathData` is the same builder the SVG uses, so the two can never
    drift into different drawings.
    """
    stroke = _fmt(STROKE * scale)
    ring = ring_path_data(scale, dx, dy)
    wave = wave_path_data(scale, dx, dy)
    tint_attr = f'\n    android:tint="{tint}"' if tint else ""
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/generate_icons.py from tools/lossless_mark.py — edit that, not this. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{_fmt(vw)}dp"
    android:height="{_fmt(vh)}dp"
    android:viewportWidth="{_fmt(vw)}"
    android:viewportHeight="{_fmt(vh)}"{tint_attr}>
    <path
        android:pathData="{ring}"
        android:strokeColor="{colour}"
        android:strokeWidth="{stroke}"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:fillColor="#00000000" />
    <path
        android:pathData="{wave}"
        android:strokeColor="{colour}"
        android:strokeWidth="{stroke}"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:fillColor="#00000000" />
</vector>
"""


def adaptive_foreground(colour="#FFFFFFFF", viewport=108.0, span=0.52):
    """The mark sized for an adaptive icon: [span] of the tile, tips to tips.

    The tile is 108dp but a launcher may mask it down to the central 72dp, so the
    drawing is kept well inside that — see span.
    """
    drawn = (2 * TIP + STROKE) * (viewport / 100.0)
    scale = (viewport / 100.0) * (span * viewport / drawn)
    return vector_drawable(viewport, viewport, scale,
                           (viewport - 100.0 * scale) / 2.0,
                           (viewport - 100.0 * scale) / 2.0, colour)


def tight_logo(colour="#FFFFFFFF"):
    """The mark cropped to its own bounds — what the in-app wordmark draws.

    The crop is 93.78 x 61.88 design units, an aspect of 1.515. Call sites that
    sized the old wide wordmark by its aspect keep working with that number.
    """
    x0, y0, x1, y1 = bounds()
    return vector_drawable(x1 - x0, y1 - y0, 1.0, -x0, -y0, colour)
