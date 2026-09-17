# -*- coding: utf-8 -*-
"""把 output/course_card.html 的「截图模式」导成 PNG，并拼成两张总览图。

用法：
    python scripts/export_widget_data.py     # 先刷新预览页数据
    python scripts/shot_widget.py            # 再导出图片

产物：
    output/预览图/*.png          每张单独一张
    output/预览图/桌面实景.png    4 种状态并排
    output/预览图/组件本体.png    组件本体 + 本周课表
"""

import subprocess
import sys
import urllib.parse
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
PAGE = ROOT / "output" / "course_card.html"
OUT_DIR = ROOT / "output" / "预览图"

CHROME_CANDIDATES = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
]

FONT_CANDIDATES = [
    r"C:\Windows\Fonts\msyh.ttc",
    r"C:\Windows\Fonts\simhei.ttf",
]

BG = (242, 244, 247)
INK = (17, 24, 39)
MUTED = (107, 114, 128)

# name, hash, 窗口宽, 窗口高, 说明
SHOTS = [
    ("手机-在课中",   "#shot&only=phone&d=1&t=10:10&w=6", 392, 850, "正在上课 · 进度条 + 还剩几分钟"),
    ("手机-课间",     "#shot&only=phone&d=1&t=12:00&w=6", 392, 850, "课间 · 主角换成下一节"),
    ("手机-今天没课", "#shot&only=phone&d=6&t=10:10&w=6", 392, 850, "今天没课 · 全部降成灰阶"),
    ("手机-已上完",   "#shot&only=phone&d=1&t=19:30&w=6", 392, 850, "当天的课上完了"),
    ("组件-4x2",      "#shot&only=card&d=1&t=10:10&w=6&s=0", 656, 352, "标准尺寸 4×2"),
    ("组件-2x2",      "#shot&only=card&d=1&t=10:10&w=6&s=1", 320, 304, "最小尺寸 2×2 · 自动省略"),
    ("组件-4x3",      "#shot&only=card&d=1&t=10:10&w=6&s=2", 656, 496, "加大尺寸 4×3"),
    ("本周课表",      "#shot&only=week&tab=week&d=1&w=6", 688, 688, "整周课表 · 今天整列铺淡强调色"),
]

DESKTOP_ROW = ["手机-在课中", "手机-课间", "手机-今天没课", "手机-已上完"]
CARD_ROW = ["组件-4x2", "组件-2x2", "组件-4x3", "本周课表"]


def find_chrome():
    for path in CHROME_CANDIDATES:
        if Path(path).is_file():
            return path
    raise SystemExit("找不到 Chrome 或 Edge，没法截图。")


def find_font(size):
    for path in FONT_CANDIDATES:
        if Path(path).is_file():
            try:
                return ImageFont.truetype(path, size)
            except OSError:
                continue
    return ImageFont.load_default()


def page_url(fragment):
    return "file:///" + urllib.parse.quote(str(PAGE).replace("\\", "/")) + fragment


def shoot(chrome, name, fragment, width, height):
    target = OUT_DIR / (name + ".png")
    cmd = [
        chrome,
        "--headless=new",
        "--disable-gpu",
        "--hide-scrollbars",
        "--force-device-scale-factor=1",
        "--virtual-time-budget=2500",
        "--window-size=%d,%d" % (width, height),
        "--screenshot=" + str(target),
        page_url(fragment),
    ]
    subprocess.run(cmd, capture_output=True)
    if not target.is_file():
        raise SystemExit("截图失败：" + name)
    image = Image.open(target).convert("RGB")
    if image.width < width or image.height < height:
        image = image.crop((0, 0, min(width, image.width), min(height, image.height)))
        image.save(target)
    return image


def sheet(items, columns, title, path, tile_pad=24, label_h=30):
    """items: [(图片, 说明)]，按 columns 列拼贴，每张下面一行说明。"""
    font = find_font(17)
    rows = (len(items) + columns - 1) // columns
    col_w = [0] * columns
    row_h = [0] * rows
    for index, (image, _) in enumerate(items):
        col, row = index % columns, index // columns
        col_w[col] = max(col_w[col], image.width)
        row_h[row] = max(row_h[row], image.height + label_h)
    width = tile_pad * (columns + 1) + sum(col_w)
    height = tile_pad * 2 + 46 + sum(row_h)
    canvas = Image.new("RGB", (width, height), BG)
    draw = ImageDraw.Draw(canvas)
    draw.text((tile_pad, tile_pad), title, font=find_font(20), fill=INK)
    y = tile_pad + 46
    for row in range(rows):
        x = tile_pad
        for col in range(columns):
            index = row * columns + col
            if index >= len(items):
                break
            image, caption = items[index]
            canvas.paste(image, (x, y))
            draw.text((x + 2, y + image.height + 6), caption, font=font, fill=MUTED)
            x += col_w[col] + tile_pad
        y += row_h[row] + tile_pad
    canvas.save(path)
    return canvas.size


def main():
    chrome = find_chrome()
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    made = {}
    for name, fragment, width, height, _ in SHOTS:
        made[name] = shoot(chrome, name, fragment, width, height)
        print("已截图 %-14s %s" % (name, made[name].size))

    caption = {item[0]: item[4] for item in SHOTS}
    desk = [(made[n], "%s — %s" % (n, caption[n])) for n in DESKTOP_ROW]
    print("桌面实景", sheet(desk, 4, "桌面实景 · 同一张壁纸上的四种状态",
                            OUT_DIR / "桌面实景.png"))
    card = [(made[n], "%s — %s" % (n, caption[n])) for n in CARD_ROW]
    print("组件本体", sheet(card, 2, "组件本体与本周课表 · 与手机上是同一套配色字号",
                            OUT_DIR / "组件本体.png"))


if __name__ == "__main__":
    main()
