# -*- coding: utf-8 -*-
"""生成草记对外分享图（1080x1420，含 GitHub / 百度网盘两个下载二维码）。

只依赖 Pillow + qrcode + numpy，二维码按真实链接生成，排版全部用显式坐标。
改链接只需改下面 GITHUB_URL / BAIDU_URL / BAIDU_CODE 三行。
"""
import os
import pathlib

import numpy as np
import qrcode
from PIL import Image, ImageDraw, ImageFilter, ImageFont

# ---------------------------------------------------------------- 配置
GITHUB_URL = "https://github.com/sansimon2001/CAOJ/releases/latest"
# 可用环境变量覆盖，避免为改两个值动代码：
#   BAIDU_URL="https://pan.baidu.com/s/1xxx?pwd=abcd" BAIDU_CODE=abcd python make-share-card.py
BAIDU_URL = os.environ.get("BAIDU_URL", "").strip()
BAIDU_CODE = os.environ.get("BAIDU_CODE", "").strip()

W, H = 1080, 1420

INK = (26, 26, 26)
INK2 = (107, 107, 107)
INK3 = (158, 158, 158)
RED = (230, 0, 18)
CARD_STROKE = (234, 234, 234)

BOLD = "C:/Windows/Fonts/msyhbd.ttc"
REG = "C:/Windows/Fonts/msyh.ttc"


def font(path, size):
    return ImageFont.truetype(path, size)


# ---------------------------------------------------------------- 背景
def background():
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    base = np.full((H, W, 3), 249.0, dtype=np.float32)
    # 顶部略亮
    base += (255.0 - 249.0) * np.clip(1.0 - yy / H, 0, 1)[..., None]

    for (cx, cy, r, color, peak) in [
        (W * 0.86, -60, 760, RED, 0.085),          # 右上正红柔光
        (-80, H * 0.96, 820, (70, 70, 82), 0.075),  # 左下中性柔光
    ]:
        d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
        a = np.clip(1.0 - d / r, 0, 1) ** 2.2 * peak
        for i in range(3):
            base[..., i] = base[..., i] * (1 - a) + color[i] * a

    return Image.fromarray(np.clip(base, 0, 255).astype(np.uint8), "RGB").convert("RGBA")


def drop_shadow(canvas, box, radius, blur, dy, alpha):
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).rounded_rectangle(
        [box[0], box[1] + dy, box[2], box[3] + dy], radius, fill=(16, 16, 24, alpha))
    canvas.alpha_composite(layer.filter(ImageFilter.GaussianBlur(blur)))


# ---------------------------------------------------------------- 二维码
def make_qr(data, size):
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, border=2)
    qr.add_data(data)
    qr.make(fit=True)
    total = qr.modules_count + 4
    qr.box_size = max(2, size // total)          # 整数像素，不缩放，模块才等宽
    img = qr.make_image(fill_color=INK, back_color=(255, 255, 255)).convert("RGB")
    pad = Image.new("RGB", (size, size), (255, 255, 255))
    pad.paste(img, ((size - img.width) // 2, (size - img.height) // 2))
    return pad


def placeholder(size):
    img = Image.new("RGB", (size, size), (255, 255, 255))
    d = ImageDraw.Draw(img)
    step = 16
    for x in range(0, size, step):
        for y in range(0, size, step):
            if (x // step + y // step) % 2 == 0:
                d.rectangle([x, y, x + step - 2, y + step - 2], fill=(246, 246, 247))
    d.rounded_rectangle([6, 6, size - 7, size - 7], 14, outline=(210, 210, 212), width=2)
    return img


# ---------------------------------------------------------------- 绘制
canvas = background()
draw = ImageDraw.Draw(canvas)

# 顶部 logo
logo = Image.open("docs/logo-1024.png").convert("RGBA").resize((112, 112), Image.LANCZOS)
canvas.alpha_composite(logo, ((W - 112) // 2, 88))

draw.text((W // 2, 228), "草记", font=font(BOLD, 92), fill=INK, anchor="ma")
draw.text((W // 2, 360), "戴上耳机就开始记", font=font(REG, 46), fill=INK2, anchor="ma")
draw.text((W // 2, 440), "记录 → 转写 → 摘要 · 待办 → 归项目",
          font=font(REG, 27), fill=INK3, anchor="ma")

# 两张渠道卡
CARD_TOP, CARD_H = 520, 660
cards = [
    dict(x=80, tag="GitHub", tip="github.com/sansimon2001/CAOJ",
         data=GITHUB_URL, code=""),
    dict(x=560, tag="百度网盘",
         tip=(f"提取码 {BAIDU_CODE} · 扫码自动填入") if BAIDU_CODE else "待补充网盘链接",
         data=BAIDU_URL, code=BAIDU_CODE),
]

QR = 372
for c in cards:
    box = [c["x"], CARD_TOP, c["x"] + 440, CARD_TOP + CARD_H]
    drop_shadow(canvas, box, 34, 26, 10, 26)
    ImageDraw.Draw(canvas).rounded_rectangle(box, 34, fill=(255, 255, 255), outline=CARD_STROKE, width=2)

    cx = c["x"] + 220
    # 胶囊标签
    f_tag = font(BOLD, 30)
    tw = draw.textlength(c["tag"], font=f_tag)
    pw, ph = tw + 56, 56
    py = CARD_TOP + 46
    draw.rounded_rectangle([cx - pw / 2, py, cx + pw / 2, py + ph], ph / 2, fill=(247, 247, 248))
    draw.text((cx, py + ph / 2 - 2), c["tag"], font=f_tag, fill=INK, anchor="mm")
    # 标签里的小红点
    draw.ellipse([cx - pw / 2 + 18, py + ph / 2 - 4, cx - pw / 2 + 26, py + ph / 2 + 4], fill=RED)

    qy = CARD_TOP + 132
    qimg = make_qr(c["data"], QR) if c["data"] else placeholder(QR)
    canvas.paste(qimg, (int(cx - QR / 2), qy))

    draw.text((cx, qy + QR + 46), "扫码下载 APK", font=font(REG, 32), fill=INK, anchor="ma")
    draw.text((cx, qy + QR + 100), c["tip"], font=font(REG, 23), fill=INK3, anchor="ma")

# 底部
draw.text((W // 2, 1272), "v1.22 · 5.4 MB · Android 8.0 及以上",
          font=font(REG, 30), fill=INK2, anchor="ma")
draw.text((W // 2, 1332), "Android 端 · 无需注册 · 录音与转写数据不上传到我们的服务器",
          font=font(REG, 22), fill=INK3, anchor="ma")

out = canvas.convert("RGB")
pathlib.Path("docs/share-card.png").write_bytes(b"")
out.save("docs/share-card.png", optimize=True)
out.save(r"C:/Users/simon/Desktop/草记-分享图.png", optimize=True)
print("尺寸:", out.size, "| 体积:", pathlib.Path("docs/share-card.png").stat().st_size, "B")
print("GitHub QR:", GITHUB_URL)
print("百度网盘 :", "已生成" if BAIDU_URL else "占位（待填链接）")
