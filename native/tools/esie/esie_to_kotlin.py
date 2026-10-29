#!/usr/bin/env python3
"""Converts the Esie master (art/esie.svg) into Kotlin data for the shared core.

    python3 native/tools/esie/esie_to_kotlin.py \
        native/tools/esie/art/esie.svg \
        native/shared/src/commonMain/kotlin/com/ecency/core/esie/EsieArtData.kt

Every drawable element (path, ellipse, circle, rect) becomes one layer, in document (z) order, with:
- its path as normalised absolute M/L/C/Q/Z commands (ellipses, circles and rects become cubic or line paths,
  an element `transform` is applied to the points),
- the chain of ancestor ids (the parts the rig can move or hide), outermost first,
- its fill and stroke: solid colours as ARGB, gradients with the matrix that maps gradient units (the element's
  bounding box, as SVG's default objectBoundingBox) to art coordinates,
- the clips that apply to it, each with the chain depth after whose transform it applies,
- its opacity (product of the element's and its ancestors', except the mouth shapes' 0, which the rig decides).
A translucent group of like paths (body_shade) becomes one layer, so its opacity applies once as in SVG. The soft
blur filter is left out: at app sizes it is about 1 dp, invisible, and costly to draw.

Also written: the bounds of every id (union of its layers, art coordinates) and the eyelid travel (`--up`).
Only the standard library is used.
"""
import math
import re
import sys
import xml.etree.ElementTree as ET

NS = "{http://www.w3.org/2000/svg}"


def tag(e):
    return e.tag.replace(NS, "")


# ---------- path data ----------

def tokens(d):
    for m in re.finditer(r"[MmLlHhVvCcSsQqTtZzAa]|-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?", d):
        yield m.group(0)


def parse_path(d):
    """Absolute commands: list of (op, [x, y, ...]) with op in M L C Q Z."""
    out = []
    toks = list(tokens(d))
    i = 0
    cmd = None
    cx = cy = sx = sy = 0.0
    last_c2 = None
    last_q = None

    def num():
        nonlocal i
        v = float(toks[i])
        i += 1
        return v

    while i < len(toks):
        t = toks[i]
        if re.match(r"[A-Za-z]", t):
            cmd = t
            i += 1
            if cmd in "Zz":
                out.append(("Z", []))
                cx, cy = sx, sy
                last_c2 = last_q = None
                continue
        if cmd is None:
            raise ValueError("path data starts without a command")
        rel = cmd.islower()
        c = cmd.upper()
        if c == "A":
            raise ValueError("arcs are not supported")
        if c == "M":
            x, y = num(), num()
            if rel:
                x += cx
                y += cy
            out.append(("M", [x, y]))
            cx, cy = sx, sy = x, y
            cmd = "l" if rel else "L"
            last_c2 = last_q = None
        elif c == "L":
            x, y = num(), num()
            if rel:
                x += cx
                y += cy
            out.append(("L", [x, y]))
            cx, cy = x, y
            last_c2 = last_q = None
        elif c == "H":
            x = num() + (cx if rel else 0)
            out.append(("L", [x, cy]))
            cx = x
            last_c2 = last_q = None
        elif c == "V":
            y = num() + (cy if rel else 0)
            out.append(("L", [cx, y]))
            cy = y
            last_c2 = last_q = None
        elif c in "CS":
            if c == "C":
                x1, y1 = num(), num()
                if rel:
                    x1 += cx
                    y1 += cy
            else:
                x1, y1 = (2 * cx - last_c2[0], 2 * cy - last_c2[1]) if last_c2 else (cx, cy)
            x2, y2, x, y = num(), num(), num(), num()
            if rel:
                x2 += cx
                y2 += cy
                x += cx
                y += cy
            out.append(("C", [x1, y1, x2, y2, x, y]))
            last_c2 = (x2, y2)
            last_q = None
            cx, cy = x, y
        elif c in "QT":
            if c == "Q":
                x1, y1 = num(), num()
                if rel:
                    x1 += cx
                    y1 += cy
            else:
                x1, y1 = (2 * cx - last_q[0], 2 * cy - last_q[1]) if last_q else (cx, cy)
            x, y = num(), num()
            if rel:
                x += cx
                y += cy
            out.append(("Q", [x1, y1, x, y]))
            last_q = (x1, y1)
            last_c2 = None
            cx, cy = x, y
    return out


