#!/usr/bin/env python3
"""Seasonal app icons (README "App icons"): one source for the Android launcher aliases and the iOS alternate icons.

Every icon is the real Ecency mark (ecency-assets ecency_logo.svg) on a coloured tile with one small object. The
designs below are drawn once as primitives and written out as:

  Android  androidApp/src/main/res/drawable/launcher_<name>_{background,foreground,monochrome}.xml (vector drawables)
           androidApp/src/main/res/mipmap-anydpi-v26/launcher_<name>.xml (adaptive icon with the monochrome layer)
           androidApp/src/main/res/mipmap/launcher_<name>.xml (the two layers stacked, Android 7)
           the <activity-alias> block of androidApp/src/main/AndroidManifest.xml (between the seasonal-icons marks)
  iOS      iosApp/EcencyApp/Assets.xcassets/AppIcon-<Key>.appiconset (1024 px light, dark and tinted PNGs)
           iosApp/EcencyApp/Assets.xcassets/IconPreview-<Key>.imageset (the Settings picker's 120 px preview)

The PNGs are drawn by AppKit (rasterize_svg.swift, macOS only) from SVGs written to a temporary folder. The keys are
LauncherIcon.key in shared/.../app/SeasonalIcons.kt; adding a celebration: README "App icons".

Usage (from the repository root): python3 scripts/icons/seasonal_icons.py [--check]
  --check  fail when an Android or iOS text file on disk differs from what this script writes (PNGs are not compared:
           AppKit's encoder output can change between macOS versions).
"""
import json
import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
RES = os.path.join(ROOT, "androidApp", "src", "main", "res")
MANIFEST = os.path.join(ROOT, "androidApp", "src", "main", "AndroidManifest.xml")
ASSETS = os.path.join(ROOT, "iosApp", "EcencyApp", "Assets.xcassets")

# ecency-assets/ecency_logo.svg (viewBox 107 x 103.66)
LOGO = ("M67.49,103.52c-13.16.11-44.19.39-51.15-.42C10.69,102.44.28,98.67.16,84.45c-.08-8.19-.55-41.59.62-49,"
        "2.43-15.4,6.56-25.06,24.16-33C39.88-4,71.78,1.37,75.23,27.15c.65,4.9,1.83,5.45,3.63,5.83,28.55,6,34.86,"
        "38.23,21.25,55.8C91.45,100,82.1,102.14,67.49,103.52ZM62.3,35.88C63.29,19.1,46.27,7,29.08,16,16.81,22.37,"
        "15.75,31.66,15,42.24c-1,13.4-.37,23.16-.14,36.59.11,6,1.85,9.67,8.3,10.25,1.67.15,24.74,0,36.73.09,15.63.08,"
        "35-7.12,31.93-26.15A18.46,18.46,0,0,0,76.08,48.14a3.07,3.07,0,0,0-3.22,1.44C62.74,70.49,40.38,69.45,31.29,58"
        "c-8.18-10.28-3.94-27.27,6.81-30.87,4-1.36,7.43-.16,9.09,2.71,2.68,4.64.18,8.83-3.62,11.14s-3.57,5.71-2,8c3.73,"
        "5.23,11.07,4.19,15.72-.4C61.07,44.83,62,40.57,62.3,35.88Z")
LOGO_HEIGHT = 103.66
# the mark where the shipped icon has it, on a 100 unit tile: about 58 % of it, a little left of centre
LOGO_T = [("t", 21, 22), ("s", 0.54)]

