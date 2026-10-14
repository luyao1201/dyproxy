# 生成「我是/歪果仁」文字图标（多密度 mipmap）
from PIL import Image, ImageDraw, ImageFont
import os

FONT_CANDIDATES = [r"C:\Windows\Fonts\msyhbd.ttc", r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\simhei.ttf"]
font_path = next((p for p in FONT_CANDIDATES if os.path.exists(p)), None)

def make(size):
    S = size * 4  # 超采样
    im = Image.new("RGBA", (S, S), (0,0,0,0))
    d = ImageDraw.Draw(im)
    # 圆角方形 + 对角渐变（深蓝->紫）
    radius = int(S*0.22)
    grad = Image.new("RGB", (S, S))
    gd = ImageDraw.Draw(grad)
    c1 = (28, 42, 120)   # 深蓝
    c2 = (122, 46, 168)  # 紫
    for y in range(S):
        t = y / (S-1)
        gd.line([(0,y),(S,y)], fill=(int(c1[0]+(c2[0]-c1[0])*t), int(c1[1]+(c2[1]-c1[1])*t), int(c1[2]+(c2[2]-c1[2])*t)))
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0,0,S-1,S-1], radius=radius, fill=255)
    im.paste(grad, (0,0), mask)
    d = ImageDraw.Draw(im)
    # 文字两行：我是 / 歪果仁
    f1 = ImageFont.truetype(font_path, int(S*0.30))
    f2 = ImageFont.truetype(font_path, int(S*0.30))
    def draw_c(txt, font, cy):
        bb = d.textbbox((0,0), txt, font=font)
        w = bb[2]-bb[0]; h = bb[3]-bb[1]
        x = (S-w)//2 - bb[0]
        y = cy - h//2 - bb[1]
        # 阴影
        d.text((x+S*0.01, y+S*0.012), txt, font=font, fill=(0,0,0,110))
        d.text((x, y), txt, font=font, fill=(255,255,255,255))
    draw_c("我是", f1, int(S*0.34))
    draw_c("歪果仁", f2, int(S*0.68))
    return im.resize((size,size), Image.LANCZOS)

dens = {"mdpi":48, "hdpi":72, "xhdpi":96, "xxhdpi":144, "xxxhdpi":192}
for d, s in dens.items():
    p = f"app/src/main/res/mipmap-{d}"
    os.makedirs(p, exist_ok=True)
    make(s).save(os.path.join(p, "ic_launcher.png"))
print("icons done")
