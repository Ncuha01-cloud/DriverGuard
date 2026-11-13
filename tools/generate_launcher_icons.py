"""
DriverGuard legacy launcher icon rasterizer.

Draws the legacy PNG mipmaps straight from the SAME vector drawables that back
the adaptive icon (res/drawable/ic_launcher_background.xml +
ic_launcher_foreground.xml), so legacy and adaptive icons can never drift.

Rasterisation is font-free and native-dep-free: pathData is flattened into
polygons and drawn with Pillow at 4x supersampling, then downscaled with
LANCZOS for antialiasing.

Outputs: res/mipmap-<dpi>/ic_launcher.png        (rounded-square plate)
         res/mipmap-<dpi>/ic_launcher_round.png  (circular mask)
"""

import os
import sys
import xml.etree.ElementTree as ET

from PIL import Image, ImageChops, ImageDraw

from vector_raster import VectorShape, parse_android_color, render_vector

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RES = os.path.join(ROOT, "app", "src", "main", "res")

DENSITIES = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]

# The adaptive icon exposes the centre 72dp of its 108dp canvas. Legacy PNGs
# should show the same visual: render the full 108dp canvas at 1.5x the target
# size, then crop the centre 72dp window -- exactly what a launcher does.
LEGACY_SCALE = 108.0 / 72.0

A = "{http://schemas.android.com/apk/res/android}"
AAPT = "{http://schemas.android.com/aapt}"


def load_shapes(path):
    root = ET.parse(path).getroot()
    shapes = []
    for p in root.findall("path"):
        fill = p.get(A + "fillColor")
        gradient = None
        for attr in p.findall(AAPT + "attr"):
            if attr.get("name") == "android:fillColor":
                g = attr.find("gradient")
                if g is None:
                    continue
                stops = []
                for key, off in (("startColor", 0.0), ("centerColor", 0.5),
                                 ("endColor", 1.0)):
                    c = g.get(A + key)
                    if c:
                        stops.append((off, parse_android_color(c)))
                if len(stops) == 2:
                    stops = [(0.0, stops[0][1]), (1.0, stops[1][1])]

                def make_sampler(stops):
                    stops = list(stops)

                    def sample(t):
                        for (o0, c0), (o1, c1) in zip(stops, stops[1:]):
                            if o0 <= t <= o1:
                                f = (t - o0) / (o1 - o0) if o1 > o0 else 0.0
                                return tuple(int(round(c0[i] + (c1[i] - c0[i]) * f))
                                             for i in range(4))
                        return stops[-1][1]

                    return sample

                if g.get(A + "type", "linear") == "radial":
                    gradient = dict(type="radial",
                                    centerX=float(g.get(A + "centerX")),
                                    centerY=float(g.get(A + "centerY")),
                                    radius=float(g.get(A + "gradientRadius")),
                                    sample=make_sampler(stops))
                else:
                    gradient = dict(type="linear",
                                    startX=float(g.get(A + "startX")),
                                    startY=float(g.get(A + "startY")),
                                    endX=float(g.get(A + "endX")),
                                    endY=float(g.get(A + "endY")),
                                    sample=make_sampler(stops))
        shapes.append(VectorShape(
            d=p.get(A + "pathData"),
            fill=parse_android_color(fill) if fill else None,
            fill_alpha=float(p.get(A + "fillAlpha", 1)),
            gradient=gradient,
            stroke=parse_android_color(p.get(A + "strokeColor"))
            if p.get(A + "strokeColor") else None,
            stroke_alpha=float(p.get(A + "strokeAlpha", 1)),
            stroke_width=float(p.get(A + "strokeWidth", 0)),
            line_cap=p.get(A + "strokeLineCap", "butt"),
            line_join=p.get(A + "strokeLineJoin", "miter"),
        ))
    return shapes


def rounded_square_mask(size, radius_ratio=14.0 / 72.0):
    """Rounded square silhouette, corner radius relative to the visible 72dp box."""
    mask = Image.new("L", (size, size), 0)
    r = size * radius_ratio
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=r, fill=255)
    return mask


def round_mask(size):
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
    return mask


def build_icon(bg, fg, size, mask):
    """Composite background + foreground at `size`, cropped to the 72dp window."""
    canvas_px = int(round(size * LEGACY_SCALE))
    bg_img = render_vector(bg, canvas_px)
    fg_img = render_vector(fg, canvas_px)
    comp = Image.alpha_composite(bg_img, fg_img)

    # crop to the centre 72dp of 108dp (safe zone a launcher shows)
    inset = int(round(canvas_px * (108.0 - 72.0) / 2.0 / 108.0))
    comp = comp.crop((inset, inset, canvas_px - inset, canvas_px - inset))
    if comp.size != (size, size):
        comp = comp.resize((size, size), Image.LANCZOS)

    # apply silhouette
    alpha = ImageChops.multiply(comp.split()[-1],
                                mask.resize((size, size), Image.LANCZOS))
    comp.putalpha(alpha)
    return comp


def main():
    bg = load_shapes(os.path.join(RES, "drawable", "ic_launcher_background.xml"))
    fg = load_shapes(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"))

    written = []
    for dpi, size in DENSITIES:
        out_dir = os.path.join(RES, "mipmap-" + dpi)
        os.makedirs(out_dir, exist_ok=True)

        # render at 4x then downscale -> supersampled antialiasing
        big = size * 4
        sq = build_icon(bg, fg, big, rounded_square_mask(big))
        p1 = os.path.join(out_dir, "ic_launcher.png")
        sq.resize((size, size), Image.LANCZOS).save(p1, "PNG", optimize=True)

        rd = build_icon(bg, fg, big, round_mask(big))
        p2 = os.path.join(out_dir, "ic_launcher_round.png")
        rd.resize((size, size), Image.LANCZOS).save(p2, "PNG", optimize=True)

        written += [(p1, size), (p2, size)]
    return written


if __name__ == "__main__":
    for path, size in main():
        print(f"{size}x{size}\t{os.path.getsize(path):>6} B\t{path}")
    sys.exit(0)