# the Hive mark (ecency-vision public/assets/hive-logo.svg, viewBox 220 x 190)
HIVE = [
    "M157.272625,107.263942 C157.998992,107.263942 158.45262,108.051463 158.088736,108.68075 L111.33839,189.528945 "
    "C111.169808,189.820485 110.858795,190 110.522279,190 L81.9443812,190 C81.2180145,190 80.764386,189.212478 "
    "81.1282705,188.583191 L127.878616,107.734996 C128.047199,107.443456 128.358211,107.263942 128.694727,107.263942 "
    "L157.272625,107.263942 Z M129.477721,84.0901367 C129.141205,84.0901367 128.830192,83.9106218 128.66161,83.6190818 "
    "L81.1282705,1.41680884 C80.764386,0.787521511 81.2180145,0 81.9443812,0 L110.522279,0 C110.858795,0 111.169808,"
    "0.179514873 111.33839,0.471054898 L158.87173,82.6733278 C159.235614,83.3026152 158.781986,84.0901367 158.055619,"
    "84.0901367 L129.477721,84.0901367 Z",
    "M135.128406 1.41635199C134.76385.787064228 135.218932 0 135.947343 0L164.565951 0C164.903712 0 165.215845.179714185 "
    "165.384888.47151174L219.873006 94.5275799C220.042331 94.8198642 220.042331 95.1801358 219.873006 95.4724201"
    "L165.384888 189.528488C165.215845 189.820286 164.903712 190 164.565951 190L135.947343 190C135.218932 190 134.76385 "
    "189.212936 135.128406 188.583648L189.342845 95 135.128406 1.41635199zM111.870216 94.5240823C112.042446 94.816752 "
    "112.043313 95.1785591 111.872487 95.4720377L57.1252257 189.528106C56.7599958 190.155572 55.8478414 190.157723 "
    "55.4796094 189.531986L.129783614 95.4759177C-.0424457704 95.183248-.0433125021 94.8214409.127512727 94.5279623"
    "L54.8747743.471894257C55.2400042-.15557243 56.1521586-.157723129 56.5203906.468014185L111.870216 94.5240823z",
]


# ---- primitives: a shape is a dict; a group is ("g", transforms, children) ----

def P(d, fill=None, stroke=None, sw=None, op=None, detail=False):
    """A path. [detail] marks drawing inside another shape: the one-colour layers leave it out."""
    return {"d": d, "fill": fill, "stroke": stroke, "sw": sw, "op": op, "detail": detail}


def num(v):
    s = ("%.3f" % v).rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def circle(cx, cy, r, fill=None, op=None, detail=False, stroke=None, sw=None):
    d = "M%s,%sa%s,%s 0 1,0 %s,0a%s,%s 0 1,0 %s,0Z" % (num(cx - r), num(cy), num(r), num(r), num(2 * r), num(r), num(r), num(-2 * r))
    return P(d, fill, stroke, sw, op, detail)


def ellipse(cx, cy, rx, ry, fill, detail=False):
    return P("M%s,%sa%s,%s 0 1,0 %s,0a%s,%s 0 1,0 %s,0Z" % (num(cx - rx), num(cy), num(rx), num(ry), num(2 * rx), num(rx), num(ry), num(-2 * rx)), fill, detail=detail)


def rrect(x, y, w, h, r, fill, detail=False):
    d = ("M%s,%sh%sa%s,%s 0 0 1 %s,%sv%sa%s,%s 0 0 1 %s,%sh%sa%s,%s 0 0 1 %s,%sv%sa%s,%s 0 0 1 %s,%sZ" % (
        num(x + r), num(y), num(w - 2 * r), num(r), num(r), num(r), num(r), num(h - 2 * r), num(r), num(r), num(-r), num(r),
        num(-(w - 2 * r)), num(r), num(r), num(-r), num(-r), num(-(h - 2 * r)), num(r), num(r), num(r), num(-r)))
    return P(d, fill, detail=detail)


def line(x1, y1, x2, y2, stroke, sw, detail=False):
    return P("M%s,%sL%s,%s" % (num(x1), num(y1), num(x2), num(y2)), None, stroke, sw, detail=detail)


def G(transforms, *children):
    return ("g", transforms, list(children))


# ---- the objects (the drafts of the decision page, all in the free top right corner of the 100 unit tile) ----

def sparkle(c0, c1):
    import math
    out = []
    for (x, y, r, col) in ((78, 20, 10, c0), (88, 36, 5, c1), (66, 10, 4, c1)):
        for i in range(8):
            a = i * math.pi / 4
            out.append(line(x + math.cos(a) * r * .35, y + math.sin(a) * r * .35, x + math.cos(a) * r, y + math.sin(a) * r, col, 1.6))
    return out


