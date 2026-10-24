"""
从 res/drawable/ic_logo_mark.xml 导出独立可用的 logo 图像文件。

产出（默认写到桌面）：
  草记-logo-1024.png   透明底位图，应用市场/文档/封面用
  草记-logo.svg        矢量版，网页/印刷/无限缩放

为什么不是另画一遍：v1.21 的教训是「同一件事写两遍，迟早漂移」。
这里 PNG 用 render-vector.py 的渲染器（按真实 pathData + 渐变坐标绘制），
SVG 直接把同一份 XML 的 group / path / gradient 翻译过去，两者必然一致。

唯一的不同是**投影**：App 内的 VectorDrawable 没有 blur，只能拿 4 层同心缩放
硬叠出边缘过渡（贴在红顶栏上够用）；独立成图时，那 4 层在透明底上会露出
4 道同心的灰弧。位图有真高斯模糊、SVG 有 feDropShadow，所以导出时换成
真模糊投影——同一设计意图，各自介质里最合适的实现。
"""

import importlib.util
import os
import re
import xml.etree.ElementTree as ET

from PIL import Image, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))

# 复用已有的矢量渲染器（它已经过 C 开口方向、渐变区间等数值校验）
spec = importlib.util.spec_from_file_location("render_vector", os.path.join(HERE, "render-vector.py"))
rv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rv)

SRC = os.path.join(HERE, "..", "app", "src", "main", "res", "drawable", "ic_logo_mark.xml")
DESKTOP = os.path.join(os.path.expanduser("~"), "Desktop")

ANDROID = "{http://schemas.android.com/apk/res/android}"
AAPT = "{http://schemas.android.com/aapt}"

# 落影 4 层的 alpha。字母阴影是 #424A0003，RGB 相同但 alpha 不同，必须区分开
SHADOW_ALPHAS = ("0C", "11", "18", "22")
SHADOW_RGB = "4A0003"
SHADOW_OPACITY = 0.52


# ---------------------------------------------------------------- SVG 导出

def split_color(c):
    """#AARRGGBB / #RRGGBB → (color, alpha0-1)"""
    c = c.strip().lstrip("#")
    if len(c) == 8:
        a, r, g, b = c[:2], c[2:4], c[4:6], c[6:8]
        return "#" + r + g + b, int(a, 16) / 255.0
    return "#" + c, 1.0


def is_shadow_color(c):
    """落影层（区别于字母阴影 #424A0003）"""
    if not c:
        return False
    h = c.strip().lstrip("#").upper()
    return len(h) == 8 and h[2:] == SHADOW_RGB and h[:2] in SHADOW_ALPHAS


def grad_to_svg(el, gid, defs):
    """aapt:attr 里的 gradient → <linearGradient>，坐标用 userSpaceOnUse 原样带过去"""
    gx1 = el.get(ANDROID + "startX", "0")
    gy1 = el.get(ANDROID + "startY", "0")
    gx2 = el.get(ANDROID + "endX", "0")
    gy2 = el.get(ANDROID + "endY", "1")
    stops = []
    for it in el:
        if it.tag.split("}")[-1] != "item":
            continue
        col, a = split_color(it.get(ANDROID + "color"))
        off = float(it.get(ANDROID + "offset", 0))
        stops.append(
            '      <stop offset="%s" stop-color="%s"%s/>'
            % (("0" if off == 0 else ("1" if off == 1 else "%g" % off)), col,
               "" if a >= 1 else ' stop-opacity="%g"' % round(a, 4))
        )
    defs.append(
        '    <linearGradient id="%s" gradientUnits="userSpaceOnUse" '
        'x1="%s" y1="%s" x2="%s" y2="%s">\n%s\n    </linearGradient>'
        % (gid, gx1, gy1, gx2, gy2, "\n".join(stops))
    )
    return "url(#%s)" % gid


