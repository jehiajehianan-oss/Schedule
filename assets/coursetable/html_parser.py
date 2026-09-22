"""解析教务系统导出的课表网页，按「星期 + 节次行」逐格产出单元格。

关于这份课表的两个坑（都是从 data/course.html 实际结构里确认的）：

1. 课表表（id=timetable）是嵌在外层 content_tab 表里的。遍历 soup.find_all("table") 时，
   外层表会把内层表的单元格一并数进去：实测周一那一列会被数 24 次，而实际只有 8 格。
   所以这里只认 id=timetable 一张表，并按单元格 id 再兜底去重。
2. 一门课如果跨多个「节次行」，教务系统会在每个和它相交的行里重复渲染这门课
   （单元格 id 各不相同），并在单元格里带上真实节次文本（例如「第6-9节」），
   只有当真实节次刚好等于行标题时才省略。所以这里如实产出每一格，
   由调用方用「课程自带的节次」去重。
"""

from __future__ import annotations

import re
from pathlib import Path

from bs4 import BeautifulSoup

WEEKDAY_PATTERN = re.compile(r"^([1-7])-")
SECTION_PATTERN = re.compile(r"(\d+)\s*[-~－—]\s*(\d+)")


def read_html(html_file):
    """读取课表网页源码。

    文件实际是 UTF-8（页面 meta 里写的 gbk 是错的），所以先按 UTF-8 严格解码，
    失败再退回 GBK；都不行才用替换模式兜底，避免像 errors="ignore" 那样静默丢字。
    """
    raw = Path(html_file).read_bytes()
    for encoding in ("utf-8", "gbk"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace")


def parse_section_label(text):
    """从「第1-2节」「海南7-8节」这类行标题里取出节次区间。"""
    matched = SECTION_PATTERN.search(text or "")
    if not matched:
        return None
    return int(matched.group(1)), int(matched.group(2))


def find_schedule_table(soup):
    """找到真正的课表：优先 id=timetable，否则退回「含星期单元格」的第一张表。"""
    table = soup.find("table", id="timetable")
    if table is not None:
        return table

    for candidate in soup.find_all("table"):
        for cell in candidate.find_all("td"):
            if WEEKDAY_PATTERN.match(cell.get("id") or ""):
                return candidate
    return None


def get_schedule_cells(html_file):
    """产出 (单元格, 星期, 行起始节, 行结束节)。

    行起始/结束节来自行标题，只是兜底值：课程若自带节次文本，调用方应当优先采用。
    """
    soup = BeautifulSoup(read_html(html_file), "html.parser")
    table = find_schedule_table(soup)
    if table is None:
        return

    seen_ids = set()
    for row in table.find_all("tr"):
        header = row.find("th")
        section = parse_section_label(header.get_text(" ", strip=True)) if header else None
        if section is None:
            continue

        row_start, row_end = section
        for cell in row.find_all("td"):
            cell_id = cell.get("id") or ""
            matched = WEEKDAY_PATTERN.match(cell_id)
            if not matched:
                continue
            if cell_id in seen_ids:
                continue
            seen_ids.add(cell_id)

            yield cell, int(matched.group(1)), row_start, row_end
