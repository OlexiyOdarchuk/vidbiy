# Збирає release APK для Android (Windows; працює й у PowerShell на Linux та macOS).
#
#   scripts\build-android.bat        (подвійний клік або з термінала)
#   pwsh scripts/build-android.ps1
#
# Результат: dist\Vidbiy.apk і dist\Vidbiy-<версія>.apk.
# Потрібні JDK 17–24 і Android SDK; скрипт шукає їх сам.
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)

# Gradle 8.14 запускається на Java 17–24; новіші версії він не підтримує.
$MinJava = 17
$MaxJava = 24
$exe = if ($IsWindows -or $env:OS -eq 'Windows_NT') { 'java.exe' } else { 'java' }

function Fail([string]$Message) {
    Write-Host "`nПомилка: $Message" -ForegroundColor Red
    exit 1
}

function Get-JavaMajor([string]$Java) {
    try {
        $line = (& $Java -version 2>&1 | Select-Object -First 1).ToString()
    } catch {
        return $null
    }
    if ($line -match 'version "(1\.)?(\d+)') { return [int]$Matches[2] }
    return $null
}

# Кандидати: JAVA_HOME, java з PATH, JDK з Android Studio та поширених інсталяторів.
$candidates = [System.Collections.Generic.List[string]]::new()
if ($env:JAVA_HOME) { $candidates.Add((Join-Path $env:JAVA_HOME "bin/$exe")) }
$onPath = Get-Command java -ErrorAction SilentlyContinue
if ($onPath) { $candidates.Add($onPath.Source) }
$roots = @(
    "$env:ProgramFiles\Android\Android Studio\jbr",
    "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
)
foreach ($pattern in @(
        "$env:ProgramFiles\Eclipse Adoptium\jdk-*",
        "$env:ProgramFiles\Microsoft\jdk-*",
        "$env:ProgramFiles\Java\jdk-*",
        "$env:ProgramFiles\Zulu\zulu-*",
        "$env:ProgramFiles\Amazon Corretto\jdk*",
        '/usr/lib/jvm/*'
    )) {
    $roots += @(Get-ChildItem -Path $pattern -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | ForEach-Object FullName)
}
foreach ($root in $roots) { $candidates.Add((Join-Path $root "bin/$exe")) }

$java = $null
foreach ($candidate in $candidates) {
    if (-not (Test-Path $candidate)) { continue }
    $major = Get-JavaMajor $candidate
    if ($major -and $major -ge $MinJava -and $major -le $MaxJava) {
        $java = (Get-Item $candidate).FullName
        break
    }
}
if (-not $java) {
    Fail "не знайдено Java $MinJava–$MaxJava.`nВстановіть JDK 21 (наприклад, https://adoptium.net) або вкажіть шлях у JAVA_HOME."
}
$env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $java)
Write-Host "Java: $env:JAVA_HOME ($(Get-JavaMajor $java))"

# Android SDK: змінні середовища, local.properties або стандартні місця.
$fromProps = $null
if (Test-Path local.properties) {
    $line = Select-String -Path local.properties -Pattern '^sdk\.dir=(.*)$' | Select-Object -First 1
    # У local.properties шлях екранований: C\:\\Users\\...
    if ($line) {
        $raw = [regex]::Replace($line.Matches[0].Groups[1].Value, '\\u([0-9a-fA-F]{4})', { [string][char][Convert]::ToInt32($args[0].Groups[1].Value, 16) })
        $fromProps = $raw -replace '\\(.)', '$1'
    }
}
$sdk = $null
foreach ($dir in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, $fromProps,
        "$env:LOCALAPPDATA\Android\Sdk", "$HOME/Android/Sdk", "$HOME/Library/Android/sdk")) {
    if ($dir -and (Test-Path (Join-Path $dir 'platforms'))) {
        $sdk = $dir
        break
    }
}
if (-not $sdk) {
    Fail "не знайдено Android SDK.`nВстановіть Android Studio (https://developer.android.com/studio) або вкажіть шлях у ANDROID_HOME."
}
Write-Host "Android SDK: $sdk"
# Передаємо SDK через змінну середовища, а не local.properties: так не треба екранувати шлях.
$env:ANDROID_HOME = $sdk

$gradlew = if ($IsWindows -or $env:OS -eq 'Windows_NT') { '.\gradlew.bat' } else { './gradlew' }
& $gradlew assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) { Fail 'збирання не вдалося, подробиці вище.' }

$version = (Select-String -Path app/build.gradle.kts -Pattern 'versionName = "([^"]+)"' | Select-Object -First 1).Matches[0].Groups[1].Value
New-Item -ItemType Directory -Force -Path dist | Out-Null
$apk = 'app/build/outputs/apk/release/app-release.apk'
Copy-Item $apk dist/Vidbiy.apk -Force
Copy-Item $apk "dist/Vidbiy-$version.apk" -Force
Write-Host "`nГотово: dist\Vidbiy.apk і dist\Vidbiy-$version.apk" -ForegroundColor Green