def lantern():
    return [
        line(79, 4, 79, 11, "#f6c453", 1.2),
        rrect(75, 10, 8, 2.4, 1, "#f6c453"),
        ellipse(79, 20, 8.5, 8, "#ffd36a"),
        P("M73 20h12M74 15.5h10M74 24.5h10", None, "#e0a32a", .8, detail=True),
        rrect(75, 27.5, 8, 2.4, 1, "#f6c453"),
        P("M79 30v6M77.5 30.5v4.5M80.5 30.5v4.5", None, "#f6c453", .9),
    ]


def hearts():
    h = "M0 3C0 0 4 -1 5 2C6 -1 10 0 10 3C10 6 5 9 5 9C5 9 0 6 0 3Z"
    return [G([("t", x, y), ("s", s)], P(h, col)) for (x, y, s, col) in ((70, 12, 1.5, "#ffffff"), (84, 26, .9, "#ffd1dc"), (64, 30, .6, "#ffd1dc"))]


def crescent():
    return [
        P("M80 9a11 11 0 1 0 8.5 17.5 9 9 0 1 1 -8.5 -17.5Z", "#f2d27a"),
        P("M88 12l1.2 2.6 2.8.3-2.1 1.9.6 2.8-2.5-1.4-2.5 1.4.6-2.8-2.1-1.9 2.8-.3Z", "#f2d27a"),
    ]


def hive():
    return [G([("t", 66.5, 9.5), ("s", 0.118)], *[P(d, "#ffffff") for d in HIVE])]


def bat():
    # the moon behind the bat, so the dark bat reads on the night tile
    return [
        circle(80, 20, 9.5, "#ffd36a"),
        P("M78 18c-2-3-6-4-12-2 3 1 4 3 4 5 2-1 4-1 5 1 1-2 2-3 3-3s2 1 3 3c1-2 3-2 5-1 0-2 1-4 4-5-6-2-10-1-12 2Z", "#1a0b26", detail=True),
    ]


def diya():
    return [
        P("M78 8c3 4 4 7 0 11-4-4-3-7 0-11Z", "#ffd36a"),
        P("M78 11c1.5 2 2 4 0 6-2-2-1.5-4 0-6Z", "#fff4c2", detail=True),
        P("M66 22h24c-1 7-6 11-12 11s-11-4-12-11Z", "#e8742a"),
        line(68, 22, 88, 22, "#ffb627", 1.4),
        circle(72, 27, 1, "#ffd36a", detail=True), circle(78, 29, 1, "#ffd36a", detail=True), circle(84, 27, 1, "#ffd36a", detail=True),
    ]


def tag():
    return [
        G([("r", 8, 80, 20)], P("M70 10h12l9 9-12 12-9-9Z", "#ffffff")),
        circle(74.5, 15, 2, "#0e0e10", detail=True),
        G([("r", 8, 80, 20)], P("M78 24l6-6M78.5 18.5h.1M84 23.5h.1", None, "#0e0e10", 1.6, detail=True)),
    ]


def snow():
    return [circle(x, y, r, "#ffffff") for (x, y, r) in ((70, 12, 1.6), (84, 9, 1.2), (90, 22, 1.6), (76, 27, 1.1))]


def hat():
    """Christmas: a hat on the heart's upper lobe (on the mark, so it never moves with the corner object)."""
    return [
        P("M24 27c4-12 16-18 30-15 6 1 9 4 11 9-4-3-8-3-11-1-7-3-20 0-30 7Z", "#d62c3a"),
        P("M22 28c10-7 23-10 32-7l1 4c-10-3-22 0-32 7Z", "#ffffff"),
        circle(66, 22, 3.4, "#ffffff"),
    ]


