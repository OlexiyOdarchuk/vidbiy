#!/usr/bin/env sh
# Збирає release APK для Android (Linux і macOS).
#
#   ./scripts/build-android.sh
#
# Результат: dist/Vidbiy.apk і dist/Vidbiy-<версія>.apk.
# Потрібні JDK 17–24 і Android SDK; скрипт шукає їх сам.
set -eu
cd "$(dirname "$0")/.."

fail() {
    printf '\nПомилка: %s\n' "$1" >&2
    exit 1
}

# Основна версія Java за шляхом до java, або порожньо.
java_major() {
    "$1" -version 2>&1 | head -n 1 | sed -nE 's/.*version "(1\.)?([0-9]+).*/\2/p'
}

# Gradle 8.14 запускається на Java 17–24; новіші версії він не підтримує.
MIN_JAVA=17
MAX_JAVA=24

# Кандидати по одному на рядок: шляхи можуть містити пробіли (Android Studio на macOS).
java_candidates() {
    [ -n "${JAVA_HOME:-}" ] && printf '%s\n' "$JAVA_HOME/bin/java"
    command -v java >/dev/null 2>&1 && command -v java
    if [ -x /usr/libexec/java_home ]; then # macOS
        for v in 21 17 24; do
            home=$(/usr/libexec/java_home -v "$v" 2>/dev/null) && printf '%s\n' "$home/bin/java"
        done
    fi
    for home in /usr/lib/jvm/*21* /usr/lib/jvm/*17* /usr/lib/jvm/* \
        "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
        "$HOME/.local/share/JetBrains/Toolbox/apps/android-studio/jbr" /opt/android-studio/jbr; do
        [ -x "$home/bin/java" ] && printf '%s\n' "$home/bin/java"
    done
    return 0
}

find_java() {
    java_candidates | while IFS= read -r java; do
        v=$(java_major "$java")
        if [ -n "$v" ] && [ "$v" -ge "$MIN_JAVA" ] && [ "$v" -le "$MAX_JAVA" ]; then
            # /usr/bin/java зазвичай посилання; JAVA_HOME має вказувати на саму теку JDK.
            readlink -f "$java" 2>/dev/null || printf '%s' "$java"
            break
        fi
    done
}

JAVA=$(find_java)
[ -n "$JAVA" ] || fail "не знайдено Java $MIN_JAVA–$MAX_JAVA.
Встановіть JDK 21 (наприклад, https://adoptium.net) або вкажіть шлях у JAVA_HOME."
JAVA_HOME=$(cd "$(dirname "$JAVA")/.." && pwd)
export JAVA_HOME
echo "Java: $JAVA_HOME ($(java_major "$JAVA"))"

# Android SDK: змінні середовища, local.properties або стандартні місця.
sdk_from_props() {
    [ -f local.properties ] && sed -nE 's/^sdk\.dir=(.*)$/\1/p' local.properties | head -n 1
}
SDK=""
for dir in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$(sdk_from_props || true)" \
    "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    if [ -n "$dir" ] && [ -d "$dir/platforms" ]; then
        SDK=$dir
        break
    fi
done
[ -n "$SDK" ] || fail "не знайдено Android SDK.
Встановіть Android Studio (https://developer.android.com/studio) або вкажіть шлях у ANDROID_HOME."
echo "Android SDK: $SDK"
# Передаємо SDK через змінну середовища, а не local.properties: так не треба екранувати шлях.
ANDROID_HOME=$SDK
export ANDROID_HOME

./gradlew assembleRelease --console=plain

VERSION=$(sed -nE 's/.*versionName = "([^"]+)".*/\1/p' app/build.gradle.kts | head -n 1)
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/Vidbiy.apk
cp app/build/outputs/apk/release/app-release.apk "dist/Vidbiy-$VERSION.apk"
printf '\nГотово: dist/Vidbiy.apk і dist/Vidbiy-%s.apk\n' "$VERSION"
