"""
内页顶栏 改前 / 改后 实景对比（用真实的 ic_logo_mark.xml 渲染「改后」）

改前（v1.21）：白色玻璃圆 + 1dp 白描边 + 内缩红方块
                → 方压在圆里、红压在红上，只剩一条白线 + 一个内框
改后（v1.22）：一体化立体徽章，外圈全部去掉
"""

import importlib.util
import math
import os

from PIL import Image, ImageDraw, ImageFont

BRAND = (230, 0, 18)
OUT = r"C:\Users\simon\Desktop\MindBall\docs\logo-before-after.png"
SPEC = os.path.join(os.path.dirname(__file__), "render-vector.py")

spec = importlib.util.spec_from_file_location("rv", SPEC)
rv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rv)


def font(size, bold=False):
    for n in (("msyhbd.ttc",) if bold else ("msyh.ttc",)):
        p = os.path.join(r"C:\Windows\Fonts", n)
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except OSError:
                pass
    return ImageFont.load_default()


def draw_old(img, cx, cy, d):
    """v1.21：白玻璃圆 + 柔光 + 1dp 白描边 + 内缩红方块"""
    r = d / 2
    box = [cx - r, cy - r, cx + r, cy + r]
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).ellipse(box, fill=255)

    g = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(g).ellipse(box, fill=(255, 255, 255, 64))
    img.alpha_composite(g)

    glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gcx, gcy, gr = cx, cy - r * 0.36, d * 0.32
    for i in range(36, 0, -1):
        t = i / 36
        gd.ellipse([gcx - gr * t, gcy - gr * t, gcx + gr * t, gcy + gr * t],
                   fill=(255, 255, 255, int(round(64 * (1 - t) ** 2))))
    glow.putalpha(Image.composite(glow.getchannel("A"), Image.new("L", img.size, 0), mask))
    img.alpha_composite(glow)

    ImageDraw.Draw(img).ellipse(box, outline=(255, 255, 255, 168),
                                width=max(1, int(round(d * 0.03))))

    inner = d * 0.588                      # 34dp 里 padding 7dp → 内容 20/34
    rad = int(round(inner * 0.24))
    ImageDraw.Draw(img).rounded_rectangle(
        [cx - inner / 2, cy - inner / 2, cx + inner / 2, cy + inner / 2],
        radius=rad, fill=BRAND + (255,))
    ro, ri = 0.2963 * inner, 0.1667 * inner
    ImageDraw.Draw(img).arc([cx - ro, cy - ro, cx + ro, cy + ro], start=50, end=310,
                            fill=(255, 255, 255, 255), width=max(1, int(round(ro - ri))))


def draw_new(img, cx, cy, dp_px):
    """v1.22：真实矢量图渲染（120 视口里板面占 100）"""
    tile = rv.render(round(dp_px * 120 / 100), (0, 0, 0, 0))
    x, y = int(round(cx - tile.width / 2)), int(round(cy - tile.height / 2))
    img.alpha_composite(tile, (x, y))


def bar(w, h, painter, dp, tag):
    im = Image.new("RGBA", (w, h), BRAND + (255,))
    d = ImageDraw.Draw(im)
    ax = int(h * 0.40)
    d.line([(ax + h * 0.09, h * 0.37), (ax - h * 0.05, h * 0.5), (ax + h * 0.09, h * 0.63)],
           fill=(255, 255, 255, 255), width=max(2, int(h * 0.032)), joint="curve")
    bx = int(h * 0.95) + dp * 3 / 2
    painter(im, bx, h / 2, dp * 3)
    d.text((bx + dp * 3 / 2 + h * 0.26, h / 2), "语音速记", font=font(int(h * 0.30), True),
           fill=(255, 255, 255, 255), anchor="lm")
    d.text((w - int(h * 0.26), h / 2), tag, font=font(int(h * 0.19)),
           fill=(255, 224, 226, 255), anchor="rm")
    return im


def main():
    W, BH = 780, 174            # 58dp @3x
    canvas = Image.new("RGB", (W + 56, 900), (250, 250, 251))
    d = ImageDraw.Draw(canvas)
    d.text((28, 20), "内页顶栏 · 品牌标（草记 C 标）改版", font=font(23, True), fill=(24, 24, 26))
    d.text((28, 54), "上：v1.21 白玻璃圆环 + 1dp 白描边 + 内缩红方块   下：v1.22 一体化立体徽章",
           font=font(16), fill=(110, 110, 112))

    canvas.paste(bar(W, BH, draw_old, 34, "v1.21").convert("RGB"), (28, 90))
    canvas.paste(bar(W, BH, draw_new, 30, "v1.22").convert("RGB"), (28, 288))

    # 细节放大：同尺寸截取顶栏左侧
    crop = (28 + int(BH * 0.9), 90, 28 + int(BH * 0.9) + BH * 1.4, 90 + BH)
    old_z = canvas.crop(crop).resize((int(BH * 1.4 * 1.6), int(BH * 1.6)), Image.LANCZOS)
    crop2 = (28 + int(BH * 0.9), 288, 28 + int(BH * 0.9) + BH * 1.4, 288 + BH)
    new_z = canvas.crop(crop2).resize(old_z.size, Image.LANCZOS)
    canvas.paste(old_z, (28, 500))
    canvas.paste(new_z, (28 + old_z.width + 24, 500))
    d.text((28, 812), "放大对比：改前是一圈白线 + 内框；改后靠渐变、顶光、底压与落影立起来",
           font=font(16), fill=(90, 90, 92))

    canvas.save(OUT)
    print("saved", OUT, canvas.size)


if __name__ == "__main__":
    main()
