"""把爬到的课程表导出成小组件能直接读的数据，并生成一份离线预览页。

用法（在项目根目录执行）：
    python scripts\\export_widget_data.py
    python scripts\\export_widget_data.py --force   # 查出冲突也照样导出

输入：output/我的课程表.xlsx
输出：
    widget/app/src/main/assets/schedule.json   小组件读取的课程数据
    output/course_card.html                    手机浏览器可直接打开的预览页

导出前会跑一遍和 coursetable/export_excel.py 相同的冲突检查；发现重复排课或时间冲突就中止导出，
避免把有问题的数据打进小组件。周次互补属于正常安排，只提示不拦截。
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from datetime import datetime
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from coursetable.schedule import (  # noqa: E402  需要先把项目根目录加进 sys.path
    BLOCKING_TYPES,
    COURSE_COLUMNS,
    expand_weeks,
    find_problems,
    has_blocking_problem,
)

SOURCE = ROOT / "output" / "我的课程表.xlsx"
ASSET_FILE = ROOT / "widget" / "app" / "src" / "main" / "assets" / "schedule.json"
PREVIEW_FILE = ROOT / "output" / "course_card.html"
PREVIEW_TEMPLATE = Path(__file__).resolve().parent / "preview_template.html"
# 预览页里垫的壁纸，按相对路径引用（预览页在 output/，壁纸在 assets/wallpapers/），
# 这样 file:// 直接打开和 preview_on_phone.py 的临时服务都能加载。换壁纸就改这里。
WALLPAPER_RELATIVE = "../assets/wallpapers/【哲风壁纸】卡通-夏日-大树.jpg"

# 默认作息时间。如果和学校实际时间不一样，改这里再跑一次脚本即可。
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
    return courses


def write_schedule_json(courses):
    """写出小组件读取的 assets/schedule.json。"""
    payload = {
        "semesterStart": "",
        "generatedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "sectionTimes": {str(key): value for key, value in SECTION_TIMES.items()},
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
    payload = {
        "generatedAt": now.strftime("%Y-%m-%d %H:%M:%S"),
        "todayWeekday": now.isoweekday(),
        "monthDay": f"{now.month}/{now.day}",
        "sectionTimes": {str(key): value for key, value in SECTION_TIMES.items()},
        "courses": courses,
    }
    data = json.dumps(payload, ensure_ascii=False).replace("</", "<\\/")
    PREVIEW_FILE.parent.mkdir(parents=True, exist_ok=True)
    PREVIEW_FILE.write_text(
        PREVIEW_TEMPLATE.read_text(encoding="utf-8")
        .replace("__DATA__", data)
        .replace("__WALLPAPER__", WALLPAPER_RELATIVE)
        .replace("__GENERATED__", payload["generatedAt"])
        .replace("__WEEK__", str(default_week(courses))),
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

    courses = collect_courses()

    problems = find_problems(schedule_frame(courses))
    report_problems(problems)

    if has_blocking_problem(problems) and not args.force:
        raise SystemExit(
            "\n课表有重复排课或时间冲突，已中止导出（没有改动任何文件）。"
            "\n确认是数据本身的问题就先修 coursetable/schedule.py 的解析；"
            "确认无误可加 --force 强制导出。"
        )

    write_schedule_json(courses)
    render_preview(courses)

    print(f"\n课程条数：{len(courses)}")
    print(f"已写入：{ASSET_FILE.relative_to(ROOT)}")
    print(f"已写入：{PREVIEW_FILE.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
