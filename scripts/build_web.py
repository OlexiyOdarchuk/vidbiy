#!/usr/bin/env python3
"""Готує сайт (Web/PWA) до публікації: копіює web/ у dist/web і додає версію до адрес файлів.

    python3 scripts/build_web.py            # зібрати в dist/web
    python3 scripts/build_web.py --serve    # зібрати й відкрити на http://localhost:8000

Версія потрібна, бо домен іде через Cloudflare, який кешує файли: без неї після оновлення
браузер може отримати новий index.html зі старим app.js. Цей самий скрипт використовує
публікація на GitHub Pages. Потрібен лише Python 3.9+, без сторонніх бібліотек.
"""
import argparse
import functools
import http.server
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "web"
# Проксі дозволяє запити з localhost:8000, тому для перевірки саме цей порт.
DEFAULT_PORT = 8000


def detect_version() -> str:
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--short=8", "HEAD"], cwd=ROOT, capture_output=True, text=True, check=True
        )
        return out.stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return time.strftime("%Y%m%d%H%M%S")


def build(out: Path, version: str) -> None:
    if out.exists():
        shutil.rmtree(out)
    shutil.copytree(SRC, out)

    index = out / "index.html"
    html = index.read_text(encoding="utf-8")
    html = re.sub(r'(href|src)="(style\.css|app\.js)"', rf'\1="\2?v={version}"', html)
    index.write_text(html, encoding="utf-8")

    # Модулі імпортують одне одного, тож версія потрібна і в import ... from "./x.js".
    for js in out.glob("*.js"):
        text = js.read_text(encoding="utf-8")
        text = re.sub(r'from "\./([a-z]+\.js)"', rf'from "./\1?v={version}"', text)
        if js.name == "sw.js":
            text = re.sub(r'const CACHE = "vidbiy-[^"]*"', f'const CACHE = "vidbiy-{version}"', text)
        js.write_text(text, encoding="utf-8")

    if f"app.js?v={version}" not in index.read_text(encoding="utf-8"):
        sys.exit("Помилка: не вдалося додати версію до index.html")
    print(f"Готово: {out.relative_to(ROOT) if out.is_relative_to(ROOT) else out} (версія {version})", flush=True)


def serve(directory: Path, port: int) -> None:
    handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(directory))
    with http.server.ThreadingHTTPServer(("127.0.0.1", port), handler) as httpd:
        print(f"Сайт: http://localhost:{port}  (зупинити — Ctrl+C)", flush=True)
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            pass


def main() -> None:
    parser = argparse.ArgumentParser(description="Збирання веб-версії «Відбою»")
    parser.add_argument("--version", help="версія для адрес файлів (типово — короткий хеш коміту)")
    parser.add_argument("--out", type=Path, default=ROOT / "dist" / "web", help="куди зібрати (типово dist/web)")
    parser.add_argument("--serve", nargs="?", const=DEFAULT_PORT, type=int, metavar="PORT",
                        help=f"після збирання запустити локальний сервер (типово порт {DEFAULT_PORT})")
    args = parser.parse_args()

    out = args.out.resolve()
    build(out, args.version or detect_version())
    if args.serve:
        serve(out, args.serve)


if __name__ == "__main__":
    main()