def build_svg():
    root = ET.parse(SRC).getroot()
    vw, vh = root.get(ANDROID + "viewportWidth"), root.get(ANDROID + "viewportHeight")
    defs, body = [], []
    n = [0]
    plate = [None]

    def walk(node, indent):
        pad = "  " * indent
        for ch in node:
            tag = ch.tag.split("}")[-1]
            if tag == "group":
                sx = float(ch.get(ANDROID + "scaleX", 1))
                sy = float(ch.get(ANDROID + "scaleY", 1))
                tx = float(ch.get(ANDROID + "translateX", 0))
                ty = float(ch.get(ANDROID + "translateY", 0))
                px = float(ch.get(ANDROID + "pivotX", 0))
                py = float(ch.get(ANDROID + "pivotY", 0))
                # 落影层整组丢掉，改用 feDropShadow（见文件头说明）
                kids = [c for c in ch if c.tag.split("}")[-1] == "path"]
                if kids and all(is_shadow_color(c.get(ANDROID + "fillColor")) for c in kids):
                    continue
                # Android：以 pivot 为中心缩放，再平移。SVG 写成 translate 后接 scale 等价
                ax, ay = px * (1 - sx) + tx, py * (1 - sy) + ty
                body.append('%s<g transform="translate(%.3f,%.3f) scale(%.4f,%.4f)">'
                            % (pad, ax, ay, sx, sy))
                walk(ch, indent + 1)
                body.append("%s</g>" % pad)
            elif tag == "path":
                if is_shadow_color(ch.get(ANDROID + "fillColor")) and not list(ch):
                    continue
                d = ch.get(ANDROID + "pathData")
                attrs = []
                fill = ch.get(ANDROID + "fillColor")
                stroke = ch.get(ANDROID + "strokeColor")
                sw = ch.get(ANDROID + "strokeWidth")
                for cand in ch:
                    if cand.tag != AAPT + "attr":
                        continue
                    grad = rv.child(cand, "gradient")
                    if grad is None:
                        continue
                    gid = "grad%d" % n[0]
                    n[0] += 1
                    url = grad_to_svg(grad, gid, defs)
                    if cand.get("name") == "android:fillColor":
                        attrs.append('fill="%s"' % url)
                        # 第一块带渐变的 path = 底板，记下轮廓，用来垫一层模糊投影
                        if plate[0] is None:
                            plate[0] = d
                    elif cand.get("name") == "android:strokeColor":
                        attrs.append('stroke="%s"' % url)
                if fill and not any(a.startswith("fill=") for a in attrs):
                    col, a = split_color(fill)
                    attrs.append('fill="%s"' % col)
                    if a < 1:
                        attrs.append('fill-opacity="%g"' % round(a, 4))
                if stroke and not any(a.startswith("stroke=") for a in attrs):
                    col, a = split_color(stroke)
                    attrs.append('stroke="%s"' % col)
                    if a < 1:
                        attrs.append('stroke-opacity="%g"' % round(a, 4))
                if sw:
                    attrs.append('stroke-width="%s"' % sw)
                body.append("%s<path d=\"%s\" %s/>" % (pad, d, " ".join(attrs)))
            elif tag == "clip-path":
                # 本图标未使用。出现就明确报错，避免静默少画一层
                raise SystemExit("ic_logo_mark.xml 出现了未支持的 <clip-path>，导出器需扩展")

    walk(root, 2)
    assert plate[0], "没找到底板路径，投影没生成"
    defs.insert(
        0,
        "    <!-- App 内 VectorDrawable 无 blur，用 4 层同心缩放叠投影；"
        "SVG 有滤镜，这里用真模糊。\n"
        "         投影必须是独立的一层：若把 filter 挂在板面自己身上，"
        "整块板面会被栅格化，\n"
        "         渐变会被截断出色带（实测绿色通道会掉到 0 再跳回来） -->\n"
        '    <filter id="soft" x="-30%" y="-30%" width="160%" height="170%">\n'
        '      <feGaussianBlur stdDeviation="3.4"/>\n'
        "    </filter>",
    )
    # 垫在最底：同轮廓 → 下移 4 → 模糊 → 半透明
    body.insert(
        0,
        f'    <path d="{plate[0]}" fill="#4A0003" fill-opacity="{SHADOW_OPACITY}" '
        f'transform="translate(0 4)" filter="url(#soft)"/>',
    )
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" '
        'viewBox="0 0 %s %s">\n'
        "  <!-- 草记品牌徽章：由 res/drawable/ic_logo_mark.xml 导出，勿手改 -->\n"
        "  <defs>\n%s\n  </defs>\n%s\n</svg>\n"
        % (vw, vh, "\n".join(defs), "\n".join(body))
    )