def ellipse_path(cx, cy, rx, ry):
    k = 0.5522847498
    return [
        ("M", [cx + rx, cy]),
        ("C", [cx + rx, cy + k * ry, cx + k * rx, cy + ry, cx, cy + ry]),
        ("C", [cx - k * rx, cy + ry, cx - rx, cy + k * ry, cx - rx, cy]),
        ("C", [cx - rx, cy - k * ry, cx - k * rx, cy - ry, cx, cy - ry]),
        ("C", [cx + k * rx, cy - ry, cx + rx, cy - k * ry, cx + rx, cy]),
        ("Z", []),
    ]


def rect_path(x, y, w, h):
    return [("M", [x, y]), ("L", [x + w, y]), ("L", [x + w, y + h]), ("L", [x, y + h]), ("Z", [])]


def fmt(v):
    s = f"{v:.1f}"
    return "0.0" if s == "-0.0" else s


def path_string(cmds):
    parts = []
    for op, pts in cmds:
        parts.append(op + " ".join(f"{fmt(pts[j])},{fmt(pts[j + 1])}" for j in range(0, len(pts), 2)))
    return "".join(parts)


def bbox(cmds):
    xs, ys = [], []
    px = py = 0.0
    for op, p in cmds:
        if op in "ML":
            xs.append(p[0])
            ys.append(p[1])
            px, py = p[0], p[1]
        elif op == "C":
            for k in range(1, 41):
                t = k / 40
                u = 1 - t
                xs.append(u ** 3 * px + 3 * u * u * t * p[0] + 3 * u * t * t * p[2] + t ** 3 * p[4])
                ys.append(u ** 3 * py + 3 * u * u * t * p[1] + 3 * u * t * t * p[3] + t ** 3 * p[5])
            px, py = p[4], p[5]
        elif op == "Q":
            for k in range(1, 41):
                t = k / 40
                u = 1 - t
                xs.append(u * u * px + 2 * u * t * p[0] + t * t * p[2])
                ys.append(u * u * py + 2 * u * t * p[1] + t * t * p[3])
            px, py = p[2], p[3]
    return [min(xs), min(ys), max(xs), max(ys)]


# ---------- transforms (a b c d e f, as SVG matrix()) ----------

def mul(m, n):
    a, b, c, d, e, f = m
    a2, b2, c2, d2, e2, f2 = n
    return [a * a2 + c * b2, b * a2 + d * b2, a * c2 + c * d2, b * c2 + d * d2, a * e2 + c * f2 + e, b * e2 + d * f2 + f]


IDENTITY = [1, 0, 0, 1, 0, 0]


def parse_transform(s):
    m = IDENTITY
    for name, args in re.findall(r"(\w+)\(([^)]*)\)", s or ""):
        v = [float(x) for x in re.split(r"[ ,]+", args.strip()) if x]
        if name == "translate":
            n = [1, 0, 0, 1, v[0], v[1] if len(v) > 1 else 0]
        elif name == "scale":
            n = [v[0], 0, 0, v[1] if len(v) > 1 else v[0], 0, 0]
        elif name == "rotate":
            r = math.radians(v[0])
            n = [math.cos(r), math.sin(r), -math.sin(r), math.cos(r), 0, 0]
            if len(v) == 3:
                n = mul(mul([1, 0, 0, 1, v[1], v[2]], n), [1, 0, 0, 1, -v[1], -v[2]])
        elif name == "matrix":
            n = v
        else:
            raise ValueError(f"transform {name} is not supported")
        m = mul(m, n)
    return m


def apply(m, cmds):
    out = []
    for op, p in cmds:
        q = []
        for j in range(0, len(p), 2):
            x, y = p[j], p[j + 1]
            q += [m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]]
        out.append((op, q))
    return out


# ---------- paint ----------

def color(hex_, alpha=1.0):
    h = hex_.lstrip("#")
    if len(h) == 3:
        h = "".join(ch * 2 for ch in h)
    a = max(0, min(255, round(alpha * 255)))
    return f"0x{a:02X}{h.upper()}"


