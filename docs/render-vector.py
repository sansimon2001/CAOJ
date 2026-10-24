"""
把 res/drawable/ic_logo_mark.xml 按真实 pathData / gradient / group 变换渲染成图。

为什么要有这个脚本：docs/logo-badge-mock.py 只是「示意稿」，它是按参数另画一遍的，
和图里的实际路径可能对不上。这个脚本直接解析矢量图本身，用来验证
圆弧端点、组变换（落影缩放/下移）、渐变区间是否真的如预期。

只支持本图标用到的子集：M / H / V / L / A / Z + 竖向线性渐变 + group(scale, translate, pivot)。
"""

import re
import math
import xml.etree.ElementTree as ET
from PIL import Image, ImageDraw

ANDROID = "{http://schemas.android.com/apk/res/android}"
AAPT = "{http://schemas.android.com/aapt}"

SRC = r"C:\Users\simon\Desktop\MindBall\app\src\main\res\drawable\ic_logo_mark.xml"
OUT = r"C:\Users\simon\Desktop\MindBall\docs\logo-badge-vector-check.png"
SS = 4          # 超采样


def parse_color(c):
    c = c.strip().lstrip("#")
    if len(c) == 8:
        return int(c[2:4], 16), int(c[4:6], 16), int(c[6:8], 16), int(c[0:2], 16)
    if len(c) == 6:
        return int(c[0:2], 16), int(c[2:4], 16), int(c[4:6], 16), 255
    raise ValueError(c)



def child(el, name):
    """按本地名找子元素：aapt:attr 里的 gradient/item 不带命名空间前缀"""
    for c in el:
        if c.tag.split("}")[-1] == name:
            return c
    return None


def read_grad(grad):
    st = [(float(it.get(ANDROID + "offset")), parse_color(it.get(ANDROID + "color")))
          for it in grad if it.tag.split("}")[-1] == "item"]
    return dict(y0=float(grad.get(ANDROID + "startY", 0)),
                y1=float(grad.get(ANDROID + "endY", 1)),
                stops=sorted(st))


ARC_CMD = re.compile(r"[MHVLACZ]|-?\d*\.?\d+(?:e-?\d+)?")


def arc_points(x0, y0, rx, ry, phi, large, sweep, x1, y1, steps=64):
    """SVG 端点式圆弧 → 采样点（与 Android 的 arc 处理一致）"""
    if rx == 0 or ry == 0:
        return [(x1, y1)]
    phi = math.radians(phi)
    cosp, sinp = math.cos(phi), math.sin(phi)
    dx2, dy2 = (x0 - x1) / 2, (y0 - y1) / 2
    x1p = cosp * dx2 + sinp * dy2
    y1p = -sinp * dx2 + cosp * dy2
    rx, ry = abs(rx), abs(ry)
    lam = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
    if lam > 1:
        rx *= math.sqrt(lam)
        ry *= math.sqrt(lam)
    num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    co = math.sqrt(max(0.0, num / den)) if den else 0.0
    if large == sweep:
        co = -co
    cxp = co * rx * y1p / ry
    cyp = -co * ry * x1p / rx
    cx = cosp * cxp - sinp * cyp + (x0 + x1) / 2
    cy = sinp * cxp + cosp * cyp + (y0 + y1) / 2

    def ang(ux, uy, vx, vy):
        d = (ux * vx + uy * vy) / (math.hypot(ux, uy) * math.hypot(vx, vy))
        a = math.acos(max(-1, min(1, d)))
        return -a if ux * vy - uy * vx < 0 else a

    th1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dth = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not sweep and dth > 0:
        dth -= 2 * math.pi
    if sweep and dth < 0:
        dth += 2 * math.pi
    pts = []
    for i in range(1, steps + 1):
        t = th1 + dth * i / steps
        px = cosp * rx * math.cos(t) - sinp * ry * math.sin(t) + cx
        py = sinp * rx * math.cos(t) + cosp * ry * math.sin(t) + cy
        pts.append((px, py))
    return pts


def parse_path(d):
    toks = ARC_CMD.findall(d.replace(",", " "))
    subpaths, cur = [], []
    i, cmd, x, y = 0, None, 0.0, 0.0
    start = (0.0, 0.0)
    while i < len(toks):
        t = toks[i]
        if re.match(r"[MHVLACZ]", t):
            cmd = t
            i += 1
            if cmd == "Z":
                if cur:
                    cur.append(start)
                    subpaths.append(cur)
                    cur = []
                x, y = start
                continue
        num = lambda k: float(toks[i + k]) if toks[i + k].upper() != "E" else 0.0
        if cmd == "M":
            x, y = float(toks[i]), float(toks[i + 1]); i += 2
            if cur:
                subpaths.append(cur)
            cur = [(x, y)]
            start = (x, y)
            cmd = "L"
        elif cmd == "L":
            x, y = float(toks[i]), float(toks[i + 1]); i += 2
            cur.append((x, y))
        elif cmd == "H":
            x = float(toks[i]); i += 1
            cur.append((x, y))
        elif cmd == "V":
            y = float(toks[i]); i += 1
            cur.append((x, y))
        elif cmd == "A":
            rx, ry, rot = float(toks[i]), float(toks[i + 1]), float(toks[i + 2])
            large, sweep = int(float(toks[i + 3])), int(float(toks[i + 4]))
            nx, ny = float(toks[i + 5]), float(toks[i + 6]); i += 7
            cur.extend(arc_points(x, y, rx, ry, rot, large, sweep, nx, ny))
            x, y = nx, ny
        else:
            i += 1
    if cur:
        subpaths.append(cur)
    return subpaths


