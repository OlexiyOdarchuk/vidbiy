"""Рендерить прев'ю для соцмереж (og:image) з tools/og.html у web/og.png.

    python3 tools/make_og.py   # потрібен Playwright з Chromium або встановленим Chrome
"""
from pathlib import Path

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent.parent
with sync_playwright() as p:
    browser = p.chromium.launch(channel="chrome")
    page = browser.new_page(viewport={"width": 1200, "height": 630})
    page.goto((ROOT / "tools" / "og.html").as_uri(), wait_until="networkidle")
    page.screenshot(path=str(ROOT / "web" / "og.png"))
    browser.close()
print("web/og.png")
