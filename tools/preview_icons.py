"""QA renderer: Android launcher vector XMLs -> PNG previews (no native deps)."""

import os
import xml.etree.ElementTree as ET

from PIL import Image

from vector_raster import VectorShape, parse_android_color, render_vector

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RES = os.path.join(ROOT, "app", "src", "main", "res")
OUT = os.path.join(ROOT, "build", "icon-preview")
A = "{http://schemas.android.com/apk/res/android}"
AAPT = "{http://schemas.android.com/aapt}"


def load_shapes(path):
    root = ET.parse(path).getroot()
    vw = float(root.get(A + "viewportWidth"))
    vh = float(root.get(A + "viewportHeight"))
    shapes = []
    for p in root.findall("path"):
        fill = p.get(A + "fillColor")
        gradient = None
        for attr in p.findall(AAPT + "attr"):
            if attr.get("name") == "android:fillColor":
                g = attr.find("gradient")
                if g is not None:
                    gtype = g.get(A + "type", "linear")
                    stops = []
                    for key, off in (("startColor", 0.0), ("centerColor", 0.5), ("endColor", 1.0)):
                        c = g.get(A + key)
                        if c:
                            stops.append((off, parse_android_color(c)))
                    if len(stops) == 2:  # start/end only -> remap
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

                    sample = make_sampler(stops)

                    if gtype == "radial":
                        gradient = dict(type="radial",
                                        centerX=float(g.get(A + "centerX")),
                                        centerY=float(g.get(A + "centerY")),
                                        radius=float(g.get(A + "gradientRadius")),
                                        sample=sample)
                    else:
                        gradient = dict(type="linear",
                                        startX=float(g.get(A + "startX")),
                                        startY=float(g.get(A + "startY")),
                                        endX=float(g.get(A + "endX")),
                                        endY=float(g.get(A + "endY")),
                                        sample=sample)
        shapes.append(VectorShape(
            d=p.get(A + "pathData"),
            fill=parse_android_color(fill) if fill else None,
            fill_alpha=float(p.get(A + "fillAlpha", 1)),
            gradient=gradient,
            stroke=parse_android_color(p.get(A + "strokeColor")) if p.get(A + "strokeColor") else None,
            stroke_alpha=float(p.get(A + "strokeAlpha", 1)),
            stroke_width=float(p.get(A + "strokeWidth", 0)),
            line_cap=p.get(A + "strokeLineCap", "butt"),
            line_join=p.get(A + "strokeLineJoin", "miter"),
        ))
    return shapes, vw, vh


def main():
    os.makedirs(OUT, exist_ok=True)
    bg, vw, vh = load_shapes(os.path.join(RES, "drawable", "ic_launcher_background.xml"))
    fg, _, _ = load_shapes(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"))

    px = 432
    bg_img = render_vector(bg, px)
    fg_img = render_vector(fg, px)
    comp = Image.alpha_composite(bg_img, fg_img)
    comp.save(os.path.join(OUT, "adaptive_full.png"))

    inset = int(px * 18.0 / 108.0)  # adaptive icon: centre 72dp of 108dp is visible
    visible = comp.crop((inset, inset, px - inset, px - inset))
    visible.save(os.path.join(OUT, "adaptive_visible_72.png"))
    visible.resize((48 * 3, 48 * 3), Image.LANCZOS).save(os.path.join(OUT, "adaptive_at_48dp_x3.png"))

    # circle crop for the round icon
    from PIL import ImageDraw
    circ = Image.new("L", (px, px), 0)
    ImageDraw.Draw(circ).ellipse([0, 0, px - 1, px - 1], fill=255)
    round_comp = comp.copy()
    round_comp.putalpha(circ)
    round_comp.save(os.path.join(OUT, "adaptive_round_full.png"))
    print("preview ->", OUT)


if __name__ == "__main__":
    main()
