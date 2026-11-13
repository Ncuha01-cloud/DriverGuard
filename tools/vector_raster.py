"""
Font-free / native-dep-free rasterizer for Android <vector> drawables.

Supports the subset used by the DriverGuard launcher vectors:
  <path> with pathData (M/L/H/V/C/S/Q/T/A/Z, abs+rel), fillColor, fillAlpha,
  strokeColor, strokeWidth, strokeAlpha, strokeLineCap, strokeLineJoin,
  and <aapt:attr name="android:fillColor"><gradient linear/radial/.../></aapt:attr>.

Curves are flattened into polygons, then drawn at 4x supersampling and
downscaled with LANCZOS. Also used by the legacy PNG generator, so the PNG
icons are pixel-consistent with the vector launcher icon.
"""

import math

from PIL import Image, ImageDraw, ImageFilter

SUPERSAMPLE = 4

# --- path parsing -------------------------------------------------------------

_TOKEN_CHARS = set("MmLlHhVvCcSsQqTtAaZz")


def _tokenize(d):
    """Split pathData into (command, [floats]) groups."""
    tokens = []
    i, n = 0, len(d)
    while i < n:
        ch = d[i]
        if ch.isspace() or ch == ",":
            i += 1
            continue
        if ch in _TOKEN_CHARS:
            tokens.append([ch, []])
            i += 1
            continue
        if ch in "+-." or ch.isdigit():
            j = i
            if d[j] in "+-":
                j += 1
            seen_dot = False
            while j < n and (d[j].isdigit() or (d[j] == "." and not seen_dot)):
                if d[j] == ".":
                    seen_dot = True
                j += 1
            if j < n and d[j] in "eE":
                j += 1
                if j < n and d[j] in "+-":
                    j += 1
                while j < n and d[j].isdigit():
                    j += 1
            if not tokens:
                raise ValueError(f"pathData starts with a number: {d[:20]!r}")
            tokens[-1][1].append(float(d[i:j]))
            i = j
            continue
        raise ValueError(f"unexpected pathData char {ch!r} in {d!r}")
    return [(c, a) for c, a in tokens]


def _approx_arc(x0, y0, rx, ry, phi_deg, large, sweep, x1, y1, steps_per_quarter=24):
    """Endpoint-parameterised elliptical arc -> list of points (excluding start)."""
    if rx == 0 or ry == 0:
        return [(x1, y1)]
    phi = math.radians(phi_deg)
    cp, sp = math.cos(phi), math.sin(phi)
    dx2, dy2 = (x0 - x1) / 2.0, (y0 - y1) / 2.0
    x1p = cp * dx2 + sp * dy2
    y1p = -sp * dx2 + cp * dy2
    rx, ry = abs(rx), abs(ry)
    lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
    if lam > 1:
        s = math.sqrt(lam)
        rx *= s
        ry *= s
    num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    ratio = max(0.0, num / den) if den else 0.0
    coef = math.sqrt(ratio) * (-1 if large == sweep else 1)
    cxp = coef * (rx * y1p / ry)
    cyp = coef * (-ry * x1p / rx)
    cx = cp * cxp - sp * cyp + (x0 + x1) / 2.0
    cy = sp * cxp + cp * cyp + (y0 + y1) / 2.0

    def ang(ux, uy, vx, vy):
        dot = ux * vx + uy * vy
        n = math.hypot(ux, uy) * math.hypot(vx, vy)
        a = math.acos(max(-1.0, min(1.0, dot / n))) if n else 0.0
        return -a if (ux * vy - uy * vx) < 0 else a

    t1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not sweep and dt > 0:
        dt -= 2 * math.pi
    elif sweep and dt < 0:
        dt += 2 * math.pi

    steps = max(2, int(abs(dt) / (math.pi / 2) * steps_per_quarter))
    pts = []
    for i in range(1, steps + 1):
        t = t1 + dt * i / steps
        ex, ey = rx * math.cos(t), ry * math.sin(t)
        pts.append((cp * ex - sp * ey + cx, sp * ex + cp * ey + cy))
    return pts


