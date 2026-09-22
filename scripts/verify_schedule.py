"""独立核对 output/我的课程表.xlsx 和 data/course.html 是否对得上。

刻意不复用 coursetable/schedule.py 的解析流程：这里直接用正则从 HTML 里重新抽一遍课程块，
再和 Excel 逐项对照，并检查两类问题：

    同一时段同一节课重复  —— 不应该出现
    时段交叉且周次重叠    —— 不应该出现（周次错开的交叉是正常安排）

用法（在项目根目录执行）：
    python scripts\\verify_schedule.py
"""

from __future__ import annotations

import html
import re
import sys
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
# coursetable 包在 assets/ 下（和壁纸放一起），根目录和 assets/ 都加上，挪位置也不会失效
for candidate in (ROOT, ROOT / "assets"):
    if str(candidate) not in sys.path:
        sys.path.insert(0, str(candidate))

from coursetable.schedule import expand_weeks  # noqa: E402

HTML_FILE = ROOT / "data" / "course.html"
EXCEL_FILE = ROOT / "output" / "我的课程表.xlsx"

ROW_LABEL_PATTERN = re.compile(r"<th[^>]*>(.*?)</th>", re.S)
CELL_PATTERN = re.compile(r'<td[^>]*id="(\d)-(\d+)"[^>]*>(.*?)</td>', re.S)
SECTION_PATTERN = re.compile(r"第?\s*(\d+)\s*[-~－—]\s*(\d+)\s*节")
BLOCK_HEAD_PATTERN = re.compile(r"^<<(.+?)>>")
LESSON_TYPE_PATTERN = re.compile(r"学时$")
ROOM_HINT_PATTERN = re.compile(r"[0-9]|楼|房|馆|室|场|区")


def timetable_markup():
    """只取 id=timetable 那张表：它嵌在外层 content_tab 表里，直接遍历所有表会重复统计。"""
    text = HTML_FILE.read_bytes().decode("utf-8")
    start = text.index('id="timetable"')
    return text[start:text.index("</table>", start)]


def parse_row_label(label):
    matched = SECTION_PATTERN.search(label)
    return (int(matched.group(1)), int(matched.group(2))) if matched else None


def split_blocks(parts):
    """按「<<课名>>」把单元格里的文本切成一门门课。"""
    starts = [index for index, text in enumerate(parts) if BLOCK_HEAD_PATTERN.match(text)]
    for position, index in enumerate(starts):
        end = starts[position + 1] if position + 1 < len(starts) else len(parts)
        yield parts[index], parts[index + 1:end]


def parse_block(name_text, details, row_range):
    """节次优先用单元格自带的，没有才用行标题。"""
    start, end = row_range
    weeks = ""
    fields = []
    has_section_text = False

    for text in details:
        section = SECTION_PATTERN.search(text)
        if section:
            start, end = int(section.group(1)), int(section.group(2))
            has_section_text = True
            continue
        if "周" in text:
            weeks = text
            continue
        if LESSON_TYPE_PATTERN.search(text):
            continue
        fields.append(text)

    room = teacher = ""
    if len(fields) >= 2:
        room, teacher = fields[0], fields[1]
    elif len(fields) == 1:
        if ROOM_HINT_PATTERN.search(fields[0]):
            room = fields[0]
        else:
            teacher = fields[0]

    return {
        "课程名称": BLOCK_HEAD_PATTERN.match(name_text).group(1).strip(),
        "开始节数": start,
        "结束节数": end,
        "老师": teacher,
        "地点": room,
        "上课周": weeks,
        "有节次文本": has_section_text,
    }


def extract_blocks():
    """抽出 HTML 里所有课程块（同一门课会在多个相交的行里各出现一次）。"""
    blocks = []
    for row_chunk in timetable_markup().split("<tr")[1:]:
        label = ROW_LABEL_PATTERN.search(row_chunk)
        row_range = parse_row_label(html.unescape(label.group(1))) if label else None
        if row_range is None:
            continue

        for matched in CELL_PATTERN.finditer(row_chunk):
            weekday = int(matched.group(1))
            cell_text = html.unescape(re.sub(r"<br\s*/?>", "\n", matched.group(3)))
            parts = [
                part.strip()
                for part in cell_text.split("\n")
                if part.strip() and part.strip() != "&nbsp;"
            ]
            for name_text, details in split_blocks(parts):
                block = parse_block(name_text, details, row_range)
                block["星期"] = weekday
                block["行节次"] = row_range
                blocks.append(block)
    return blocks