def ball():
    return [
        circle(79, 20, 10, "#ffffff"),
        P("M79 15.5l4.3 3.1-1.6 5h-5.4l-1.6-5Z", "#111111", detail=True),
        P("M79 15.5v-5M83.3 18.6l4.7-1.5M81.7 23.6l2.9 4M76.3 23.6l-2.9 4M74.7 18.6l-4.7-1.5", None, "#111111", 1, detail=True),
    ]


def medal():
    return [
        P("M71 4l5 13h6l5-13h-5l-3 8-3-8Z", "#e23b3b"),
        circle(79, 24, 9, "#f6c453"),
        circle(79, 24, 6.2, None, stroke="#c9952b", sw=1.2, detail=True),
        P("M79 19.8l1.3 2.7 3 .4-2.2 2 .5 3-2.6-1.4-2.6 1.4.5-3-2.2-2 3-.4Z", "#c9952b", detail=True),
    ]


def pizza():
    return [
        P("M68 9l24 6-15 22Z", "#ffd36a"),
        P("M68 9l24 6", None, "#c26a1b", 3),
        circle(77, 16, 2.2, "#d6402b", detail=True), circle(83, 20, 1.8, "#d6402b", detail=True), circle(77, 24, 1.6, "#d6402b", detail=True),
    ]


def earth():
    return [
        circle(79, 20, 10.5, "#3aa0ff"),
        P("M72 14c3-2 6-1 7 1s-1 4 1 6-2 5-5 4-3-4-5-5 0-4 2-6Z", "#46c46b", detail=True),
        P("M83 22c2-1 4 0 5 2-1 3-3 4-5 3s-2-4 0-5Z", "#46c46b", detail=True),
        P("M88 11c-2 0-3 1-3 3 2 0 3-1 3-3Z", "#9be38f", detail=True),
    ]


def code():
    return [P("M73 12l-7 8 7 8M85 12l7 8-7 8M81.5 9l-5 22", None, "#7cfc9a", 2.4)]


def moon():
    return [
        circle(80, 19, 10, "#ffe7a3"),
        circle(76, 16, 1.6, "#f2cf6f", detail=True), circle(83, 22, 2.2, "#f2cf6f", detail=True), circle(78, 23, 1, "#f2cf6f", detail=True),
        circle(66, 10, .9, "#ffffff", op=.8), circle(91, 34, .9, "#ffffff", op=.8), circle(69, 33, .7, "#ffffff", op=.8),
    ]


def splash():
    return [
        circle(76, 16, 7, "#ff3d8b", op=.9), circle(86, 22, 6, "#ffd23f", op=.9),
        circle(77, 28, 5, "#2ec4b6", op=.9), circle(68, 25, 3.2, "#8a5cf6", op=.9),
    ]


def sabzeh():
    return [
        P("M70 26h18l-2 7H72Z", "#c9952b"),
        P("M72 26c0-6 1-11 2-15M75 26c0-6 2-12 4-17M78 26c0-7 0-12-1-17M81 26c1-6 3-11 5-14M84 26c1-4 3-8 5-10M86.5 26c1-3 2-5 3-6", None, "#d6f5a3", 1.4),
        line(70, 24.5, 88, 24.5, "#e23b3b", 1.6, detail=True),
    ]