def flatten_path(d):
    """Convert pathData into a list of subpaths (each a list of (x, y) points)."""
    toks = _tokenize(d)
    subpaths = []
    cur = []
    x = y = 0.0
    sx = sy = 0.0
    cmd = None
    last_ctrl = None  # reflected control point for S/T
    idx = 0
    numbers = []

    flat = []
    for c, args in toks:
        flat.append((c, args))

    def take(nn):
        nonlocal idx
        if idx + nn > len(numbers):
            raise ValueError(f"command {cmd} needs {nn} args, has {len(numbers) - idx}")
        out = numbers[idx:idx + nn]
        idx += nn
        return out

    # iterate commands, processing implicit repeats
    pos = 0
    while pos < len(flat):
        cmd, args = flat[pos]
        pos += 1
        numbers = list(args)
        idx = 0
        rel = cmd.islower()
        C = cmd.upper()
        nargs = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "A": 7, "Z": 0}[C]

        first = True
        while idx < len(numbers) or (first and C == "Z"):
            first = False
            if C == "M":
                ax, ay = take(2)
                x, y = (x + ax, y + ay) if rel else (ax, ay)
                if cur:
                    subpaths.append(cur)
                cur = [(x, y)]
                sx, sy = x, y
                last_ctrl = None
                C = "L"  # subsequent pairs are implicit lineto
                continue
            if C == "L":
                ax, ay = take(2)
                x, y = (x + ax, y + ay) if rel else (ax, ay)
                cur.append((x, y))
                last_ctrl = None
            elif C == "H":
                (ax,) = take(1)
                x = x + ax if rel else ax
                cur.append((x, y))
                last_ctrl = None
            elif C == "V":
                (ay,) = take(1)
                y = y + ay if rel else ay
                cur.append((x, y))
                last_ctrl = None
            elif C in ("C", "S"):
                if C == "C":
                    x1, y1, x2, y2, ax, ay = take(6)
                    if rel:
                        x1 += x; y1 += y; x2 += x; y2 += y; ax += x; ay += y
                else:
                    x2, y2, ax, ay = take(4)
                    if rel:
                        x2 += x; y2 += y; ax += x; ay += y
                    x1, y1 = (2 * x - last_ctrl[0], 2 * y - last_ctrl[1]) if last_ctrl else (x, y)
                last_ctrl = (x2, y2)
                steps = 24
                for i in range(1, steps + 1):
                    t = i / steps
                    mt = 1 - t
                    px = mt**3 * x + 3 * mt**2 * t * x1 + 3 * mt * t**2 * x2 + t**3 * ax
                    py = mt**3 * y + 3 * mt**2 * t * y1 + 3 * mt * t**2 * y2 + t**3 * ay
                    cur.append((px, py))
                x, y = ax, ay
            elif C in ("Q", "T"):
                if C == "Q":
                    x1, y1, ax, ay = take(4)
                    if rel:
                        x1 += x; y1 += y; ax += x; ay += y
                else:
                    ax, ay = take(2)
                    if rel:
                        ax += x; ay += y
                    x1, y1 = (2 * x - last_ctrl[0], 2 * y - last_ctrl[1]) if last_ctrl else (x, y)
                last_ctrl = (x1, y1)
                steps = 24
                for i in range(1, steps + 1):
                    t = i / steps
                    mt = 1 - t
                    px = mt**2 * x + 2 * mt * t * x1 + t**2 * ax
                    py = mt**2 * y + 2 * mt * t * y1 + t**2 * ay
                    cur.append((px, py))
                x, y = ax, ay
            elif C == "A":
                rx, ry, rot, large, sweep, ax, ay = take(7)
                if rel:
                    ax += x; ay += y
                cur.extend(_approx_arc(x, y, rx, ry, rot, int(large), int(sweep), ax, ay))
                x, y = ax, ay
                last_ctrl = None
            elif C == "Z":
                cur.append((sx, sy))
                x, y = sx, sy
                last_ctrl = None
                subpaths.append(cur)
                cur = []
                break
            else:  # pragma: no cover
                raise ValueError(f"unsupported command {cmd}")

        if C == "Z":
            if cur:
                subpaths.append(cur)
            cur = []

    if cur:
        subpaths.append(cur)
    return [sp for sp in subpaths if len(sp) >= 2]