def text_of(value):
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return ""
    text = str(value).strip()
    return "" if text.lower() == "nan" else text


def index_by_slot(records):
    """按（星期, 节次, 课名, 老师, 地点）归并周次。"""
    indexed = {}
    for record in records:
        key = (
            int(record["星期"]),
            int(record["开始节数"]),
            int(record["结束节数"]),
            record["课程名称"],
            text_of(record["老师"]),
            text_of(record["地点"]),
        )
        indexed.setdefault(key, set()).update(expand_weeks(record["上课周"]))
    return indexed


def check_row_consistency(blocks, problems):
    """同一门课在多个行里重复渲染时，节次必须自洽，且每行都要和它相交。"""
    groups = {}
    for block in blocks:
        key = (
            block["星期"],
            block["课程名称"],
            block["老师"],
            block["地点"],
            frozenset(expand_weeks(block["上课周"])),
        )
        groups.setdefault(key, []).append(block)

    for key, group in groups.items():
        rows = {block["行节次"] for block in group}
        slots = {(block["开始节数"], block["结束节数"]) for block in group}
        if len(slots) > 1:
            problems.append(f"节次前后不一致：{key[1]} 星期{key[0]} -> {sorted(slots)}")
            continue
        slot = slots.pop()
        # 跨多行时，至少要有一格写了真实节次，否则真实节次无从得知
        if len(rows) > 1 and not any(block["有节次文本"] for block in group):
            problems.append(f"跨行但没有明确节次：{key[1]} 星期{key[0]} 行={sorted(rows)}")
        for row in rows:
            if row[1] < slot[0] or slot[1] < row[0]:
                problems.append(
                    f"行与节次不相交：{key[1]} 星期{key[0]} 行={row} 节次={slot}"
                )


def check_conflicts(records, problems):
    """重复排课与时间冲突。"""
    duplicates = conflicts = complements = 0
    for index, first in enumerate(records):
        for second in records[index + 1:]:
            if first["星期"] != second["星期"]:
                continue
            if (first["结束节数"] < second["开始节数"]
                    or second["结束节数"] < first["开始节数"]):
                continue

            shared = set(expand_weeks(first["上课周"])) & set(expand_weeks(second["上课周"]))
            same_course = (
                first["课程名称"] == second["课程名称"]
                and text_of(first["老师"]) == text_of(second["老师"])
            )
            if not shared:
                complements += 1
            elif same_course:
                duplicates += 1
                problems.append(
                    f"重复排课：星期{first['星期']} {first['课程名称']} "
                    f"{first['开始节数']}-{first['结束节数']} 周次 {sorted(shared)}"
                )
            else:
                conflicts += 1
                problems.append(
                    f"时间冲突：星期{first['星期']} {first['课程名称']}"
                    f"({first['开始节数']}-{first['结束节数']}) 与 "
                    f"{second['课程名称']}({second['开始节数']}-{second['结束节数']}) "
                    f"周次 {sorted(shared)}"
                )
    return duplicates, conflicts, complements


def main():
    problems = []
    blocks = extract_blocks()
    frame = pd.read_excel(EXCEL_FILE, sheet_name="课程表")
    records = frame.to_dict("records")

    expected = index_by_slot(blocks)
    actual = index_by_slot(records)

    check_row_consistency(blocks, problems)
    duplicates, conflicts, complements = check_conflicts(records, problems)

    for key in sorted(set(expected) | set(actual)):
        if key not in actual:
            problems.append(f"Excel 里少了：星期{key[0]} {key[3]} {key[1]}-{key[2]} 周次{sorted(expected[key])}")
        elif key not in expected:
            problems.append(f"Excel 里多了：星期{key[0]} {key[3]} {key[1]}-{key[2]} 周次{sorted(actual[key])}")
        elif expected[key] != actual[key]:
            problems.append(
                f"周次对不上：星期{key[0]} {key[3]} {key[1]}-{key[2]} "
                f"HTML={sorted(expected[key])} Excel={sorted(actual[key])}"
            )

    print(f"HTML 课程块（含跨行重复渲染）：{len(blocks)}")
    print(f"去重后的课程时段：{len(expected)}")
    print(f"Excel 行数：{len(records)}")
    print(f"重复排课：{duplicates}   时间冲突：{conflicts}   周次互补：{complements}")
    print()

    if problems:
        print(f"发现 {len(problems)} 个问题：")
        for problem in problems:
            print("  -", problem)
        return 1

    print("核对通过：Excel 与 course.html 完全一致，没有重复排课，也没有时间冲突。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