def grad_color(g, yy):
    t = (yy - g["y0"]) / ((g["y1"] - g["y0"]) or 1)
    t = max(0.0, min(1.0, t))
    st = g["stops"]
    for k in range(len(st) - 1):
        o0, c0 = st[k]
        o1, c1 = st[k + 1]
        if o0 <= t <= o1:
            f = 0 if o1 == o0 else (t - o0) / (o1 - o0)
            return tuple(int(round(c0[j] + (c1[j] - c0[j]) * f)) for j in range(4))
    return st[-1][1]


def fill_gradient(img, mask, g, scale):
    """按行取色得到竖向渐变图层。g 的坐标是视口单位，像素行要除以 scale 再查"""
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    px = layer.load()
    for y in range(img.size[1]):
        r, gg, b, a = grad_color(g, y / scale)
        for x in range(img.size[0]):
            px[x, y] = (r, gg, b, a)
    layer.putalpha(Image.composite(layer.getchannel("A"), Image.new("L", img.size, 0), mask))
    return layer


def render(target_px, bg):
    root = ET.parse(SRC).getroot()
    vw = float(root.get(ANDROID + "viewportWidth"))
    vh = float(root.get(ANDROID + "viewportHeight"))
    scale = target_px * SS / vw
    W = int(round(vw * scale))
    H = int(round(vh * scale))
    img = Image.new("RGBA", (W, H), bg)

    def walk(node, mat):
        for ch in node:
            tag = ch.tag.split("}")[-1]
            if tag == "group":
                sx = float(ch.get(ANDROID + "scaleX", 1))
                sy = float(ch.get(ANDROID + "scaleY", 1))
                tx = float(ch.get(ANDROID + "translateX", 0))
                ty = float(ch.get(ANDROID + "translateY", 0))
                px_ = float(ch.get(ANDROID + "pivotX", 0))
                py_ = float(ch.get(ANDROID + "pivotY", 0))
                walk(ch, (mat[0] * sx, mat[1] * sy, mat[2] + (px_ * (1 - sx) + tx), mat[3] + (py_ * (1 - sy) + ty)))
            elif tag == "path":
                draw_path(ch, mat)

    def tf(pts, mat):
        return [(x * mat[0] + mat[2], y * mat[1] + mat[3]) for x, y in pts]

    def draw_path(el, mat):
        subs = [tf(sp, mat) for sp in parse_path(el.get(ANDROID + "pathData"))]
        polys = [[(p[0] * scale, p[1] * scale) for p in sp] for sp in subs]

        # 描边（仅上缘那种 stroke 用）
        stroke_w = el.get(ANDROID + "strokeWidth")
        sg = parse_gradient(el.find(ANDROID + "path")) if el.find(ANDROID + "path") is not None else None
        if stroke_w:
            sg = None
            for cand in el:
                if cand.tag == AAPT + "attr" and cand.get("name") == "android:strokeColor":
                    sg = read_grad(child(cand, "gradient"))
            layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
            d = ImageDraw.Draw(layer)
            wid = max(1, int(round(float(stroke_w) * scale)))
            for sp in polys:
                for k in range(len(sp) - 1):
                    a, b = sp[k], sp[k + 1]
                    if sg:
                        yv = (a[1] / scale + b[1] / scale) / 2
                        c = grad_color(sg, yv)
                    else:
                        c = parse_color(el.get(ANDROID + "strokeColor", "#FFFFFFFF"))
                    d.line([a, b], fill=c, width=wid)
            img.alpha_composite(layer)
            return

        fill = el.get(ANDROID + "fillColor")
        g = None
        if fill is None or fill == "#00000000":
            for cand in el:
                if cand.tag == AAPT + "attr" and cand.get("name") == "android:fillColor":
                    g = read_grad(child(cand, "gradient"))
            if g is None:
                return
        mask = Image.new("L", img.size, 0)
        md = ImageDraw.Draw(mask)
        for sp in polys:
            md.polygon(sp, fill=255)
        if g:
            img.alpha_composite(fill_gradient(img, mask, g, scale))
        else:
            c = parse_color(fill)
            layer = Image.new("RGBA", img.size, c)
            layer.putalpha(Image.composite(Image.new("L", img.size, c[3]), Image.new("L", img.size, 0), mask))
            img.alpha_composite(layer)

    walk(root, (1.0, 1.0, 0.0, 0.0))
    return img.resize((target_px, target_px), Image.LANCZOS)


def main():
    BRAND = (230, 0, 18, 255)
    pad, gap = 30, 26
    sizes = [(int(30 * 3), "30dp @3x"), (int(38 * 3), "38dp @3x"), (260, "放大 260px")]
    total_w = pad * 2 + sum(s for s, _ in sizes) + gap * (len(sizes) - 1)
    canvas = Image.new("RGBA", (total_w, 340), BRAND)
    x = pad
    for s, tag in sizes:
        im = render(s, (0, 0, 0, 0))
        canvas.alpha_composite(im, (x, 30))
        x += s + gap
    canvas.convert("RGB").save(OUT)
    print("saved", OUT, canvas.size)


if __name__ == "__main__":
    main()
