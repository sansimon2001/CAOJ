"""
内页顶栏 logo「玻璃托底」效果对比图

用途：不装 APK 也能判断改动是否达到「凸显 logo」的目的。
左边还原 v1.9 现状（红标直接压在红底顶栏上，红对红只剩一个白 C），
右边是 v1.10 的做法（半透明白色磨砂圆 + 中心柔光 + 1dp 高光描边 + 内缩的 logo）。

纯 PIL 绘制，参数与 Android 侧保持一致：
- 顶栏底色       #E60012（正红）
- 玻璃本体       白色 40% 透明度
- 中心柔光       白色约 42% → 3%，中心偏上 0.30r，半径 0.42×直径，严格裁剪在圆内
- 高光描边       白色 59%，1dp（此处按 2x 画成 2px）
- logo 内缩      直径的 30% 留白（对应布局 padding 6dp / 34dp）
"""

from PIL import Image, ImageDraw, ImageFont
import math
import os

BRAND = (230, 0, 18)
PANEL_W, PANEL_H = 400, 150
GAP = 40
MARGIN = 40
CANVAS_W = MARGIN * 2 + PANEL_W * 2 + GAP
CANVAS_H = 420

BG = (247, 247, 248)
TEXT_DARK = (26, 26, 26)
TEXT_SUB = (107, 107, 107)
TEXT_FAINT = (158, 158, 158)


def load_font(size: int, bold: bool = False):
    names = ("msyhbd.ttc", "msyh.ttc") if bold else ("msyh.ttc",)
    for name in names:
        path = os.path.join(r"C:\Windows\Fonts", name)
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except OSError:
                pass
    return ImageFont.load_default()


def draw_logo(draw: ImageDraw.ImageDraw, cx: int, cy: int, size: int):
    """红底圆角方块 + 白色大写 C（与 ic_logo_mark 同比例）"""
    r = int(size * 0.24)
    draw.rounded_rectangle(
        [cx - size // 2, cy - size // 2, cx + size // 2, cy + size // 2],
        radius=r, fill=BRAND,
    )
    # C：外径 32 / 内径 18（108 视口）→ 环宽 0.13×size，中线半径 0.232×size
    cr = size * 0.232
    cw = max(2, int(size * 0.13))
    draw.arc(
        [cx - cr, cy - cr, cx + cr, cy + cr],
        start=50, end=310, fill=(255, 255, 255), width=cw,
    )


def draw_glass(canvas: Image.Image, cx: int, cy: int, d: int):
    """白色磨砂圆：本体 + 中心偏上柔光 + 高光描边（全部裁剪在圆内）"""
    rad = d / 2
    x0, y0 = cx - rad, cy - rad
    x1, y1 = cx + rad, cy + rad

    glass = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(glass).ellipse([x0, y0, x1, y1], fill=(255, 255, 255, 64))

    # 径向柔光：先算一张 alpha 蒙版，再整块叠上去，边缘不留像素块
    glow = Image.new("L", canvas.size, 0)
    gp = glow.load()
    gcx, gcy = cx, cy - rad * 0.36
    max_d = d * 0.32
    for y in range(int(y0), int(y1) + 1):
        for x in range(int(x0), int(x1) + 1):
            if math.hypot(x - cx, y - cy) > rad - 1:
                continue                      # 严格裁剪在圆内，不溢出到顶栏
            dist = math.hypot(x - gcx, y - gcy)
            if dist >= max_d:
                continue
            t = 1 - dist / max_d
            gp[x, y] = int(4 + 62 * (t ** 1.5))

    white = Image.new("RGBA", canvas.size, (255, 255, 255, 0))
    white.putalpha(glow)
    glass.alpha_composite(white)

    ImageDraw.Draw(glass).ellipse(
        [x0, y0, x1, y1], outline=(255, 255, 255, 165), width=2
    )
    canvas.alpha_composite(glass)


def main():
    img = Image.new("RGBA", (CANVAS_W, CANVAS_H), BG + (255,))
    d = ImageDraw.Draw(img)
    f_title = load_font(26, bold=True)
    f_sub = load_font(17)
    f_note = load_font(15)
    f_brand = load_font(28, bold=True)

    d.text((MARGIN, 34), "内页顶栏 logo：玻璃托底前后对比", font=f_title, fill=TEXT_DARK)
    d.text((MARGIN, 72), "左 / 现在（v1.9）", font=f_sub, fill=TEXT_SUB)
    d.text((MARGIN + PANEL_W + GAP, 72), "右 / 改后（v1.10）", font=f_sub, fill=TEXT_SUB)

    top = 108
    cy = top + PANEL_H // 2
    for i in range(2):
        x0 = MARGIN + i * (PANEL_W + GAP)
        d.rounded_rectangle([x0, top, x0 + PANEL_W, top + PANEL_H], radius=18, fill=BRAND)

    # 左：现状 —— 红标直接放红底
    cx = MARGIN + 74
    draw_logo(d, cx, cy, 76)
    d.text((cx + 58, cy - 14), "草记", font=f_brand, fill=(255, 255, 255))

    # 右：玻璃托底
    cx2 = MARGIN + PANEL_W + GAP + 74
    draw_glass(img, cx2, cy, 88)
    draw_logo(d, cx2, cy, 52)
    d.text((cx2 + 58, cy - 14), "草记", font=f_brand, fill=(255, 255, 255))

    notes = [
        "红标与红底同色 → 轮廓消失，只剩悬空的白 C",
        "磨砂圆托住红标 → 有边界、有高光、有层次",
    ]
    for i, note in enumerate(notes):
        x0 = MARGIN + i * (PANEL_W + GAP)
        d.text((x0, top + PANEL_H + 22), note, font=f_note, fill=TEXT_SUB)

    d.text(
        (MARGIN, CANVAS_H - 62),
        "实现：drawable/bg_logo_glass.xml（三层叠加，无 blur / 无阴影，零额外合成开销）",
        font=f_note, fill=TEXT_FAINT,
    )

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "logo-glass.png")
    img.convert("RGB").save(out)
    print("saved:", out)


if __name__ == "__main__":
    main()