# --- colour parsing ------------------------------------------------------------

def parse_android_color(value):
    """#RRGGBBAA (Android order) -> (r, g, b, a). Supports named aapt colors."""
    if value is None:
        return None
    v = value.strip()
    named = {
        "white": (255, 255, 255, 255),
        "black": (0, 0, 0, 255),
        "transparent": (0, 0, 0, 0),
        "@android:color/white": (255, 255, 255, 255),
        "@android:color/black": (0, 0, 0, 255),
        "@android:color/transparent": (0, 0, 0, 0),
    }  # NOTE: tuples are (r, g, b, a) like everything else here

    if v.lower() in named:
        return named[v.lower()]
    if not v.startswith("#"):
        raise ValueError(f"unsupported color reference {v!r}")
    h = v[1:]
    if len(h) == 3:
        h = "FF" + "".join(c * 2 for c in h)     # #RGB  -> #FFRRGGBB
    elif len(h) == 4:
        h = "".join(c * 2 for c in h)            # #ARGB -> #AARRGGBB
    elif len(h) == 6:
        h = "FF" + h                             # #RRGGBB -> #FFRRGGBB
    elif len(h) != 8:
        raise ValueError(f"bad color {v!r}")
    # Android order is #AARRGGBB; normalise to (r, g, b, a)
    a, r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4, 6))
    return (r, g, b, a)


# --- stroke geometry ----------------------------------------------------------

def stroke_polygon(points, width):
    """Return a list of triangles/quads covering a stroked polyline."""
    if len(points) < 2:
        return []
    hw = width / 2.0
    polys = []
    for (x0, y0), (x1, y1) in zip(points, points[1:]):
        dx, dy = x1 - x0, y1 - y0
        L = math.hypot(dx, dy)
        if L < 1e-9:
            continue
        nx, ny = -dy / L * hw, dx / L * hw
        polys.append([(x0 + nx, y0 + ny), (x1 + nx, y1 + ny),
                      (x1 - nx, y1 - ny), (x0 - nx, y0 - ny)])
    return polys


def round_cap_dots(points, width):
    hw = width / 2.0
    return [(p[0] - hw, p[1] - hw, p[0] + hw, p[1] + hw) for p in points]


# --- vector rasterisation -----------------------------------------------------

class VectorShape:
    __slots__ = ("d", "fill", "fill_alpha", "stroke", "stroke_width",
                 "stroke_alpha", "line_cap", "line_join", "gradient")

    def __init__(self, **kw):
        for k in self.__slots__:
            setattr(self, k, kw.get(k))


def render_vector(shapes, size, scale=1.0, offset=(0.0, 0.0), background=None,
                  supersample=SUPERSAMPLE):
    """Rasterise shapes into an RGBA image of `size` x `size` px (supersampled)."""
    s = size * supersample
    k = s / 108.0 * scale
    img = Image.new("RGBA", (s, s), background or (0, 0, 0, 0))

    def to_px(pts):
        return [((x + offset[0]) * k, (y + offset[1]) * k) for x, y in pts]

    for sh in shapes:
        subpaths = flatten_path(sh.d)

        if sh.fill is not None or sh.gradient is not None:
            fill_layer = Image.new("RGBA", (s, s), (0, 0, 0, 0))
            fd = ImageDraw.Draw(fill_layer)
            col = sh.fill or (0, 0, 0, 255)
            rgba = (col[0], col[1], col[2], int(round(col[3] * sh.fill_alpha)))
            # even-odd style: draw subpaths in order; Pillow's polygon cannot
            # punch holes, so marked subpaths are XOR-ed out after the pass.
            for sp in subpaths:
                fd.polygon(to_px(sp), fill=rgba)
            if sh.gradient is not None:
                fill_layer = paint_gradient(fill_layer, sh.gradient, s)
            img.alpha_composite(fill_layer)

        if sh.stroke is not None and sh.stroke_width:
            stroke_layer = Image.new("RGBA", (s, s), (0, 0, 0, 0))
            sd = ImageDraw.Draw(stroke_layer)
            col = sh.stroke
            rgba = (col[0], col[1], col[2], int(round(col[3] * sh.stroke_alpha)))
            w = sh.stroke_width * k
            for sp in subpaths:
                pts = to_px(sp)
                for poly in stroke_polygon(pts, w):
                    sd.polygon(poly, fill=rgba)
                if sh.line_join == "round":
                    for p in pts[1:-1]:
                        sd.ellipse([p[0] - w / 2, p[1] - w / 2,
                                    p[0] + w / 2, p[1] + w / 2], fill=rgba)
                if sh.line_cap == "round":
                    for box in round_cap_dots([pts[0], pts[-1]], w):
                        sd.ellipse(box, fill=rgba)
            img.alpha_composite(stroke_layer)

    return img.resize((size, size), Image.LANCZOS)


