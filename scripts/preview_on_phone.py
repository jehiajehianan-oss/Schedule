"""开一个临时预览服务，方便用手机看效果。

用法（在项目根目录执行）：
    python scripts\\preview_on_phone.py

然后用手机浏览器打开打印出来的地址（手机和电脑要连同一个 WiFi）。
第一次运行 Windows 可能会弹防火墙提示，允许「专用网络」即可。Ctrl+C 结束。

服务的是整个项目目录，因为预览页里的壁纸是相对路径引用的（output/ 相对 assets/）。
除了 .git 目录以外都能访问，用完记得 Ctrl+C 关掉。
"""

from __future__ import annotations

import http.server
import socket
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PREVIEW_PAGE = ROOT / "output" / "course_card.html"
URL_PATH = "/output/course_card.html"
PORT = 8000


def lan_address():
    """拿本机在局域网里的地址；只做一次 UDP connect，不会真的发数据出去。"""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
        try:
            probe.connect(("8.8.8.8", 80))
            return probe.getsockname()[0]
        except OSError:
            return "127.0.0.1"


class PreviewHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def translate_path(self, path):
        """只放项目目录里的文件，顺带把 .git 挡在外面。"""
        resolved = Path(super().translate_path(path)).resolve()
        if ".git" in resolved.parts or not resolved.is_relative_to(ROOT):
            return str(ROOT / "not-found")
        return str(resolved)

    def log_message(self, fmt, *args):
        pass


def main():
    if not PREVIEW_PAGE.exists():
        raise SystemExit(
            "还没有预览页，先运行：python scripts/export_widget_data.py"
        )

    address = lan_address()
    print(f"手机浏览器打开： http://{address}:{PORT}{URL_PATH}")
    print(f"电脑上打开：     http://127.0.0.1:{PORT}{URL_PATH}")
    print("Ctrl+C 结束\n")

    with http.server.ThreadingHTTPServer(("0.0.0.0", PORT), PreviewHandler) as server:
        server.serve_forever()


if __name__ == "__main__":
    main()
