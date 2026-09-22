"""把课表网页解析成整洁的课程表，并检查重复排课与时间冲突。

节次怎么定（对应 data/course.html 的真实结构）：

    单元格里带「第X-Y节」 → 用这个真实节次（跨多行的课只有这一处写得准）
    单元格里没带        → 用行标题的节次

一门课跨多个节次行时会被教务系统重复渲染，所以最后要按
（课程、星期、节次、老师、地点、周次）去重，再把同一时段的多个周次合并成一行。
"""

from __future__ import annotations

import re

import pandas as pd

from coursetable.html_parser import get_schedule_cells

COURSE_COLUMNS = [
    "课程名称",
    "星期",
    "开始节数",
    "结束节数",
    "老师",
    "地点",
    "上课周",
]

PROBLEM_COLUMNS = [
    "类型",
    "星期",
    "课程A",
    "节次A",
    "课程B",
    "节次B",
    "重叠周次",
    "说明",
]

BLOCKING_TYPES = ("重复排课", "时间冲突")

COURSE_HEAD_PATTERN = re.compile(r"^<<(.+?)>>")
SECTION_TEXT_PATTERN = re.compile(r"第?\s*(\d+)\s*[-~－—]\s*(\d+)\s*节")
LESSON_TYPE_PATTERN = re.compile(r"学时\s*$")
ROOM_HINT_PATTERN = re.compile(r"[0-9]|楼|房|馆|室|场|区")

WEEK_SEPARATOR_PATTERN = re.compile(r"[、,，;；/\s]+")
WEEK_RANGE_PATTERN = re.compile(r"^(\d+)\s*[-~－—]\s*(\d+)(单|双)?$")
WEEK_SINGLE_PATTERN = re.compile(r"^(\d+)(单|双)?$")


def expand_weeks(weeks_text):
    """把「1-3、8-15、06」「13-15单」这类文本展开成升序去重的周次列表。"""
    raw = re.sub(r"第", "", str(weeks_text or "")).replace("周", "").strip()
    if not raw or raw.lower() == "nan":
        return []

    weeks = []
    for token in WEEK_SEPARATOR_PATTERN.split(raw):
        if not token:
            continue

        matched = WEEK_RANGE_PATTERN.match(token)
        if matched:
            first, last = int(matched.group(1)), int(matched.group(2))
            parity = matched.group(3)
            candidates = range(first, last + 1)
        else:
            matched = WEEK_SINGLE_PATTERN.match(token)
            if not matched:
                continue
            candidates = [int(matched.group(1))]
            parity = matched.group(2)

        for week in candidates:
            if parity == "单" and week % 2 == 0:
                continue
            if parity == "双" and week % 2 == 1:
                continue
            if week not in weeks:
                weeks.append(week)

    return sorted(weeks)


def normalize_weeks(weeks_text):
    """把原始周次文本整理成用来显示的写法：「1-9周」→「1-9」、「第06周」→「06」。"""
    text = str(weeks_text or "").strip().replace(" ", "")
    text = re.sub(r"第(?=\d)", "", text)
    return text.replace("周", "")


def clean_course_name(course_name):
    """去掉「<<课名>>;课程号」这层包装，只留课名。"""
    matched = COURSE_HEAD_PATTERN.match(course_name.strip())
    return (matched.group(1) if matched else course_name).strip()


def looks_like_room(text):
    """判断剩下的单个字段是教室还是老师：教室一般带数字或「楼/房/馆/室/场/区」。"""
    return bool(ROOM_HINT_PATTERN.search(text))


def parse_course(course_name, details, weekday, row_start, row_end):
    """解析一门课：节次、老师、地点、上课周次。"""
    name = clean_course_name(course_name)
    if not name:
        return None

    start, end = row_start, row_end
    weeks = ""
    fields = []

    for text in details:
        section = SECTION_TEXT_PATTERN.search(text)
        if section:
            # 单元格自带的节次最准，行标题只是它被省略时的兜底
            start, end = int(section.group(1)), int(section.group(2))
            continue
        if "周" in text:
            weeks = text
            continue
        if LESSON_TYPE_PATTERN.search(text):
            continue
        fields.append(text)

    room, teacher = "", ""
    if len(fields) >= 2:
        room, teacher = fields[0], fields[1]
    elif len(fields) == 1:
        # 只写了一个字段时：像教室就当教室，否则当老师（课表里确实有没写教室的课）
        if looks_like_room(fields[0]):
            room = fields[0]
        else:
            teacher = fields[0]

    return {
        "课程名称": name,
        "星期": weekday,
        "开始节数": start,
        "结束节数": end,
        "老师": teacher,
        "地点": room,
        "上课周": normalize_weeks(weeks),
    }