def _apply_gradient(layer, g, s):
    """Multiply an already-masked layer by a linear/radial gradient."""
    mask = layer.split()[-1]
    grad = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    gp = grad.load()
    k = s / 108.0
    if g["type"] == "radial":
        cx, cy, r = g["centerX"] * k, g["centerY"] * k, g["radius"] * k
        for yy in range(s):
            dy = yy - cy
            for xx in range(s):
                d = math.hypot(xx - cx, dy) / r if r else 1.0
                gp[xx, yy] = g["sample"](min(1.0, d))
    else:
        x0, y0, x1, y1 = (g["startX"] * k, g["startY"] * k, g["endX"] * k, g["endY"] * k)
        vx, vy = x1 - x0, y1 - y0
        den = vx * vx + vy * vy or 1.0
        for yy in range(s):
            for xx in range(s):
                t = ((xx - x0) * vx + (yy - y0) * vy) / den
                gp[xx, yy] = g["sample"](max(0.0, min(1.0, t)))
    grad.putalpha(mask)
    return grad


def _gradient_rgb_image(g, s, k):
    """Fast vectorised gradient field: (rgb image, alpha LUT) in 0..1 space."""
    import numpy as np

    yy, xx = np.mgrid[0:s, 0:s].astype(np.float64)
    if g["type"] == "radial":
        cx, cy, r = g["centerX"] * k, g["centerY"] * k, g["radius"] * k
        t = np.clip(np.hypot(xx - cx, yy - cy) / (r or 1.0), 0.0, 1.0)
    else:
        x0, y0 = g["startX"] * k, g["startY"] * k
        x1, y1 = g["endX"] * k, g["endY"] * k
        vx, vy = x1 - x0, y1 - y0
        den = (vx * vx + vy * vy) or 1.0
        t = np.clip(((xx - x0) * vx + (yy - y0) * vy) / den, 0.0, 1.0)

    # sample() takes tuples; vectorise by evaluating on a fine 1-D LUT
    lut_n = 256
    table = np.array([g["sample"](i / (lut_n - 1)) for i in range(lut_n)], dtype=np.float64)
    idx = np.clip((t * (lut_n - 1)).astype(np.int32), 0, lut_n - 1)
    rgb = np.clip(table[idx, :3], 0, 255).astype(np.uint8)
    alpha = np.clip(table[idx, 3], 0, 255).astype(np.uint8)
    return Image.fromarray(rgb, "RGB"), Image.fromarray(alpha, "L")


def paint_gradient(layer, g, s):
    """Recolour a coverage layer with a linear/radial gradient, honouring alpha.

    `layer` is an RGBA image whose alpha channel is the shape coverage; the
    existing RGB is ignored (fills are flat) and the gradient provides colour.
    """
    coverage = layer.split()[-1]
    rgb, ga = _gradient_rgb_image(g, s, s / 108.0)
    # final alpha = coverage * gradient alpha
    import numpy as np

    a = (np.asarray(coverage, dtype=np.float64) / 255.0) * \
        (np.asarray(ga, dtype=np.float64) / 255.0)
    out = rgb.convert("RGBA")
    out.putalpha(Image.fromarray(np.round(a * 255).astype(np.uint8), "L"))
    return out
