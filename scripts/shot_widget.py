# -*- coding: utf-8 -*-
"""把 output/course_card.html 的「截图模式」导成 PNG，并拼成几张总览图。

用法：
    python scripts/export_widget_data.py     # 先刷新预览页数据
    python scripts/shot_widget.py            # 再导出图片

产物在 output/预览图/：
    单张 PNG          手机实景 4 张、今日组件的每种尺寸×状态、周视图浅深两套
    桌面实景.png      同一张壁纸上的四种状态
    今日组件-状态.png 4×2 的五种状态 + 深色
    今日组件-尺寸.png 三种尺寸（含期末周）
    今日组件-全矩阵.png 4×2 / 2×2 / 4×3 × 在课 / 课间 / 没课 / 已上完 / 期末周，共 15 张
    本周课表.png      浅色 / 深色 / 期末周
    色卡对比.png      四张出厂色卡下的同一张周视图
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

# 组件本体按 2 倍放大截图：4×2 = 328×176dp，2×2 = 160×152dp，4×3 = 328×248dp，周视图 344×344dp
CARD_SCALE = 2
SIZES = [
    ("4x2", 328, 176, 0),
    ("2x2", 160, 152, 1),
    ("4x3", 328, 248, 2),
]
WEEK_PX = 344 * CARD_SCALE

# 四张出厂色卡各来一张周视图，拼成「色卡对比.png」
PALETTE_SHOTS = [
    ("default", "默认"),
    ("morandi", "莫兰迪"),
    ("macaron", "马卡龙"),
    ("bright", "明快"),
]

# 五种状态：和 Java 侧 TodayWidgetProvider 的分支一一对应
STATES = [
    ("在课", "d=1&t=10:10&w=6", "在课 · 药丸 + 进度条 + 还剩几分钟"),
    ("课间", "d=1&t=12:00&w=6", "课间 · 主角换成下一节"),
    ("没课", "d=6&t=10:10&w=6", "今天没课 · 只剩灰阶文案"),
    ("已上完", "d=1&t=19:30&w=6", "今天的课已经上完了"),
    ("期末周", "d=3&t=15:00&w=6&finals=1", "期末周 · 考试中 + 考试进度"),
]

PHONE_STATES = [
    ("在课中", "d=1&t=10:10&w=6", "正在上课 · 药丸 + 进度条 + 还剩几分钟"),
    ("课间", "d=1&t=12:00&w=6", "课间 · 主角换成下一节"),
    ("今天没课", "d=6&t=10:10&w=6", "今天没课 · 全部降成灰阶"),
    ("已上完", "d=1&t=19:30&w=6", "当天的课上完了"),
]


def build_shots():
    """(文件名词干, hash, 宽, 高, 说明)。文件名沿用 output/预览图/ 里已有的那套。"""
    shots = []
    for name, fragment, caption in PHONE_STATES:
        shots.append(("手机-" + name, "#shot&only=phone&" + fragment, 392, 850, caption))
    for label, width, height, index in SIZES:
        for state, fragment, caption in STATES:
            suffix = "" if label == "4x2" else "-" + label
            name = "今日%s-%s" % (suffix, state)
            shots.append((name, "#shot&only=card&%s&s=%d" % (fragment, index),
                          width * CARD_SCALE, height * CARD_SCALE,
                          "%s · %s · %s" % (label, state, caption)))
    shots.append(("今日-深色", "#shot&only=card&d=1&t=10:10&w=6&s=0&th=dark",
                  656, 352, "4×2 · 深色主题"))
    shots.append(("今日-2x2-深色", "#shot&only=card&d=1&t=10:10&w=6&s=1&th=dark",
                  320, 304, "2×2 · 深色主题"))
    shots.append(("周视图-浅色", "#shot&only=week&tab=week&th=light", WEEK_PX, WEEK_PX,
                  "5×5 浅色 · 课块连成整块，今天整列铺色"))
    shots.append(("周视图-深色", "#shot&only=week&tab=week&th=dark", WEEK_PX, WEEK_PX,
                  "5×5 深色 · 只换卡片底色，课块文字跟着 ink 走"))
    shots.append(("周视图-期末周", "#shot&only=week&tab=week&finals=1", WEEK_PX, WEEK_PX,
                  "5×5 期末周 · 整块换成考试表"))
    for preset_id, label in PALETTE_SHOTS:
        shots.append(("色卡-%s" % label,
                      "#shot&only=week&tab=week&pal=%s" % preset_id,
                      WEEK_PX, WEEK_PX, "周视图 · 色卡「%s」" % label))
    return shots


SHOTS = build_shots()
CAPTIONS = {name: caption for name, _, _, _, caption in SHOTS}

DESKTOP_ROW = ["手机-在课中", "手机-课间", "手机-今天没课", "手机-已上完"]
STATE_ROW = ["今日-在课", "今日-课间", "今日-没课", "今日-已上完", "今日-期末周", "今日-深色"]
SIZE_ROW = ["今日-在课", "今日-2x2-在课", "今日-4x3-在课",
            "今日-2x2-期末周", "今日-4x3-期末周"]
MATRIX_ROW = [("今日-" + state if label == "4x2" else "今日-%s-%s" % (label, state))
              for state, _, _ in STATES for label, _, _, _ in SIZES]
WEEK_ROW = ["周视图-浅色", "周视图-深色", "周视图-期末周"]
PALETTE_ROW = ["色卡-" + label for _, label in PALETTE_SHOTS]


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


def build_sheets(made):
    sheets = [
        (DESKTOP_ROW, 4, "桌面实景 · 同一张壁纸上的四种状态", "桌面实景.png"),
        (STATE_ROW, 3, "今日课程 · 4×2 的五种状态 + 深色", "今日组件-状态.png"),
        (SIZE_ROW, 3, "今日课程 · 三种尺寸（含期末周）", "今日组件-尺寸.png"),
        (MATRIX_ROW, 5, "今日课程 · 三种尺寸 × 五种状态", "今日组件-全矩阵.png"),
        (WEEK_ROW, 1, "本周课表 · 浅色 / 深色 / 期末周", "本周课表.png"),
        (PALETTE_ROW, 4, "四张出厂色卡 · 同一张课表只换配色", "色卡对比.png"),
    ]
    for names, columns, title, filename in sheets:
        items = [(made[name], "%s — %s" % (name, CAPTIONS[name])) for name in names]
        print("已拼图 %-18s %s" % (filename, sheet(items, columns, title, OUT_DIR / filename)))


def main():
    chrome = find_chrome()
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    made = {}
    for name, fragment, width, height, _ in SHOTS:
        made[name] = shoot(chrome, name, fragment, width, height)
        print("已截图 %-18s %s" % (name, made[name].size))

    build_sheets(made)


if __name__ == "__main__":
    main()