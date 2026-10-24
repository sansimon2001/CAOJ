"""
商店素材生成（图标 + 特色图）

输出到 docs/：
- icon-512.png           应用商店图标（华为/小米/OPPO/vivo/应用宝通用，512×512，无透明）
- icon-1024.png          Google Play 图标（1024×1024）
- feature-1024x500.png   Google Play 特色图片

图标沿与应用内一致的视觉：正红 #E60012 满底 + 白色大写 C
（C 的几何比例照 ic_logo_mark：中线半径 0.232、环宽 0.13、开口 ±50°）
"""

from PIL import Image, ImageDraw, ImageFont
import math
import os

BRAND = (230, 0, 18)
WHITE = (255, 255, 255)
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)))


def load_font(size, bold=True):
    names = ("msyhbd.ttc", "msyh.ttc") if bold else ("msyh.ttc",)
    for name in names:
        p = os.path.join(r"C:\Windows\Fonts", name)
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except OSError:
                pass
    return ImageFont.load_default()


def draw_mark(draw, cx, cy, d):
    """在 (cx,cy) 处画直径 d 的白色 C"""
    r = d * 0.232
    w = max(3, int(d * 0.14))
    draw.arc([cx - r, cy - r, cx + r, cy + r], start=50, end=310, fill=WHITE, width=w)


def make_icon(size, out_name, padding_ratio=0.16):
    img = Image.new("RGB", (size, size), BRAND)
    d = ImageDraw.Draw(img)
    d.ellipse([0, 0, size - 1, size - 1], fill=BRAND)  # 保证无透明、满版
    d = ImageDraw.Draw(img)
    inner = size * (1 - padding_ratio * 2)
    draw_mark(d, size / 2, size / 2, inner)
    path = os.path.join(OUT, out_name)
    img.save(path)
    print("saved", path)


def make_feature(w=1024, h=500):
    img = Image.new("RGB", (w, h), BRAND)
    d = ImageDraw.Draw(img)

    # 图标 + 文字
    mark_d = 130
    draw_mark(d, 150, h // 2, mark_d)

    f_title = load_font(72, bold=True)
    f_sub = load_font(30)
    d.text((262, h // 2 - 104), "草记", font=f_title, fill=WHITE)
    d.text((266, h // 2 + 4), "戴上耳机就开始记，说完自动整理成待办", font=f_sub, fill=(255, 226, 228))

    path = os.path.join(OUT, "feature-1024x500.png")
    img.save(path)
    print("saved", path)


if __name__ == "__main__":
    make_icon(512, "icon-512.png")
    make_icon(1024, "icon-1024.png")
    make_feature()
