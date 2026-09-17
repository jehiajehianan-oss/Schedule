"""用系统默认浏览器打开教务系统登录页，由你自己登录并点到课表页，再把这一页抓下来。

为什么不让脚本替你登录：

    学校统一身份认证页里的密码是前端 RSA 加密后提交的，还带一次性令牌，并且备着滑块验证。
    脚本去复刻这些，等于在做反自动化对抗：既不属于「正常用户请求」，学校一改版还会立刻失效。
    所以这里只做一件事 —— 打开一个真实浏览器，登录这件事永远由你本人完成。

安全边界：

    - 不写死任何学校地址，登录页由 --login-url 传入，谁用谁填自己的
    - 不读、不填、不提交账号密码，不碰短信验证码和滑块
    - 默认不保存登录态；除非显式给 --profile-dir，否则用全新会话，退出即销毁
    - 抓到的东西先交给现有解析器试跑，解析不出课程就报错退出，绝不覆盖旧文件
    - 终端只打印站点的域名和路径，不打查询串，也不打印任何 Cookie 与请求头

依赖：playwright（python -m pip install playwright）。
驱动的是系统里已经装好的 Chrome / Edge，不需要再下载 Playwright 自带的浏览器。

用法（在项目根目录执行）：

    python scripts\\fetch_course_html.py --login-url "https://.../login"
    python scripts\\fetch_course_html.py --login-url "https://.../login" --wait-url-regex "timetable"
    python scripts\\fetch_course_html.py --login-url "https://.../login" --browser chrome

接管你自己开的浏览器（不重新登录，推荐先试这条）：

    python scripts\\fetch_course_html.py --cdp 9222

抓完接着跑原来的链路，一条命令都不用改：

    python coursetable\\export_excel.py
    python scripts\\verify_schedule.py
    python scripts\\export_widget_data.py
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
import time
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from coursetable.schedule import build_schedule  # noqa: E402  需要先把项目根目录加进 sys.path

DEFAULT_OUT = ROOT / "data" / "course.html"

DEFAULT_WAIT_SECONDS = 300
PAGE_POLL_SECONDS = 0.5

# 默认的操作提示：各校路径不一样，可以用 --hint 覆盖（用 | 分隔每一步）
DEFAULT_STEPS = (
    "1) 先找到【信息门户】并点进去（有的学校叫「统一身份认证」「校园门户」）",
    "2) 正常登录：账号密码或短信验证码都由你自己点，我不碰",
    "3) 登录后一路点到【课程表】/【我的课表】那一页",
    "4) 回到这个终端按回车",
)

# Playwright 只能用 channel 驱动系统里的 Chrome / Edge，其它内核的要按可执行文件路径启动
CHANNEL_BY_PROGID = {
    "MSEdgeHTM": "msedge",
    "MSEdgeBHTML": "msedge-beta",
    "ChromeHTML": "chrome",
    "ChromeBHTML": "chrome-beta",
}

# Playwright 驱动不了系统里装的 Firefox / Safari（它只支持自己那份改过的版本）
UNSUPPORTED_PROGIDS = {
    "FirefoxURL": "Firefox",
    "FirefoxHTML": "Firefox",
    "SafariHTML": "Safari",
}

# 接管已有浏览器时的提示：那边已经登录过了，不用再登
CDP_STEPS = (
    "1) 就在你自己那个浏览器里，点到【课程表】页面（不用再登录，那边已经是登录好的）",
    "2) 回到这个终端按回车",
    "3) 如果它列出多个标签页，输入课表页前面的序号",
)

USER_CHOICE_KEY = (
    r"Software\Microsoft\Windows\Shell\Associations"
    r"\UrlAssociations\https\UserChoice"
)

EXE_FROM_COMMAND_PATTERN = re.compile(r'^\s*"([^"]+)"|^\s*([^\s]+\.exe)', re.IGNORECASE)

# 诊断用：判断一份 HTML 里有没有课表表、有没有课表单元格
TIMETABLE_ID_PATTERN = re.compile(r"id=.?timetable", re.IGNORECASE)
CELL_ID_PATTERN = re.compile(r"id=.?[1-7]-\d+")


def read_default_progid():
    """只读注册表，问出 Windows 当前的默认浏览器 ProgId。

    只读不写：注册表里不会发生任何改动。非 Windows 或读不到时返回空串。
    """
    if sys.platform != "win32":
        return ""

    import winreg

    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, USER_CHOICE_KEY) as handle:
            return str(winreg.QueryValueEx(handle, "ProgId")[0])
    except OSError:
        return ""


def executable_for_progid(progid):
    """照着 ProgId 去注册表里查它对应的浏览器可执行文件，查不到返回 None。

    这比硬编码各家浏览器的安装路径可靠：默认浏览器是 Brave / Opera / 360 这类
    Chromium 内核的，也能顺着它自己的注册项找到 exe，再用 executable_path 启动。
    """
    import winreg

    subkeys = [
        rf"Software\Classes\{progid}\shell\open\command",
        rf"{progid}\shell\open\command",
    ]
    for subkey in subkeys:
        for hive in (winreg.HKEY_CURRENT_USER, winreg.HKEY_CLASSES_ROOT):
            try:
                with winreg.OpenKey(hive, subkey) as handle:
                    command = str(winreg.QueryValueEx(handle, "")[0])
            except OSError:
                continue

            matched = EXE_FROM_COMMAND_PATTERN.match(command)
            if not matched:
                continue

            path = Path(matched.group(1) or matched.group(2))
            if path.is_file():
                return path
    return None


def local_test_url(value):
    """把本地测试页的路径转成 file:// 地址；不像本地页面就返回空串。

    自测时可以直接写 scripts\\fixtures\\mock_timetable.html，不用自己拼 file:///。
    """
    if value.lower().startswith(("http://", "https://", "file://")):
        return ""
    path = Path(value)
    if path.suffix.lower() != ".html" or not path.is_file():
        return ""
    return path.resolve().as_uri()


def looks_like_path(value):
    """判断 --browser 给的是路径还是一个 channel 名字。"""
    return value.lower().endswith(".exe") or "/" in value or "\\" in value


def resolve_default_browser():
    """决定默认用哪个浏览器，返回 (说明, 启动参数)；没法自动决定时返回 (None, None)。"""
    progid = read_default_progid()
    if not progid:
        return None, None

    channel = CHANNEL_BY_PROGID.get(progid)
    if channel:
        return f"系统默认浏览器（{progid} → channel {channel}）", {"channel": channel}

    name = UNSUPPORTED_PROGIDS.get(progid)
    if name:
        print(f"系统默认浏览器是 {name}，Playwright 驱动不了系统装的 {name}。")
        print("  两条路：改用系统里的 Chrome / Edge（--browser msedge 或 --browser chrome），")
        print("  或者下载一份 Playwright 自带的 Firefox：python -m playwright install firefox")
        return None, None

    path = executable_for_progid(progid)
    if path:
        return f"系统默认浏览器（{progid} → {path}）", {"executable_path": str(path)}

    print(f"认不出默认浏览器 {progid} 该用哪个可执行文件启动。")
    print(r'  可以用 --browser 直接指定，例如 --browser msedge 或 --browser "D:\路径\浏览器.exe"')
    return None, None


def resolve_browser(requested):
    """把 --browser 的值（auto / channel / exe 路径）解析成启动参数。"""
    if requested and requested.lower() != "auto":
        if looks_like_path(requested):
            path = Path(requested)
            if not path.is_file():
                raise SystemExit(f"找不到这个浏览器：{path}")
            return f"命令行指定的浏览器：{path}", {"executable_path": str(path)}
        return f"命令行指定的 channel：{requested}", {"channel": requested}

    label, options = resolve_default_browser()
    if options is None:
        raise SystemExit("请用 --browser 指定一个能驱动的浏览器（例如 --browser msedge）。")
    return label, options


def cdp_endpoint(value):
    """把 --cdp 的值整理成 connect_over_cdp 能用的地址。"""
    if value.startswith(("http://", "https://", "ws://", "wss://")):
        return value
    return f"http://127.0.0.1:{value}"


def suggested_launch_command(endpoint):
    """连不上调试端口时，给一条可以直接复制去启动浏览器的命令。"""
    progid = read_default_progid()
    path = executable_for_progid(progid) if progid else None
    port = endpoint.rstrip("/").rsplit(":", 1)[-1]
    profile_dir = ROOT / ".browser-profile"

    exe = f'"{path}"' if path else '"<你的浏览器.exe>"'
    flags = (
        f"--remote-debugging-port={port} "
        f"--user-data-dir=\"{profile_dir}\" "
        "--no-first-run"
    )

    lines = [
        f"连不上 {endpoint}。先用这条命令开一个带调试端口的浏览器：",
        "",
        f"  Start-Process {exe} '{flags}'",
        "",
        "必须用独立的 --user-data-dir：浏览器已经在跑时，调试端口这个标志会被忽略。",
        "用 Start-Process 是为了让命令立刻返回，不然终端会一直等你关掉浏览器。",
        "在那个窗口里登录一次、点到课表页，然后重跑本命令。",
    ]
    return "\n".join(lines)


def attach_running_browser(playwright, value):
    """接上一个已经开着调试端口的浏览器，返回 (context, 收尾函数)。

    这是你自己的浏览器：我们只读你指定的那一页，不导航、不关闭，
    连断开连接都交给进程退出自己处理。
    """
    endpoint = cdp_endpoint(value)
    try:
        browser = playwright.chromium.connect_over_cdp(endpoint)
    except Exception as error:  # noqa: BLE001  连不上的原因很多，统一给可复制的指引
        raise SystemExit(suggested_launch_command(endpoint)) from error

    context = browser.contexts[0] if browser.contexts else browser.new_context()
    return context, lambda: None


def launch_browser(playwright, options, profile_dir):
    """启动有界面的浏览器，返回 (context, 需要额外关掉的 browser)。"""
    settings = dict(options)
    settings["headless"] = False

    if profile_dir:
        context = playwright.chromium.launch_persistent_context(
            user_data_dir=str(profile_dir), **settings
        )
        return context, context.close

    browser = playwright.chromium.launch(**settings)
    context = browser.new_context()

    def cleanup():
        context.close()
        browser.close()

    return context, cleanup


def wait_for_matching_page(context, pattern, timeout_seconds):
    """等地址匹配正则的页面出现，等不到返回 None。"""
    deadline = time.monotonic() + timeout_seconds
    while True:
        for page in context.pages:
            if pattern.search(page.url or ""):
                return page
        if time.monotonic() >= deadline:
            return None
        time.sleep(PAGE_POLL_SECONDS)


def choose_page(context):
    """挑要抓的标签页：只开着一个就直接用，开着多个就让你选。"""
    pages = [page for page in context.pages if not page.is_closed()]
    if not pages:
        raise SystemExit("浏览器里没有打开着的页面，先打开课表页再重跑。")
    if len(pages) == 1:
        return pages[0]

    print()
    print("浏览器里开着这些页面：")
    for index, page in enumerate(pages, start=1):
        print(f"  {index}) {safe_address(page.url)}")

    try:
        answer = input(f"抓哪一个？回车 = 最后一个（{len(pages)}）：").strip()
    except EOFError:
        print()
        return pages[-1]

    if answer.isdigit() and 1 <= int(answer) <= len(pages):
        return pages[int(answer) - 1]
    if answer:
        print(f"没看懂「{answer}」，就用最后一个了。")
    return pages[-1]


def wait_for_enter(context, steps):
    """等你在浏览器里登录并点到课表页，回到终端按回车；再确认抓哪个标签页。"""
    print()
    print("接下来请在那个浏览器窗口里操作：")
    for line in steps:
        print(f"  {line}")
    print()
    try:
        input("准备好了就按回车（想放弃就 Ctrl+C）：")
    except EOFError:
        # 非交互环境（比如把输入用管道喂进来）就直接往下走
        print()
    return choose_page(context)


def frame_label(frame, index):
    """给一个 frame 起个能打印的名字。"""
    if index == 0:
        return "顶层页面"
    return f"页面里的 iframe（{safe_address(frame.url)}）"


def capture_html(page):
    """从这一页里找出真正装课表的那份 HTML：先看顶层文档，再看各个 iframe。

    有些教务系统把课表嵌在 iframe 里，只读顶层文档会一条课程都解析不出来。
    返回 (HTML 文本, 课程条数, 来源说明)。
    """
    top_html = ""

    for index, frame in enumerate(page.frames):
        try:
            html_text = frame.content()
        except Exception:  # noqa: BLE001  frame 可能刚好在跳转，跳过就好
            continue

        if index == 0:
            top_html = html_text

        courses = count_courses(html_text)
        if courses:
            return html_text, courses, frame_label(frame, index)

    return top_html, 0, frame_label(page.main_frame, 0)


def build_failure_report(context, chosen):
    """解析不出课程时，把浏览器里开着什么、有没有 iframe 列出来，方便定位问题。"""
    lines = ["当时浏览器里开着这些标签页："]
    pages = [page for page in context.pages if not page.is_closed()][:8]

    for index, page in enumerate(pages, start=1):
        try:
            courses = capture_html(page)[1]
            address = safe_address(page.url)
        except Exception:  # noqa: BLE001  页面可能刚关闭或还在跳转
            continue
        mark = "   ← 你选的是这个" if page is chosen else ""
        lines.append(f"  {index}) {address}：{courses} 条课程{mark}")

    frames = [] if chosen is None else [f for f in chosen.frames]
    if len(frames) > 1:
        lines.append("")
        lines.append(f"你选的那一页里有 {len(frames)} 个 frame：")
        for index, frame in enumerate(frames):
            try:
                html_text = frame.content()
            except Exception:  # noqa: BLE001  同上
                continue
            has_table = "有" if TIMETABLE_ID_PATTERN.search(html_text) else "没有"
            has_cells = "有" if CELL_ID_PATTERN.search(html_text) else "没有"
            lines.append(
                f"  {index}) {frame_label(frame, index)}：{len(html_text)} 字符，"
                f"{has_table} id=\"timetable\"，{has_cells}课表单元格 id"
            )

    return "\n".join(lines)


def count_courses(html_text):
    """把抓到的 HTML 交给现有解析器试跑，返回解析出的课程条数。

    走的是和 export_excel.py 完全相同的那条解析链路，所以这里能解析出来，
    后面的脚本就一定读得懂；顺带也能挡住「抓到登录页 / 抓到不相干的页面」。
    """
    with tempfile.NamedTemporaryFile(
        "w", suffix=".html", encoding="utf-8", delete=False
    ) as handle:
        handle.write(html_text)
        probe = Path(handle.name)

    try:
        return len(build_schedule(probe))
    finally:
        probe.unlink(missing_ok=True)


def safe_address(url):
    """只留协议 + 域名 + 路径，丢掉查询串和锚点，免得把令牌类参数打进终端。"""
    parts = urlsplit(url or "")
    if not parts.scheme:
        return url or "（空地址）"
    return f"{parts.scheme}://{parts.netloc}{parts.path}"


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="打开浏览器让你自己登录，然后把课表页抓成 data/course.html",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "示例：\n"
            '  python scripts\\fetch_course_html.py --login-url "https://.../login"\n'
            "  python scripts\\fetch_course_html.py --login-url \"https://.../login\" "
            '--wait-url-regex "timetable"\n'
        ),
    )
    parser.add_argument(
        "--login-url",
        default="",
        help="教务系统入口地址（不写死在代码里，谁用谁填自己的）；用 --cdp 时可以不给",
    )
    parser.add_argument(
        "--out",
        default=str(DEFAULT_OUT),
        help=f"抓到的 HTML 存到哪里，默认 {DEFAULT_OUT.relative_to(ROOT)}",
    )
    parser.add_argument(
        "--browser",
        default="auto",
        help="auto（跟随系统默认浏览器）/ msedge / chrome / 浏览器 exe 的完整路径",
    )
    parser.add_argument(
        "--wait-url-regex",
        default="",
        help="给了就自动等地址匹配它的页面出现，不用按回车（例如 timetable）",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=DEFAULT_WAIT_SECONDS,
        help=f"--wait-url-regex 的最长等待秒数，默认 {DEFAULT_WAIT_SECONDS}",
    )
    parser.add_argument(
        "--profile-dir",
        default="",
        help="把登录态存在这个目录里，下次不用重新登录；默认不用，退出即销毁",
    )
    parser.add_argument(
        "--hint",
        default="",
        help="自定义操作提示，用 | 分隔每一步；默认提示「信息门户 → 登录 → 课表」",
    )
    parser.add_argument(
        "--cdp",
        default="",
        help="接管一个已经开着调试端口的浏览器：填端口号（例如 9222）或完整地址",
    )
    parser.add_argument(
        "--dump",
        default="",
        help="不管解析成不成功，都把抓到的 HTML 另存一份到这个路径（排查页面结构用）",
    )
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)

    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        print("没装 playwright，先跑：python -m pip install playwright")
        return 1

    if not args.cdp and not args.login_url:
        raise SystemExit("要么用 --login-url 给个入口，要么用 --cdp 接管你已开着的浏览器。")

    login_url = local_test_url(args.login_url) or args.login_url

    out_file = Path(args.out)
    if not out_file.is_absolute():
        out_file = (Path.cwd() / out_file).resolve()

    pattern = re.compile(args.wait_url_regex) if args.wait_url_regex else None
    profile_dir = Path(args.profile_dir).expanduser().resolve() if args.profile_dir else None

    if args.hint:
        steps = tuple(args.hint.split("|"))
    else:
        steps = CDP_STEPS if args.cdp else DEFAULT_STEPS

    if args.cdp:
        label, options = "", {}
    else:
        label, options = resolve_browser(args.browser)

    if args.cdp:
        print(f"接管浏览器：{cdp_endpoint(args.cdp)}")
    else:
        print(f"浏览器：{label}")
        print(f"登录页：{safe_address(login_url)}")
    print(f"抓到后写入：{out_file}")
    if args.cdp:
        print("登录态：用你自己浏览器的，本脚本不读取也不保存")
    elif profile_dir:
        print(f"登录态：存在 {profile_dir}（里面有会话 Cookie，别提交进 Git）")
    else:
        print("登录态：不保存，关掉浏览器就没了")
    print()

    with sync_playwright() as playwright:
        if args.cdp:
            context, cleanup = attach_running_browser(playwright, args.cdp)
        else:
            context, cleanup = launch_browser(playwright, options, profile_dir)

        try:
            if not args.cdp:
                page = context.pages[0] if context.pages else context.new_page()
                page.goto(login_url, wait_until="domcontentloaded", timeout=60_000)

            else:
                page = None

            if pattern:
                print(f"等地址里含「{args.wait_url_regex}」的页面出现"
                      f"（最多等 {args.timeout} 秒）……")
                target = wait_for_matching_page(context, pattern, args.timeout)
                if target is None:
                    print("等超时了。确认那个页面已经打开，或者把 --wait-url-regex 放宽一点。")
                    return 1
            else:
                target = wait_for_enter(context, steps)

            target.bring_to_front()
            print(f"抓取页面：{safe_address(target.url)}")

            html_text, courses, source = capture_html(target)
            if courses and source != "顶层页面":
                print(f"（课表嵌在 {source} 里，用的是那一份）")
            if args.dump:
                dump_file = Path(args.dump)
                if not dump_file.is_absolute():
                    dump_file = (Path.cwd() / dump_file).resolve()
                dump_file.parent.mkdir(parents=True, exist_ok=True)
                dump_file.write_text(html_text, encoding="utf-8", newline="")
                print(f"抓到的 HTML 已另存到：{dump_file}（里面有你的课表信息，别随便发人）")

            failure_report = "" if courses else build_failure_report(context, target)
        finally:
            cleanup()

    if not courses:
        print()
        print("这一页没解析出任何课程，已经放弃写入，旧文件一个字节都没动。")
        print()
        print(failure_report)
        print()
        print("把上面这段发我：能看出是选错了标签页，还是课表的结构和我认识的不一样。")
        print("想让我直接看页面结构，就加 --dump data\\_debug_page.html 再跑一次。")
        return 2

    out_file.parent.mkdir(parents=True, exist_ok=True)
    out_file.write_text(html_text, encoding="utf-8", newline="")

    print()
    print(f"搞定：解析出 {courses} 条课程，已写入 {out_file}")
    print("接着跑原来的链路就行：")
    print("  python coursetable\\export_excel.py")
    print("  python scripts\\verify_schedule.py")
    print("  python scripts\\export_widget_data.py")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print()
        print("已取消。什么都没写。")
        sys.exit(1)