def parse_cell(cell, weekday, row_start, row_end):
    """解析一个单元格里的所有课程。"""
    parts = [
        text.strip()
        for text in cell.stripped_strings
        if text.strip() not in {"&nbsp;", "\xa0"}
    ]
    starts = [
        index
        for index, text in enumerate(parts)
        if COURSE_HEAD_PATTERN.match(text)
    ]

    courses = []
    for position, index in enumerate(starts):
        end = starts[position + 1] if position + 1 < len(starts) else len(parts)
        course = parse_course(
            parts[index],
            parts[index + 1:end],
            weekday,
            row_start,
            row_end,
        )
        if course:
            courses.append(course)
    return courses


def remove_duplicate_courses(courses):
    """去掉完全相同的记录（同一门课在多个相交的行里被重复渲染）。"""
    frame = pd.DataFrame(courses, columns=COURSE_COLUMNS)
    if frame.empty:
        return frame

    return frame.drop_duplicates(subset=COURSE_COLUMNS, keep="first").reset_index(drop=True)


def merge_courses(courses):
    """把同一门课、同一时段的多条周次记录合并成一行。"""
    frame = remove_duplicate_courses(courses)
    if frame.empty:
        return frame

    group_columns = [column for column in COURSE_COLUMNS if column != "上课周"]
    merged = (
        frame.groupby(group_columns, dropna=False, sort=False)["上课周"]
        .agg(lambda values: "、".join(dict.fromkeys(
            value for value in values if value
        )))
        .reset_index()
    )

    # groupby 会把行顺序打乱，这里恢复成固定列序，交给 sort_courses 排序
    return merged[COURSE_COLUMNS]


def sort_courses(schedule):
    """先按星期，再按节次和课名排序。"""
    if schedule.empty:
        return schedule

    return schedule.sort_values(
        by=["星期", "开始节数", "结束节数", "课程名称"],
        kind="stable",
    ).reset_index(drop=True)


def build_schedule(html_file):
    """解析整份课表，返回去掉重复与重叠后的课程表。"""
    courses = []

    for cell, weekday, row_start, row_end in get_schedule_cells(html_file):
        courses.extend(parse_cell(cell, weekday, row_start, row_end))

    return sort_courses(merge_courses(courses))


def find_problems(schedule):
    """检查重复排课与时间冲突，返回问题清单。

    三种情况：

    - 重复排课：同一天、同一时段、同一门课，而且周次真的重叠（正常应该已被合并掉）
    - 时间冲突：同一天、时段相交、课程不同，而且周次重叠（要人工核对）
    - 周次互补：时段相交但周次完全错开，属于正常安排，列出来供确认
    """
    records = [] if schedule.empty else schedule.to_dict("records")
    problems = []

    for index, first in enumerate(records):
        for second in records[index + 1:]:
            if first["星期"] != second["星期"]:
                continue
            if (first["结束节数"] < second["开始节数"]
                    or second["结束节数"] < first["开始节数"]):
                continue

            shared = sorted(
                set(expand_weeks(first["上课周"]))
                & set(expand_weeks(second["上课周"]))
            )
            same_course = (
                first["课程名称"] == second["课程名称"]
                and first["老师"] == second["老师"]
            )

            if shared:
                kind = "重复排课" if same_course else "时间冲突"
                note = "同一门课在同一时段重复出现" if same_course else "两门课有重叠周次，需要核对"
            else:
                kind = "周次互补"
                note = "时段相交但周次错开，属于正常安排"

            problems.append({
                "类型": kind,
                "星期": first["星期"],
                "课程A": first["课程名称"],
                "节次A": f'{first["开始节数"]}-{first["结束节数"]}',
                "课程B": second["课程名称"],
                "节次B": f'{second["开始节数"]}-{second["结束节数"]}',
                "重叠周次": "、".join(str(week) for week in shared) if shared else "无",
                "说明": note,
            })

    return pd.DataFrame(problems, columns=PROBLEM_COLUMNS)


def has_blocking_problem(problems):
    """是否存在必须修正的问题（重复排课或时间冲突）。"""
    if problems.empty:
        return False
    return bool(problems["类型"].isin(BLOCKING_TYPES).any())
