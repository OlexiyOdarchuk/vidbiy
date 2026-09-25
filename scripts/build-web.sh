#!/usr/bin/env sh
# Збирає сайт у dist/web (Linux і macOS). Параметри передаються далі, напр. --serve.
set -eu
cd "$(dirname "$0")/.."
command -v python3 >/dev/null 2>&1 || { echo "Помилка: потрібен Python 3 (https://www.python.org)." >&2; exit 1; }
exec python3 scripts/build_web.py "$@"
