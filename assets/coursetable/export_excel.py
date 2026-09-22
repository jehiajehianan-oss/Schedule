"""把课表导出成 Excel，并顺带检查重复排课与时间冲突。

用法（在项目根目录执行）：
    python assets\\coursetable\\export_excel.py

输入：data/course.html
输出：output/我的课程表.xlsx
"""

from __future__ import annotations

import sys
from pathlib import Path

import pandas as pd

# 这个文件在 assets/coursetable/ 下，所以项目根目录是往上两级。
# 项目根和 assets/ 都加进 sys.path：不管 coursetable 放在哪一层，import 都能成功。
ROOT = Path(__file__).resolve().parents[2]
for candidate in (ROOT, ROOT / "assets"):
    if str(candidate) not in sys.path:
        sys.path.insert(0, str(candidate))

from coursetable.schedule import build_schedule, find_problems  # noqa: E402

HTML_FILE = ROOT / "data" / "course.html"
OUTPUT_DIR = ROOT / "output"
OUTPUT_FILE = OUTPUT_DIR / "我的课程表.xlsx"


def export_excel(schedule, problems, output_file):
    """第一张表是课程表；查出问题时再加一张「检查」表。"""
    with pd.ExcelWriter(output_file, engine="openpyxl") as writer:
        schedule.to_excel(writer, sheet_name="课程表", index=False)
        if not problems.empty:
            problems.to_excel(writer, sheet_name="检查", index=False)


def export_csv(schedule, output_file):
    schedule.to_csv(output_file, index=False, encoding="utf-8-sig")


def export_schedule(schedule, problems, output_file):
    """根据文件后缀选择输出格式。"""
    suffix = Path(output_file).suffix.lower()

    if suffix == ".xlsx":
        export_excel(schedule, problems, output_file)
    elif suffix == ".csv":
        export_csv(schedule, output_file)
    else:
        raise ValueError(f"不支持的输出格式：{suffix}")


def report(schedule, problems):
    """把检查结果打到终端。"""
    print(f"课程数量：{len(schedule)}")

    if problems.empty:
        print("检查结果：没有重复排课，也没有时间冲突")
        return

    for kind, group in problems.groupby("类型", sort=False):
        print(f"{kind}：{len(group)} 处")

    blocking = problems[problems["类型"].isin(("重复排课", "时间冲突"))]
    if not blocking.empty:
        print("\n需要人工核对：")
        print(blocking.to_string(index=False))

    complementary = problems[problems["类型"] == "周次互补"]
    if not complementary.empty:
        print("\n周次互补（时段相交但周次错开，属于正常安排）：")
        print(complementary[["星期", "课程A", "节次A", "课程B", "节次B"]].to_string(index=False))


def main():
    schedule = build_schedule(HTML_FILE)
    problems = find_problems(schedule)

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    export_schedule(schedule, problems, OUTPUT_FILE)

    print(f"成功生成文件：{OUTPUT_FILE.resolve()}")
    report(schedule, problems)


if __name__ == "__main__":
    main()
