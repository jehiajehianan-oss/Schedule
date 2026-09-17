"""课程表工具包：把教务系统导出的课表网页解析成结构化课程表。

模块分工：

    html_parser   读 data/course.html，按「星期 + 节次行」逐格产出单元格
    schedule      单元格 → 课程表 DataFrame，并检查重复排课与时间冲突
    export_excel  导出 output/我的课程表.xlsx（也可以直接当脚本跑）

典型用法：

    from coursetable.schedule import build_schedule, find_problems

    schedule = build_schedule("data/course.html")
"""

from __future__ import annotations

from coursetable.html_parser import get_schedule_cells
from coursetable.schedule import (
    BLOCKING_TYPES,
    COURSE_COLUMNS,
    build_schedule,
    expand_weeks,
    find_problems,
    has_blocking_problem,
    normalize_weeks,
)

__all__ = [
    "BLOCKING_TYPES",
    "COURSE_COLUMNS",
    "build_schedule",
    "expand_weeks",
    "find_problems",
    "get_schedule_cells",
    "has_blocking_problem",
    "normalize_weeks",
]
