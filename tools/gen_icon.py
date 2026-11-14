#!/usr/bin/env python3
"""One-time launcher asset generation. APK builds use the PNGs, not Pillow."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
FONT = Path("/usr/share/fonts/google-noto-sans-cjk-fonts/NotoSansCJK-Bold.ttc")
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def main():
    canvas = Image.new("RGB", (512, 512), "white")
    draw = ImageDraw.Draw(canvas)
    font = ImageFont.truetype(str(FONT), 400, index=2)  # Noto Sans CJK SC Bold
    left, top, right, bottom = draw.textbbox((0, 0), "签", font=font)
    position = ((512 - (right - left)) / 2 - left, (512 - (bottom - top)) / 2 - top)
    draw.text(position, "签", font=font, fill="#F57C00")
    for density, size in SIZES.items():
        target = ROOT / "res" / ("mipmap-" + density) / "ic_launcher.png"
        target.parent.mkdir(parents=True, exist_ok=True)
        canvas.resize((size, size), Image.Resampling.LANCZOS).save(target, "PNG")
        print(f"{target}: {size}x{size}")


if __name__ == "__main__":
    main()
