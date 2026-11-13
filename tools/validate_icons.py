"""
Validation for the DriverGuard launcher icon resources.

Checks (no Android SDK / gradle needed):
  1. every XML is well-formed
  2. vector drawables declare 108dp width/height and 108x108 viewport
  3. every pathData parses into subpaths and stays inside the 108 canvas
  4. foreground artwork stays inside the adaptive-icon safe zone (21..87)
  5. every android:color value is a legal #AARRGGBB / #RRGGBB / named color
  6. adaptive-icon XMLs reference drawables that actually exist
  7. PNG mipmaps exist, are the right pixel size, RGBA, and non-empty
"""

import glob
import os
import sys
import xml.etree.ElementTree as ET

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RES = os.path.join(ROOT, "app", "src", "main", "res")
A = "{http://schemas.android.com/apk/res/android}"
AAPT = "{http://schemas.android.com/aapt}"

sys.path.insert(0, HERE)
from vector_raster import flatten_path, parse_android_color  # noqa: E402

SAFE_MIN, SAFE_MAX = 21.0, 87.0
EXPECTED_SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

problems = []
checks = 0


def check(cond, msg):
    global checks
    checks += 1
    if not cond:
        problems.append(msg)
    return cond


def check_vector(path, strict_safe_zone=False):
    name = os.path.relpath(path, RES)
    root = ET.parse(path).getroot()

    check(root.tag == "vector", f"{name}: root is not <vector>")
    check(root.get(A + "width") == "108dp", f"{name}: width must be 108dp")
    check(root.get(A + "height") == "108dp", f"{name}: height must be 108dp")
    vw = float(root.get(A + "viewportWidth"))
    vh = float(root.get(A + "viewportHeight"))
    check(vw == 108 and vh == 108, f"{name}: viewport must be 108x108, got {vw}x{vh}")

    paths = root.findall("path")
    check(len(paths) > 0, f"{name}: no <path> elements")

    for i, p in enumerate(paths):
        d = p.get(A + "pathData")
        if not check(d, f"{name}[{i}]: missing pathData"):
            continue
        try:
            subpaths = flatten_path(d)
        except Exception as e:  # noqa: BLE001
            problems.append(f"{name}[{i}]: pathData failed to parse: {e}")
            checks += 1
            continue
        check(len(subpaths) > 0, f"{name}[{i}]: pathData produced no subpaths")

        # bounds check (stroke half-width included)
        sw = float(p.get(A + "strokeWidth", 0) or 0)
        pad = sw / 2.0
        xs = [pt[0] for sp in subpaths for pt in sp]
        ys = [pt[1] for sp in subpaths for pt in sp]
        x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
        check(-0.01 <= x0 and x1 <= 108.01, f"{name}[{i}]: x out of canvas {x0:.1f}..{x1:.1f}")
        check(-0.01 <= y0 and y1 <= 108.01, f"{name}[{i}]: y out of canvas {y0:.1f}..{y1:.1f}")

        if strict_safe_zone:
            check(x0 - pad >= SAFE_MIN - 0.01, f"{name}[{i}]: left edge {x0 - pad:.1f} < {SAFE_MIN}")
            check(x1 + pad <= SAFE_MAX + 0.01, f"{name}[{i}]: right edge {x1 + pad:.1f} > {SAFE_MAX}")
            check(y0 - pad >= SAFE_MIN - 0.01, f"{name}[{i}]: top edge {y0 - pad:.1f} < {SAFE_MIN}")
            check(y1 + pad <= SAFE_MAX + 0.01, f"{name}[{i}]: bottom edge {y1 + pad:.1f} > {SAFE_MAX}")

        # colour attributes
        for attr in ("fillColor", "strokeColor"):
            v = p.get(A + attr)
            if v is None:
                continue
            try:
                parse_android_color(v)
            except ValueError as e:
                problems.append(f"{name}[{i}]: {attr} invalid: {e}")

        # gradient children: every colour must be valid
        for gattr in p.findall(AAPT + "attr"):
            check(gattr.get("name") == "android:fillColor",
                  f"{name}[{i}]: unexpected aapt:attr {gattr.get('name')}")
            g = gattr.find("gradient")
            if check(g is not None, f"{name}[{i}]: aapt:attr has no <gradient>"):
                ncolors = 0
                for key in ("startColor", "centerColor", "endColor"):
                    c = g.get(A + key)
                    if c is None:
                        continue
                    ncolors += 1
                    try:
                        parse_android_color(c)
                    except ValueError as e:
                        problems.append(f"{name}[{i}]: gradient {key} invalid: {e}")
                check(ncolors >= 2, f"{name}[{i}]: gradient needs >= 2 stops")
                if g.get(A + "type") == "radial":
                    check(g.get(A + "gradientRadius"), f"{name}[{i}]: radial gradient needs radius")

    return name