# key (LauncherIcon.key), background stops, mark (colour or vertical stops), corner object, shapes on the mark
ICONS = [
    ("NewYear", ["#0b1640", "#26338a"], ["#ffe9a8", "#f2b84b"], sparkle("#ffd36a", "#ffffff"), []),
    ("LunarNewYear", ["#b30f27", "#e2393f"], ["#ffe08a", "#f6b93b"], lantern(), []),
    ("Valentines", ["#d8345f", "#ff8aa1"], "#ffffff", hearts(), []),
    ("RamadanEid", ["#0c3442", "#1f6070"], "#ffffff", crescent(), []),
    ("HiveDay", ["#c40e2c", "#e31337"], "#ffffff", hive(), []),
    ("Nowruz", ["#1f6b3a", "#3f9a5a"], "#ffffff", sabzeh(), []),
    ("Holi", ["#ffffff", "#f1ecff"], ["#2a55d6", "#5b7fe0"], splash(), []),
    ("EarthDay", ["#0f6b5c", "#2fa37a"], "#ffffff", earth(), []),
    ("PizzaDay", ["#e8850f", "#ffb347"], "#ffffff", pizza(), []),
    ("Football", ["#16632f", "#2a8a45"], "#ffffff", ball(), []),
    ("SummerGames", ["#1a3f8f", "#2f6bd1"], "#ffffff", medal(), []),
    ("ProgrammersDay", ["#0f1a26", "#1e3a5f"], ["#b6ffcb", "#4fdc7c"], code(), []),
    ("MidAutumn", ["#1b1f4a", "#3a2f6b"], ["#fff1c4", "#ffd56a"], moon(), []),
    ("Diwali", ["#3d0f35", "#7a1f4f"], ["#ffd36a", "#ffa62b"], diya(), []),
    ("Halloween", ["#22102f", "#4a1d5c"], ["#ffb347", "#ff7a1a"], bat(), []),
    ("BlackFriday", ["#0e0e10", "#26262c"], "#ffffff", tag(), []),
    ("Christmas", ["#1d4fc4", "#4e78dc"], "#ffffff", snow(), hat()),
]

# On Android the visible part of the 108 dp canvas is the middle 72 dp, often cut to a circle: the 100 unit tile maps
# onto it, and the corner object moves in and shrinks a little so a round mask keeps it whole.
ANDROID_TILE = [("t", 18, 18), ("s", 0.72)]
ANDROID_CORNER = [("t", -7, 7), ("t", 79, 21), ("s", 0.9), ("t", -79, -21)]


def snake(key):
    return re.sub(r"(?<!^)(?=[A-Z])", "_", key).lower()


def gray(hex_colour):
    r, g, b = (int(hex_colour[i:i + 2], 16) for i in (1, 3, 5))
    v = round(0.2126 * r + 0.7152 * g + 0.0722 * b)
    v = max(v, 90)  # nothing darker than this on a tinted icon: it would vanish
    return "#%02x%02x%02x" % (v, v, v)


def recolour(nodes, fn, keep_details=True):
    out = []
    for n in nodes:
        if isinstance(n, tuple):
            out.append(("g", n[1], recolour(n[2], fn, keep_details)))
        elif keep_details or not n["detail"]:
            m = dict(n)
            if m["fill"]:
                m["fill"] = fn(m["fill"])
            if m["stroke"]:
                m["stroke"] = fn(m["stroke"])
            out.append(m)
    return out


# ---- SVG (iOS) ----

def svg_transform(ts):
    parts = []
    for t in ts:
        if t[0] == "t":
            parts.append("translate(%s %s)" % (num(t[1]), num(t[2])))
        elif t[0] == "s":
            parts.append("scale(%s)" % num(t[1]))
        else:
            parts.append("rotate(%s %s %s)" % (num(t[1]), num(t[2]), num(t[3])))
    return " ".join(parts)


def svg_nodes(nodes):
    out = []
    for n in nodes:
        if isinstance(n, tuple):
            out.append('<g transform="%s">%s</g>' % (svg_transform(n[1]), svg_nodes(n[2])))
            continue
        a = ['d="%s"' % n["d"], 'fill="%s"' % (n["fill"] or "none")]
        if n["stroke"]:
            a += ['stroke="%s"' % n["stroke"], 'stroke-width="%s"' % num(n["sw"]), 'stroke-linecap="round"', 'stroke-linejoin="round"']
        if n["op"] is not None:
            a.append('opacity="%s"' % num(n["op"]))
        out.append("<path %s/>" % " ".join(a))
    return "".join(out)