# ---------------------------------------------------------------- 位图导出

def _render_with(xml_text, px):
    """把临时 XML 喂给渲染器（render 读的是模块级 rv.SRC）"""
    tmp = os.path.join(HERE, "_tmp_logo.xml")
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(xml_text)
    old = rv.SRC
    rv.SRC = tmp
    try:
        return rv.render(px, (0, 0, 0, 0))
    finally:
        rv.SRC = old
        os.remove(tmp)


def render_logo(px=1024):
    raw = open(SRC, encoding="utf-8").read()

    # 1) 本体：把 4 层落影涂成全透明（只涂这 4 个 alpha，别碰字母阴影 #424A0003）
    core_xml = raw
    for a in SHADOW_ALPHAS:
        core_xml = core_xml.replace('android:fillColor="#%s%s"' % (a, SHADOW_RGB),
                                    'android:fillColor="#00000000"')
    core = _render_with(core_xml, px)

    # 2) 投影形状：直接取板面轮廓下移一点再模糊
    #    （不用 XML 里那 4 层落影：它们是为「没有 blur」放大的，拿来做位图遮罩会又大又重）
    vec_open = re.search(r"<vector\b[^>]*>", raw, re.S).group(0)
    plate_d = re.search(r'<path android:pathData="([^"]+)"', raw).group(1)
    shadow_xml = (
        vec_open
        + '\n<group android:translateY="4.0">'
        + '<path android:fillColor="#FF%s" android:pathData="%s" />' % (SHADOW_RGB, plate_d)
        + "</group>\n</vector>"
    )
    mask_img = _render_with(shadow_xml, px)

    # 3) 高斯模糊 → 上色 → 垫在本体下面
    sigma = px * 0.016
    alpha = mask_img.split()[3].filter(ImageFilter.GaussianBlur(sigma))
    shadow = Image.new("RGBA", (px, px), (74, 0, 3, 0))
    shadow.putalpha(alpha.point(lambda v: int(v * SHADOW_OPACITY)))
    return Image.alpha_composite(shadow, core)


def main():
    os.makedirs(DESKTOP, exist_ok=True)

    rv.SS = 3          # 超采样 3 倍后 LANCZOS 缩回，边缘干净
    png_path = os.path.join(DESKTOP, "草记-logo-1024.png")
    img = render_logo(1024)
    img.save(png_path, "PNG", optimize=True)
    print("PNG %s  %s  %d bytes" % (png_path, img.size, os.path.getsize(png_path)))

    svg_path = os.path.join(DESKTOP, "草记-logo.svg")
    svg = build_svg()
    with open(svg_path, "w", encoding="utf-8") as f:
        f.write(svg)
    print("SVG %s  %d chars" % (svg_path, len(svg)))

    # 自检：四角必须完全透明（视口外不能有脏像素），中心必须是红色底板
    px = img.load()
    w, h = img.size
    corners = [px[2, 2], px[w - 3, 2], px[2, h - 3], px[w - 3, h - 3]]
    assert all(c[3] < 8 for c in corners), "四角不透明: %r" % (corners,)
    c = px[w // 2, h // 2]
    assert c[0] > 120 and c[3] > 200, "中心不是红色底板: %r" % (c,)
    # 投影是渐隐的：紧贴板面外应该有淡淡的阴影，而不是 0（说明投影丢了）
    edge = px[w // 2, int(h * 0.945)]
    assert 15 <= edge[3] <= 120, "投影强度不对（应若有若无，不能是实心也不能没有）: %r" % (edge,)
    print("自检通过：四角透明 / 中心 RGB=%r / 底部投影 alpha=%d" % (c[:3], edge[3]))


if __name__ == "__main__":
    main()
