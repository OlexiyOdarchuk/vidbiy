# /// script
# requires-python = ">=3.10"
# dependencies = ["segno"]
# ///
"""QR-коди для картки «На телефоні» на сайті.

    uv run tools/make_qr.py
"""
from pathlib import Path

import segno

WEB = Path(__file__).resolve().parent.parent / "web" / "qr"
CODES = {
    # iPhone: сайт сам запропонує встановити його на початковий екран.
    "site": "https://vidbiy.ishawyha.dev/",
    # Android: одразу завантаження APK останнього релізу.
    "apk": "https://github.com/OlexiyOdarchuk/vidbiy/releases/latest/download/Vidbiy.apk",
}

WEB.mkdir(parents=True, exist_ok=True)
for name, url in CODES.items():
    segno.make(url, error="m").save(WEB / f"{name}.svg", scale=4, border=2, dark="#0A1430", light="#FFFFFF")
    print(name, url)