def svg(icon, variant):
    """[variant]: any and preview (on the tile, which rasterize_svg.swift fills), dark (no tile: the system draws its
    dark one) or tinted (grey, no tile)."""
    key, bg, mark, corner, on_mark = icon
    defs, body = [], []
    if variant == "tinted":
        mark_fill, corner, on_mark = "#ffffff", recolour(corner, gray), recolour(on_mark, gray)
    elif isinstance(mark, list):
        defs.append('<linearGradient id="mk" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="%s"/><stop offset="1" stop-color="%s"/></linearGradient>' % tuple(mark))
        mark_fill = "url(#mk)"
    else:
        mark_fill = mark
    body.append('<path transform="%s" d="%s" fill="%s"/>' % (svg_transform(LOGO_T), LOGO, mark_fill))
    body.append(svg_nodes(on_mark))
    body.append(svg_nodes(corner))
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" width="100" height="100"><defs>%s</defs>%s</svg>\n'
            % ("".join(defs), "".join(body)))


def appiconset_contents():
    images = [{"filename": "icon.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}]
    for appearance in ("dark", "tinted"):
        images.append({"appearances": [{"appearance": "luminosity", "value": appearance}], "filename": appearance + ".png",
                       "idiom": "universal", "platform": "ios", "size": "1024x1024"})
    return json.dumps({"images": images, "info": {"author": "xcode", "version": 1}}, indent=2) + "\n"


def imageset_contents():
    return json.dumps({"images": [{"filename": "preview.png", "idiom": "universal"}], "info": {"author": "xcode", "version": 1}}, indent=2) + "\n"


# ---- vector drawables (Android) ----

HEADER = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!-- Generated by scripts/icons/seasonal_icons.py: edit the script, not this file. -->\n"


def argb(hex_colour):
    return "#FF" + hex_colour[1:].upper()


def vd_nodes(nodes, indent):
    out = []
    pad = "    " * indent
    for n in nodes:
        if isinstance(n, tuple):
            inner = vd_nodes(n[2], indent + len(n[1]))
            # one group per transform, outermost first (SVG applies the list right to left)
            opened = []
            for i, t in enumerate(n[1]):
                p = "    " * (indent + i)
                if t[0] == "t":
                    opened.append('%s<group android:translateX="%s" android:translateY="%s">' % (p, num(t[1]), num(t[2])))
                elif t[0] == "s":
                    opened.append('%s<group android:scaleX="%s" android:scaleY="%s">' % (p, num(t[1]), num(t[1])))
                else:
                    opened.append('%s<group android:rotation="%s" android:pivotX="%s" android:pivotY="%s">' % (p, num(t[1]), num(t[2]), num(t[3])))
            closed = ["%s</group>" % ("    " * (indent + i)) for i in reversed(range(len(n[1])))]
            out += opened + inner + closed
            continue
        a = ['android:pathData="%s"' % n["d"]]
        if n["fill"]:
            a.append('android:fillColor="%s"' % argb(n["fill"]))
            if n["op"] is not None:
                a.append('android:fillAlpha="%s"' % num(n["op"]))
        if n["stroke"]:
            a += ['android:strokeColor="%s"' % argb(n["stroke"]), 'android:strokeWidth="%s"' % num(n["sw"]),
                  'android:strokeLineCap="round"', 'android:strokeLineJoin="round"']
            if n["op"] is not None:
                a.append('android:strokeAlpha="%s"' % num(n["op"]))
        out.append("%s<path\n%s    %s />" % (pad, pad, ("\n%s    " % pad).join(a)))
    return out


def wrap(nodes, ts):
    return [("g", ts, nodes)] if ts else nodes


def vector(body, aapt=False):
    ns = ' xmlns:aapt="http://schemas.android.com/aapt"' if aapt else ""
    return (HEADER + '<vector xmlns:android="http://schemas.android.com/apk/res/android"%s\n    android:width="108dp"\n    android:height="108dp"\n'
            '    android:viewportWidth="108"\n    android:viewportHeight="108">\n%s\n</vector>\n' % (ns, "\n".join(body)))


def gradient(stops, x1, y1, x2, y2, indent):
    p = "    " * indent
    return ['%s<aapt:attr name="android:fillColor">' % p,
            '%s    <gradient android:type="linear" android:startX="%s" android:startY="%s" android:endX="%s" android:endY="%s">' % (p, num(x1), num(y1), num(x2), num(y2)),
            '%s        <item android:offset="0" android:color="%s" />' % (p, argb(stops[0])),
            '%s        <item android:offset="1" android:color="%s" />' % (p, argb(stops[1])),
            "%s    </gradient>" % p, "%s</aapt:attr>" % p]


def mark_xml(mark, indent, mono):
    """The Ecency mark inside the tile groups (the logo's own transform, then the path)."""
    p = "    " * indent
    lines = ['%s<group android:translateX="%s" android:translateY="%s">' % (p, num(LOGO_T[0][1]), num(LOGO_T[0][2])),
             '%s    <group android:scaleX="%s" android:scaleY="%s">' % (p, num(LOGO_T[1][1]), num(LOGO_T[1][1]))]
    q = "    " * (indent + 2)
    if mono or not isinstance(mark, list):
        lines.append('%s<path\n%s    android:pathData="%s"\n%s    android:fillColor="%s" />' % (q, q, LOGO, q, "#FFFFFFFF" if mono else argb(mark)))
    else:
        # gradient coordinates are the path's own: top to bottom of the logo
        lines += ['%s<path android:pathData="%s">' % (q, LOGO)] + gradient(mark, 0, 0, 0, LOGO_HEIGHT, indent + 3) + ["%s</path>" % q]
    lines += ["%s    </group>" % p, "%s</group>" % p]
    return lines


def foreground(icon, mono):
    key, bg, mark, corner, on_mark = icon
    if mono:
        corner, on_mark = recolour(corner, lambda c: "#ffffff", keep_details=False), recolour(on_mark, lambda c: "#ffffff", keep_details=False)
    body = ['    <group android:translateX="18" android:translateY="18">', '        <group android:scaleX="0.72" android:scaleY="0.72">']
    body += mark_xml(mark, 3, mono)
    body += vd_nodes(on_mark, 3)
    body += vd_nodes(wrap(corner, ANDROID_CORNER), 3)
    body += ["        </group>", "    </group>"]
    return vector(body, aapt=not mono and isinstance(mark, list))


def background(icon):
    bg = icon[1]
    body = ['    <path android:pathData="M0,0h108v108h-108z">'] + gradient(bg, 0, 0, 0, 108, 2) + ["    </path>"]
    return vector(body, aapt=True)


def adaptive(name):
    return (HEADER + '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@drawable/launcher_%s_background" />\n'
            '    <foreground android:drawable="@drawable/launcher_%s_foreground" />\n'
            '    <monochrome android:drawable="@drawable/launcher_%s_monochrome" />\n'
            '</adaptive-icon>\n' % (name, name, name))


def legacy(name):
    return (HEADER + '<!-- Android 7 has no adaptive icons: the two layers stacked. -->\n'
            '<layer-list xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <item android:drawable="@drawable/launcher_%s_background" />\n'
            '    <item android:drawable="@drawable/launcher_%s_foreground" />\n'
            '</layer-list>\n' % (name, name))


START, END = "<!-- seasonal-icons:start -->", "<!-- seasonal-icons:end -->"


def manifest_block(text):
    """The launcher aliases: Default (enabled, the RN app's icon) and one disabled alias per celebration."""
    indent = "        "

    def alias(key, enabled, icon, round_icon):
        return ("%s<activity-alias\n%s    android:name=\".Launcher%s\"\n%s    android:enabled=\"%s\"\n%s    android:exported=\"true\"\n"
                "%s    android:icon=\"%s\"\n%s    android:roundIcon=\"%s\"\n%s    android:targetActivity=\".MainActivity\">\n"
                "%s    <intent-filter>\n%s        <action android:name=\"android.intent.action.MAIN\" />\n"
                "%s        <category android:name=\"android.intent.category.LAUNCHER\" />\n%s    </intent-filter>\n%s</activity-alias>\n"
                % (indent, indent, key, indent, "true" if enabled else "false", indent, indent, icon, indent, round_icon, indent,
                   indent, indent, indent, indent, indent))

    block = START + "\n" + indent + ("<!-- Generated by scripts/icons/seasonal_icons.py. The launcher entries (README \"App icons\"): exactly one is\n"
                                     + indent + "     enabled at a time (LauncherAliases.kt). Links, shares and widgets target MainActivity itself. -->\n")
    block += alias("Default", True, "@mipmap/ic_launcher", "@mipmap/ic_launcher_round")
    for icon in ICONS:
        name = "@mipmap/launcher_" + snake(icon[0])
        block += alias(icon[0], False, name, name)
    block += indent + END
    start, end = text.index(START), text.index(END) + len(END)
    return text[:start] + block + text[end:]


def text_outputs():
    files = {}
    for icon in ICONS:
        name = snake(icon[0])
        files[os.path.join(RES, "drawable", "launcher_%s_background.xml" % name)] = background(icon)
        files[os.path.join(RES, "drawable", "launcher_%s_foreground.xml" % name)] = foreground(icon, mono=False)
        files[os.path.join(RES, "drawable", "launcher_%s_monochrome.xml" % name)] = foreground(icon, mono=True)
        files[os.path.join(RES, "mipmap-anydpi-v26", "launcher_%s.xml" % name)] = adaptive(name)
        files[os.path.join(RES, "mipmap", "launcher_%s.xml" % name)] = legacy(name)
        files[os.path.join(ASSETS, "AppIcon-%s.appiconset" % icon[0], "Contents.json")] = appiconset_contents()
        files[os.path.join(ASSETS, "IconPreview-%s.imageset" % icon[0], "Contents.json")] = imageset_contents()
    files[os.path.join(ASSETS, "IconPreview-Default.imageset", "Contents.json")] = imageset_contents()
    with open(MANIFEST, encoding="utf-8") as f:
        files[MANIFEST] = manifest_block(f.read())
    return files


def main():
    check = "--check" in sys.argv[1:]
    files = text_outputs()
    if check:
        bad = []
        for path, text in sorted(files.items()):
            try:
                with open(path, encoding="utf-8") as f:
                    if f.read() != text:
                        bad.append(path)
            except FileNotFoundError:
                bad.append(path)
        if bad:
            print("Out of date (run python3 scripts/icons/seasonal_icons.py):\n" + "\n".join(os.path.relpath(p, ROOT) for p in bad))
            sys.exit(1)
        print("%d files up to date" % len(files))
        return
    for path, text in files.items():
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
    # PNGs: SVGs into a temporary folder, drawn by AppKit
    with tempfile.TemporaryDirectory() as tmp:
        jobs = []
        for icon in ICONS:
            key = icon[0]
            folder = os.path.join(ASSETS, "AppIcon-%s.appiconset" % key)
            for variant, file_name, size, opaque in (("any", "icon.png", 1024, 1), ("dark", "dark.png", 1024, 0), ("tinted", "tinted.png", 1024, 0)):
                src = os.path.join(tmp, "%s-%s.svg" % (key, variant))
                with open(src, "w") as f:
                    f.write(svg(icon, variant))
                jobs.append((src, os.path.join(folder, file_name), size, opaque) + (tuple(icon[1]) if opaque else ()))
            src = os.path.join(tmp, "%s-preview.svg" % key)
            with open(src, "w") as f:
                f.write(svg(icon, "preview"))
            jobs.append((src, os.path.join(ASSETS, "IconPreview-%s.imageset" % key, "preview.png"), 120, 1) + tuple(icon[1]))
        # the everyday icon's preview is the shipped icon itself
        jobs.append((os.path.join(ASSETS, "AppIcon.appiconset", "icon-1024.png"), os.path.join(ASSETS, "IconPreview-Default.imageset", "preview.png"), 120, 1))
        manifest = os.path.join(tmp, "jobs.tsv")
        with open(manifest, "w") as f:
            for job in jobs:
                f.write("\t".join(str(v) for v in job) + "\n")
        subprocess.run(["swift", os.path.join(HERE, "rasterize_svg.swift"), manifest], check=True)
    print("wrote %d text files and %d PNGs" % (len(files), len(jobs)))


if __name__ == "__main__":
    main()
