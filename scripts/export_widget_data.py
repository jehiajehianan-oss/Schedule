r"""把爬到的课程表导出成小组件能直接读的数据，并生成一份离线预览页。

用法（在项目根目录执行）：
    python scripts\export_widget_data.py
    python scripts\export_widget_data.py --force   # 查出冲突也照样导出

输入：output/我的课程表.xlsx
输出：
    widget/app/src/main/assets/schedule.json      小组件读取的课程数据
    widget/app/src/main/res/drawable/block_*_*.xml 课程色块的圆角资源（按色板生成）
    output/course_card.html                       手机浏览器可直接打开的预览页

导出前会跑一遍和 coursetable/export_excel.py 相同的冲突检查；发现重复排课或时间冲突就中止导出，
避免把有问题的数据打进小组件。周次互补属于正常安排，只提示不拦截。

三道配色门禁（过不了就中止导出，一个文件都不动）：
    1. 四张色卡里每一对 fill / ink 的对比度必须 >= 4.5:1
    2. Java 侧 Palette.java 的 BUILTIN_FILL / BUILTIN_INK 兜底表必须和这里的 PALETTES 逐套一致
    3. 色块 drawable 由第 1 步的色卡生成，生成后再读回来核对一遍；白色形状资源的圆角也对一遍

配色的唯一真源就是本文件的 PALETTES：它同时决定 JSON 里的 palettes / paletteId（外加兼容字段
courseColors）、res/drawable 里的圆角资源，以及预览页里的色卡。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from datetime import date, datetime, timedelta
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
# coursetable 包在 assets/ 下（和壁纸放一起），两个路径都加上，_ 换位置也不会失效
for candidate in (ROOT, ROOT / "assets"):
    if str(candidate) not in sys.path:
        sys.path.insert(0, str(candidate))

from coursetable.schedule import (  # noqa: E402  需要先把项目根目录加进 sys.path
    BLOCKING_TYPES,
    COURSE_COLUMNS,
    expand_weeks,
    find_problems,
    has_blocking_problem,
)

SOURCE = ROOT / "output" / "我的课程表.xlsx"
ASSET_FILE = ROOT / "widget" / "app" / "src" / "main" / "assets" / "schedule.json"
DRAWABLE_DIR = ROOT / "widget" / "app" / "src" / "main" / "res" / "drawable"
PALETTE_FILE = (ROOT / "widget" / "app" / "src" / "main" / "java"
                / "com" / "jehia" / "schedulewidget" / "Palette.java")
PREVIEW_FILE = ROOT / "output" / "course_card.html"
PREVIEW_TEMPLATE = Path(__file__).resolve().parent / "preview_template.html"
# 预览页里垫的壁纸，按相对路径引用（预览页在 output/，壁纸在 assets/wallpapers/），
# 这样 file:// 直接打开和 preview_on_phone.py 的临时服务都能加载。换壁纸就改这里。
WALLPAPER_RELATIVE = "../assets/wallpapers/【哲风壁纸】卡通-夏日-大树.jpg"

# 默认作息时间。真机上可以在 App 的「作息时间」里改，改这里只影响出厂数据。
SECTION_TIMES = {
    1: "08:00-08:45",
    2: "08:50-09:35",
    3: "09:50-10:35",
    4: "10:40-11:25",
    5: "11:30-12:15",
    6: "13:30-14:15",
    7: "14:20-15:05",
    8: "15:15-16:00",
    9: "16:05-16:50",
    10: "16:55-17:40",
    11: "18:30-19:15",
    12: "19:20-20:05",
}

# 色卡（唯一真源）。四张出厂色卡，外加 App 里那张用户自定义色卡（不进这个文件）。
#
# 每张色卡 10 个槽位，每槽一对 (fill, ink)，顺序就是调度下标（course.color 存的就是它）：
#   fill = 课块底色（手工挑的，不再是 HSVToColor 随机取色相）
#   ink  = 压在 fill 上的文字色，白字或深字，对比度保证 >= 4.5:1
# 颜色只管「认得出是哪门课」，不承载信息。
#
# 两份镜像必须逐项一致，导出时会核对：
#   Java  Palette.BUILTIN_FILL / BUILTIN_INK      （读不到 JSON 时的兜底）
#   Java  res/drawable/block_<下标>_<形状>.xml     （setColorFilter 不可用时的兜底）
PALETTES = [
    {
        "id": "default",
        "name": "默认 · 深色底白字",
        "colors": [
            ("#2563EB", "#FFFFFF"),  # 0 蓝
            ("#5B54D6", "#FFFFFF"),  # 1 靛
            ("#8E45CC", "#FFFFFF"),  # 2 紫
            ("#C13C93", "#FFFFFF"),  # 3 品红
            ("#C93A46", "#FFFFFF"),  # 4 红
            ("#B3541E", "#FFFFFF"),  # 5 赭橙
            ("#E0A63C", "#2A1D04"),  # 6 金：浅底配深字
            ("#23713F", "#FFFFFF"),  # 7 绿
            ("#17706E", "#FFFFFF"),  # 8 青
            ("#7EA9C4", "#0F2230"),  # 9 蓝灰：浅底配深字
        ],
    },
    {
        "id": "morandi",
        "name": "莫兰迪 · 低饱和",
        "colors": [
            ("#5C6B7A", "#FFFFFF"),  # 0 灰蓝
            ("#6E5F7A", "#FFFFFF"),  # 1 灰紫
            ("#7A5F5A", "#FFFFFF"),  # 2 灰棕
            ("#5F7A6B", "#FFFFFF"),  # 3 灰绿
            ("#7A6B5C", "#FFFFFF"),  # 4 卡其
            ("#4F5B66", "#FFFFFF"),  # 5 石板
            ("#7F6A4E", "#FFFFFF"),  # 6 灰金
            ("#55707A", "#FFFFFF"),  # 7 灰青
            ("#6B5F7A", "#FFFFFF"),  # 8 灰紫
            ("#7A6E63", "#FFFFFF"),  # 9 灰褐
        ],
    },
    {
        "id": "macaron",
        "name": "马卡龙 · 浅底深字",
        "colors": [
            ("#F2C4C4", "#2E241C"),  # 0 樱粉
            ("#F7DCA8", "#2E241C"),  # 1 奶黄
            ("#CFE6C4", "#2E241C"),  # 2 抹茶
            ("#B8DCE8", "#2E241C"),  # 3 天蓝
            ("#D2CBEF", "#2E241C"),  # 4 薰衣草
            ("#F4CBDD", "#2E241C"),  # 5 淡玫
            ("#DCE6B4", "#2E241C"),  # 6 青柠
            ("#C7E4DD", "#2E241C"),  # 7 薄荷
            ("#F5D6B8", "#2E241C"),  # 8 杏色
            ("#DAD5EC", "#2E241C"),  # 9 藕荷
        ],
    },
    {
        "id": "bright",
        "name": "明快 · 亮底深字",
        "colors": [
            ("#60A5FA", "#0B1220"),  # 0 亮蓝
            ("#818CF8", "#0B1220"),  # 1 亮靛
            ("#C084FC", "#0B1220"),  # 2 亮紫
            ("#F472B6", "#0B1220"),  # 3 亮粉
            ("#F87171", "#0B1220"),  # 4 亮红
            ("#FB923C", "#0B1220"),  # 5 亮橙
            ("#FACC15", "#0B1220"),  # 6 亮黄
            ("#4ADE80", "#0B1220"),  # 7 亮绿
            ("#2DD4BF", "#0B1220"),  # 8 亮青
            ("#38BDF8", "#0B1220"),  # 9 亮天蓝
        ],
    },
]

DEFAULT_PALETTE_ID = PALETTES[0]["id"]

# 兼容旧名字：默认色卡 = 第一张出厂色卡，色块兜底资源和预览页都用它。
COURSE_COLORS = PALETTES[0]["colors"]
COURSE_FILL = [fill for fill, _ in COURSE_COLORS]

# 所有色卡的 fill（给预览页和一致性核对用）
ALL_FILL = [[fill for fill, _ in palette["colors"]] for palette in PALETTES]
ALL_INK = [[ink for _, ink in palette["colors"]] for palette in PALETTES]

MIN_BLOCK_CONTRAST = 4.5

# 课块圆角（左上, 右上, 右下, 左下）：首格圆上边、尾格圆下边、单格四角都圆、中格是方的。
# 这些数值必须和 res/drawable/block_shape_*.xml 一致，导出时会逐个核对。
BLOCK_SHAPES = {
    "single": "6dp,6dp,6dp,6dp",
    "first": "6dp,6dp,0dp,0dp",
    "last": "0dp,0dp,6dp,6dp",
    "middle": "0dp,0dp,0dp,0dp",
}

# 需要预生成「有色」资源的形状：中格是方的，Java 直接 setBackgroundColor，不用资源。
# 这三个是 setColorFilter 不可用时的兜底（少数 ROM），颜色取最接近的色卡色。
BAKED_SHAPES = ("single", "first", "last")

SEMESTER_WEEKS = 20


def sample_exams():
    """预览页用的示例考试：日期按「本周」算出来，什么时候导出都能演示期末周那几个状态。

    真机上的考试是你在 App 里录的，这里只是为了把「期末周」设计出来。
    """
    monday = date.today() - timedelta(days=date.today().isoweekday() - 1)
    plans = [
        ("e1", "高等数学(2)", 2, "09:00", "11:00", "教三楼101"),
        ("e2", "大学物理(2)", 2, "14:00", "16:00", "综合楼402"),
        ("e3", "马克思主义基本原理", 4, "14:30", "16:30", "教二楼205"),
        ("e4", "数字电子技术", 8, "09:00", "11:00", "综合楼301"),
    ]
    return [
        {
            "id": exam_id,
            "name": name,
            "date": (monday + timedelta(days=offset)).isoformat(),
            "begin": begin,
            "end": end,
            "room": room,
        }
        for exam_id, name, offset, begin, end, room in plans
    ]


def text_of(value):
    """把 Excel 单元格转成去空白的字符串，空值一律返回空串。"""
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return ""
    text = str(value).strip()
    return "" if text.lower() == "nan" else text


def collect_courses():
    """读取 output/我的课程表.xlsx，转成小组件用的课程列表。"""
    frame = pd.read_excel(SOURCE)
    courses = []

    for record in frame.to_dict("records"):
        name = text_of(record.get("课程名称"))
        if not name:
            continue

        weeks_text = text_of(record.get("上课周"))
        courses.append({
            "name": name,
            "weekday": int(record["星期"]),
            "start": int(record["开始节数"]),
            "end": int(record["结束节数"]),
            "teacher": text_of(record.get("老师")),
            "room": text_of(record.get("地点")),
            "weeks": weeks_text,
            "weekList": expand_weeks(weeks_text),
        })

    courses.sort(key=lambda item: (item["weekday"], item["start"], item["name"]))
    # id 要稳定：换一台电脑重跑导出，同一门课也必须拿到同一个 id，App 才能认出「同一条排课」。
    for position, course in enumerate(courses, start=1):
        course["id"] = "c%d" % position
        course["color"] = -1  # -1 = 按课名自动取色；在 App 里改过就写具体下标
        course["colorHex"] = ""  # 空 = 跟色卡走；App 里选「自定义」就写 #RRGGBB
    return courses


# ---------------------------------------------------------------- 配色门禁

def check_palette():
    """三道配色门禁：每张色卡的对比度、Java 兜底色板是否逐项一致、白色形状资源是否一致。"""
    for palette in PALETTES:
        colors = palette["colors"]
        if len(colors) != 10:
            raise SystemExit("色卡「%s」不是 10 个槽位" % palette["name"])
        print("色卡「%s」fill / ink 对比度（门槛 %.1f:1）：" % (palette["name"], MIN_BLOCK_CONTRAST))
        weak = []
        for index, (fill, ink) in enumerate(colors):
            ratio = contrast_ratio(fill, ink)
            mark = "白字" if ink.upper() == "#FFFFFF" else "深字"
            print("  [%d] %s + %s（%s）  %.2f:1" % (index, fill, ink, mark, ratio))
            if ratio < MIN_BLOCK_CONTRAST:
                weak.append((index, fill, ink, ratio))
        if weak:
            detail = "、".join("[%d] %s + %s 只有 %.2f:1" % row for row in weak)
            raise SystemExit(
                "\n配色不合格：色卡「%s」的 %s。把颜色调深/调浅到 %.1f:1 以上再来。"
                % (palette["name"], detail, MIN_BLOCK_CONTRAST))
        print("")

    if not PALETTE_FILE.is_file():
        raise SystemExit("找不到 Java 兜底色板：%s" % PALETTE_FILE.relative_to(ROOT))
    want_fill = [[color.upper() for color in row] for row in ALL_FILL]
    want_ink = [[color.upper() for color in row] for row in ALL_INK]
    java_fill, java_ink = read_java_palette()
    if java_fill != want_fill or java_ink != want_ink:
        raise SystemExit(
            "色卡不一致（两处必须逐项相同，顺序也要一样）：\n"
            "  export_widget_data.py FILL = %s\n"
            "  Palette.java           FILL = %s\n"
            "  export_widget_data.py INK  = %s\n"
            "  Palette.java           INK  = %s"
            % (want_fill, java_fill, want_ink, java_ink))
    check_shapes()


def check_shapes():
    """手写的 block_shape_*.xml 的圆角半径必须和 BLOCK_SHAPES 一致，否则真机和预览页会不一样。"""
    corners = ("topLeft", "topRight", "bottomRight", "bottomLeft")
    for shape, radii in BLOCK_SHAPES.items():
        target = DRAWABLE_DIR / ("block_shape_%s.xml" % shape)
        if not target.is_file():
            raise SystemExit("缺少形状资源：%s" % target.relative_to(ROOT))
        found = dict(re.findall(
            r"android:(topLeft|topRight|bottomRight|bottomLeft)Radius=\"([^\"]+)\"",
            target.read_text(encoding="utf-8")))
        want = dict(zip(corners, radii.split(",")))
        if found != want:
            raise SystemExit("%s 的圆角和 BLOCK_SHAPES 对不上：文件 %s，脚本 %s"
                             % (target.name, found, want))
    print("形状资源核对通过：%d 张 block_shape_*.xml 与 BLOCK_SHAPES 一致" % len(BLOCK_SHAPES))


def read_java_palette():
    """从 Palette.java 的 BUILTIN_FILL / BUILTIN_INK 里抠出每张色卡的十六进制色值。"""
    source = PALETTE_FILE.read_text(encoding="utf-8")
    return read_java_table(source, "BUILTIN_FILL"), read_java_table(source, "BUILTIN_INK")


def read_java_table(source, name):
    matched = re.search(r"\b%s\s*=\s*\{(.*?)\n    \};" % name, source, re.S)
    if not matched:
        raise SystemExit("Palette.java 里找不到 %s = { ... };" % name)
    groups = re.findall(r"\{([^{}]*)\}", matched.group(1))
    return [[value.upper() for value in re.findall(r"#[0-9A-Fa-f]{6}", group)]
            for group in groups]


def contrast_ratio(first, second):
    """WCAG 对比度，两个十六进制颜色之间。"""
    light, dark = sorted((relative_luminance(first), relative_luminance(second)),
                         reverse=True)
    return (light + 0.05) / (dark + 0.05)


def relative_luminance(color):
    """WCAG 相对亮度。"""
    channels = [int(color[index:index + 2], 16) / 255
                for index in (1, 3, 5)]
    linear = [channel / 12.92 if channel <= 0.03928
              else ((channel + 0.055) / 1.055) ** 2.4
              for channel in channels]
    return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]


# ---------------------------------------------------------------- 圆角色块

def block_drawable_name(index, shape):
    return "block_%d_%s" % (index, shape)


def write_block_drawables():
    """按默认色卡生成课块圆角资源：Java 侧 setColorFilter 不可用时的兜底（极少见）。生成后读回来核对。"""
    DRAWABLE_DIR.mkdir(parents=True, exist_ok=True)
    written = []
    for index, fill in enumerate(COURSE_FILL):
        for shape in BAKED_SHAPES:
            top_left, top_right, bottom_right, bottom_left = BLOCK_SHAPES[shape].split(",")
            xml = (
                '<?xml version="1.0" encoding="utf-8"?>\n'
                "<!-- 由 scripts/export_widget_data.py 按默认色卡生成，不要手改；改色板请改那个脚本。\n"
                "     色卡 [%d] %s，形状 %s。\n"
                "     平时用不到这张资源：色块是「白色形状 + setColorFilter 现染」画的，\n"
                "     只有少数 ROM 不支持染色时才退回这里，颜色会取最接近的色卡色。 -->\n"
                '<shape xmlns:android="http://schemas.android.com/apk/res/android"\n'
                '    android:shape="rectangle">\n'
                '    <solid android:color="%s" />\n'
                "    <corners\n"
                '        android:topLeftRadius="%s"\n'
                '        android:topRightRadius="%s"\n'
                '        android:bottomRightRadius="%s"\n'
                '        android:bottomLeftRadius="%s" />\n'
                "</shape>\n"
            ) % (index, fill, shape, fill, top_left, top_right, bottom_right, bottom_left)
            target = DRAWABLE_DIR / (block_drawable_name(index, shape) + ".xml")
            target.write_text(xml, encoding="utf-8")

            check = target.read_text(encoding="utf-8")
            found = re.search(r'android:color="(#[0-9A-Fa-f]{6})"', check)
            if not found or found.group(1).upper() != fill.upper():
                raise SystemExit("生成的色块资源和色板对不上：%s" % target.name)
            written.append(target.name)
    return written


# ---------------------------------------------------------------- 导出

def palette_json(palette):
    """一张色卡 -> schedule.json 里的写法。"""
    return {
        "id": palette["id"],
        "name": palette["name"],
        "colors": [{"fill": fill, "ink": ink} for fill, ink in palette["colors"]],
    }


def write_schedule_json(courses):
    """写出小组件读取的 assets/schedule.json。"""
    payload = {
        "version": 3,
        "semesterStart": "",
        "generatedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "sectionTimes": {str(key): value for key, value in SECTION_TIMES.items()},
        "paletteId": DEFAULT_PALETTE_ID,
        "palettes": [palette_json(item) for item in PALETTES],
        # 旧字段：当前色卡的颜色。老版本 App 只认它，留着做向后兼容。
        "courseColors": [{"fill": fill, "ink": ink} for fill, ink in COURSE_COLORS],
        "semesterWeeks": SEMESTER_WEEKS,
        "finalsStartWeek": 0,
        "exams": [],
        "courses": courses,
    }
    ASSET_FILE.parent.mkdir(parents=True, exist_ok=True)
    ASSET_FILE.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def default_week(courses):
    """预览页默认停在一个课最多的周次，方便一眼看出效果。"""
    counted = Counter(week for course in courses for week in course["weekList"])
    if not counted:
        return 0
    return max(sorted(counted), key=lambda week: counted[week])


def render_preview(courses):
    """把数据与壁纸灌进 scripts/preview_template.html，产出一份单文件预览页。"""
    now = datetime.now()
    palette = [{"fill": fill, "ink": ink} for fill, ink in COURSE_COLORS]
    payload = {
        "generatedAt": now.strftime("%Y-%m-%d %H:%M:%S"),
        "todayWeekday": now.isoweekday(),
        "monthDay": f"{now.month}/{now.day}",
        "sectionTimes": {str(key): value for key, value in SECTION_TIMES.items()},
        "courseColors": palette,
        "paletteId": DEFAULT_PALETTE_ID,
        # 预览页也认这几张色卡，切色卡时和真机跑同一套取色
        "palettes": [palette_json(item) for item in PALETTES],
        "semesterWeeks": SEMESTER_WEEKS,
        "finalsStartWeek": 0,
        "courses": courses,
        # 预览页默认停在哪个周次：和 Java 侧 WeekUtils.firstMonday 的锚点对齐，
        # 这样预览页算出来的「周一–周日」区间和真机上一致。
        "defaultWeek": default_week(courses),
        # 预览页专用：示例考试数据，真机上是 App 里录的
        "sampleExams": sample_exams(),
    }
    data = json.dumps(payload, ensure_ascii=False).replace("</", "<\\/")
    PREVIEW_FILE.parent.mkdir(parents=True, exist_ok=True)
    PREVIEW_FILE.write_text(
        PREVIEW_TEMPLATE.read_text(encoding="utf-8")
        .replace("__DATA__", data)
        .replace("__WALLPAPER__", WALLPAPER_RELATIVE)
        .replace("__GENERATED__", payload["generatedAt"])
        .replace("__WEEK__", str(default_week(courses)))
        .replace("__PALETTE__", json.dumps(palette))
        .replace("__CONTRAST__", json.dumps({
            fill: round(contrast_ratio(fill, ink), 2) for fill, ink in COURSE_COLORS
        })),
        encoding="utf-8",
    )


def parse_args():
    parser = argparse.ArgumentParser(description="导出小组件数据（导出前先做课表冲突检查）")
    parser.add_argument("--force", action="store_true", help="查出冲突也强制导出")
    return parser.parse_args()


def schedule_frame(courses):
    """把导出的课程列表还原成课程表 DataFrame，好套用同一套冲突检查。"""
    return pd.DataFrame(
        [
            {
                "课程名称": course["name"],
                "星期": course["weekday"],
                "开始节数": course["start"],
                "结束节数": course["end"],
                "老师": course["teacher"],
                "地点": course["room"],
                "上课周": course["weeks"],
            }
            for course in courses
        ],
        columns=COURSE_COLUMNS,
    )


def report_problems(problems):
    """打印检查结果。"""
    if problems.empty:
        print("课表检查：没有重复排课，也没有时间冲突")
        return

    counts = problems["类型"].value_counts().to_dict()
    summary = "，".join(
        f"{kind} {counts.get(kind, 0)} 处"
        for kind in (*BLOCKING_TYPES, "周次互补")
    )
    print(f"课表检查：{summary}")

    blocking = problems[problems["类型"].isin(BLOCKING_TYPES)]
    if not blocking.empty:
        print("\n需要修正：")
        print(blocking.to_string(index=False))


def main():
    args = parse_args()

    if not SOURCE.exists():
        raise SystemExit(
            f"找不到课程表文件：{SOURCE}\n"
            "请先运行 python coursetable/export_excel.py 生成。"
        )

    check_palette()
    courses = collect_courses()

    problems = find_problems(schedule_frame(courses))
    report_problems(problems)

    if has_blocking_problem(problems) and not args.force:
        raise SystemExit(
            "\n课表有重复排课或时间冲突，已中止导出（没有改动任何文件）。"
            "\n确认是数据本身的问题就先修 coursetable/schedule.py 的解析；"
            "确认无误可加 --force 强制导出。"
        )

    drawables = write_block_drawables()
    write_schedule_json(courses)
    render_preview(courses)

    print(f"\n课程条数：{len(courses)}")
    print(f"已写入：{ASSET_FILE.relative_to(ROOT)}")
    print(f"已写入：{PREVIEW_FILE.relative_to(ROOT)}")
    print("已写入：%d 个色块资源（%s）" % (
        len(drawables), drawables[0].rsplit("_", 1)[0] + "_* …"))


if __name__ == "__main__":
    main()