def check_adaptive(path):
    name = os.path.relpath(path, RES)
    root = ET.parse(path).getroot()
    check(root.tag == "adaptive-icon", f"{name}: root is not <adaptive-icon>")
    for tag in ("background", "foreground"):
        node = root.find(tag)
        if check(node is not None, f"{name}: missing <{tag}>"):
            ref = node.get(A + "drawable")
            if check(ref and ref.startswith("@drawable/"),
                     f"{name}: <{tag}> must reference @drawable/*"):
                target = os.path.join(RES, "drawable", ref.split("/", 1)[1] + ".xml")
                check(os.path.isfile(target), f"{name}: {ref} does not resolve to {target}")
    mono = root.find("monochrome")
    if mono is not None:
        ref = mono.get(A + "drawable")
        target = os.path.join(RES, "drawable", ref.split("/", 1)[1] + ".xml")
        check(os.path.isfile(target), f"{name}: {ref} does not resolve")
    return name


def check_pngs():
    n = 0
    for dpi, size in EXPECTED_SIZES.items():
        for base in ("ic_launcher.png", "ic_launcher_round.png"):
            rel = f"mipmap-{dpi}/{base}"
            p = os.path.join(RES, "mipmap-" + dpi, base)
            if not check(os.path.isfile(p), f"{rel}: missing"):
                continue
            n += 1
            with Image.open(p) as im:
                check(im.size == (size, size),
                      f"{rel}: expected {size}x{size}, got {im.size}")
                check(im.mode in ("RGBA", "LA", "P"),
                      f"{rel}: needs alpha channel, got {im.mode}")
                im = im.convert("RGBA")
                alphas = im.getchannel("A").getextrema()
                check(alphas[0] == 0 and alphas[1] == 255,
                      f"{rel}: alpha range {alphas} (want 0..255 for masked edges)")
                # centre should be the dark pupil, corner transparent
                cx, cy = size // 2, size // 2
                cc = im.getpixel((cx, cy))
                check(cc[3] > 200, f"{rel}: centre pixel not opaque ({cc})")
                check(im.getpixel((0, 0))[3] == 0, f"{rel}: corner not transparent")
                # some cyan must be present
                px = list(im.get_flattened_data()) if hasattr(im, "get_flattened_data") \
                    else list(im.getdata())
                cyan = sum(1 for v in px if v[3] > 128 and v[1] > 120 and v[2] > 140)
                check(cyan > size, f"{rel}: almost no cyan pixels ({cyan})")
    return n


def main():
    check_vector(os.path.join(RES, "drawable", "ic_launcher_background.xml"))
    check_vector(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"),
                 strict_safe_zone=True)
    check_adaptive(os.path.join(RES, "mipmap-anydpi-v26", "ic_launcher.xml"))
    check_adaptive(os.path.join(RES, "mipmap-anydpi-v26", "ic_launcher_round.xml"))
    n_png = check_pngs()

    print(f"ran {checks} checks over 4 XML files and {n_png} PNGs")
    if problems:
        print("PROBLEMS:")
        for p in problems:
            print("  -", p)
        return 1
    print("ALL CHECKS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