def num_attr(e, name, default):
    v = e.get(name)
    return float(v) if v is not None else default


def main(src, out):
    root = ET.parse(src).getroot()
    gradients, clips = {}, {}
    for e in root.iter():
        t = tag(e)
        if t in ("linearGradient", "radialGradient"):
            stops = []
            for s in e:
                if tag(s) == "stop":
                    stops.append((float(s.get("offset", "0")), color(s.get("stop-color"), num_attr(s, "stop-opacity", 1.0))))
            if t == "linearGradient":
                coords = [num_attr(e, "x1", 0), num_attr(e, "y1", 0), num_attr(e, "x2", 1), num_attr(e, "y2", 0)]
            else:
                coords = [num_attr(e, "cx", .5), num_attr(e, "cy", .5), num_attr(e, "r", .5), 0.0]
            gradients[e.get("id")] = (t == "radialGradient", coords, stops)
        elif t == "clipPath":
            shapes = [c for c in e if tag(c) == "path"]
            clips[e.get("id")] = path_string(parse_path(shapes[0].get("d")))

    esie = next(e for e in root.iter() if e.get("id") == "esie")
    layers, bounds, lids = [], {}, {}

    def ref(v):
        m = re.match(r"url\(#([^)]+)\)", v or "")
        return m.group(1) if m else None

    def paint(value, opacity, box, m):
        if value is None or value == "none":
            return "null"
        g = ref(value)
        if g:
            radial, coords, stops = gradients[g]
            x0, y0, x1, y1 = box
            gm = mul(m, [x1 - x0, 0, 0, y1 - y0, x0, y0])
            return (f"EsieGradient(radial = {str(radial).lower()}, coords = floatArrayOf({', '.join(fmt3(c) for c in coords)}), "
                    f"offsets = floatArrayOf({', '.join(fmt3(o) for o, _ in stops)}), "
                    f"colors = longArrayOf({', '.join(c for _, c in stops)}), "
                    f"matrix = floatArrayOf({', '.join(fmt3(v) for v in gm)}){', alpha = ' + fmt3(opacity) + 'f' if opacity != 1 else ''})")
        return f"EsieSolid({color(value, opacity)})"

    def walk(e, chain, opacity, clip_list):
        t = tag(e)
        if t in ("clipPath", "defs", "filter"):
            return
        eid = e.get("id")
        style = e.get("style", "")
        up = re.search(r"--up:\s*(-?[\d.]+)px", style)
        if up and eid:
            lids[eid] = float(up.group(1))
        is_mouth = "mouth" in (e.get("class") or "").split()
        if e.get("opacity") is not None and not is_mouth:
            opacity *= float(e.get("opacity"))
        if eid:
            chain = chain + [eid]
        cp = ref(e.get("clip-path"))
        if cp:
            clip_list = clip_list + [(len(chain), cp)]
        kids = list(e)
        if t == "g" and e.get("opacity") is not None and len(kids) > 1 and all(
                tag(c) == "path" and not c.get("id") and not c.get("transform") and
                (c.get("fill"), c.get("stroke")) == (kids[0].get("fill"), kids[0].get("stroke")) for c in kids):
            # one shape for a translucent group of like paths, so the group composites once (SVG group opacity)
            cmds = [cmd for c in kids for cmd in parse_path(c.get("d"))]
            add_layer(kids[0], cmds, chain, opacity, clip_list)
            return
        if t == "g":
            for c in kids:
                walk(c, chain, opacity, clip_list)
            return
        if t == "path":
            cmds = parse_path(e.get("d"))
        elif t == "ellipse":
            cmds = ellipse_path(float(e.get("cx")), float(e.get("cy")), float(e.get("rx")), float(e.get("ry")))
        elif t == "circle":
            r = float(e.get("r"))
            cmds = ellipse_path(float(e.get("cx")), float(e.get("cy")), r, r)
        elif t == "rect":
            cmds = rect_path(float(e.get("x")), float(e.get("y")), float(e.get("width")), float(e.get("height")))
        else:
            raise ValueError(f"element {t} is not supported")
        add_layer(e, cmds, chain, opacity, clip_list)

    def add_layer(e, cmds, chain, opacity, clip_list):
        box = bbox(cmds)
        m = parse_transform(e.get("transform"))
        drawn = apply(m, cmds)
        fill = paint(e.get("fill", "#000000"), num_attr(e, "fill-opacity", 1.0), box, m)
        stroke = "null"
        if e.get("stroke") and e.get("stroke") != "none":
            stroke = (f"EsieStroke({color(e.get('stroke'), num_attr(e, 'stroke-opacity', 1.0))}, "
                      f"{fmt3(num_attr(e, 'stroke-width', 1.0))}f)")
        # the soft filter (feGaussianBlur) is left out: about 1 dp at app sizes, invisible, and costly to draw
        dbox = bbox(drawn)
        # a stroke widens what is drawn
        if stroke != "null":
            w = num_attr(e, "stroke-width", 1.0) / 2
            dbox = [dbox[0] - w, dbox[1] - w, dbox[2] + w, dbox[3] + w]
        for k in chain:
            b = bounds.get(k)
            bounds[k] = dbox if b is None else [min(b[0], dbox[0]), min(b[1], dbox[1]), max(b[2], dbox[2]), max(b[3], dbox[3])]
        layers.append((chain, path_string(drawn), fill, stroke, opacity, clip_list))

    walk(esie, [], 1.0, [])

    lines = [
        "// Generated by native/tools/esie/esie_to_kotlin.py from native/tools/esie/art/esie.svg. Do not edit by hand.",
        "package com.ecency.core.esie",
        "",
        "@Suppress(\"LargeClass\", \"MaxLineLength\")",
        "internal object EsieArtData {",
        "    /** viewBox of the master. */",
        f"    val VIEW_BOX = floatArrayOf({', '.join(fmt3(float(v)) + 'f' for v in root.get('viewBox').split())})",
        "",
        "    val CLIPS: Map<String, String> = mapOf(",
    ]
    for k, d in clips.items():
        lines.append(f"        \"{k}\" to \"{d}\",")
    lines += ["    )", "", "    /** Eyelid groups and how far they are parked above the eye (negative y). */",
              "    val LID_TRAVEL: Map<String, Float> = mapOf(" + ", ".join(f'"{k}" to {fmt3(v)}f' for k, v in lids.items()) + ")",
              "", "    /** Bounds (left, top, right, bottom) of every named part, art coordinates. */",
              "    val BOUNDS: Map<String, FloatArray> = mapOf("]
    for k, b in bounds.items():
        lines.append(f"        \"{k}\" to floatArrayOf({', '.join(fmt(v) + 'f' for v in b)}),")
    lines += ["    )", "", "    /** Every drawable, bottom to top. */", "    val LAYERS: List<EsieLayer> = listOf("]
    for chain, d, fill, stroke, opacity, clip_list in layers:
        lines.append("        EsieLayer(")
        lines.append(f"            chain = listOf({', '.join(chr(34) + c + chr(34) for c in chain)}),")
        lines.append(f"            d = \"{d}\",")
        lines.append(f"            fill = {kotlin_floats(fill)},")
        if stroke != "null":
            lines.append(f"            stroke = {stroke},")
        if opacity != 1:
            lines.append(f"            opacity = {fmt3(opacity)}f,")
        if clip_list:
            lines.append(f"            clips = listOf({', '.join(f'EsieClip({depth}, {chr(34)}{cid}{chr(34)})' for depth, cid in clip_list)}),")
        lines.append("        ),")
    lines += ["    )", "}", ""]
    open(out, "w").write("\n".join(lines))
    print(f"{len(layers)} layers, {len(bounds)} parts, {len(clips)} clips -> {out}")


def fmt3(v):
    s = f"{v:.4f}".rstrip("0").rstrip(".")
    if s in ("-0", ""):
        s = "0"
    return s


def kotlin_floats(s):
    """Marks the numbers inside floatArrayOf(...) as Float literals."""
    def fix(m):
        nums = [x.strip() for x in m.group(1).split(",")]
        return "floatArrayOf(" + ", ".join(n + "f" for n in nums) + ")"
    return re.sub(r"floatArrayOf\(([^)]*)\)", fix, s)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
