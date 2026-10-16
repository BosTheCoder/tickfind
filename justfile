# WSL helpers. On Windows itself use `dotnet run --project windows` / `dotnet publish windows` (see docs/development.md).

# Search logic tests, both platforms
test:
    ~/.dotnet/dotnet test tests

# Build the single-file exe into out/
publish:
    ~/.dotnet/dotnet publish windows -c Release -o out

# Publish, replace the installed copy (TICKFIND_DIR or %USERPROFILE%\Apps\Tickfind) and relaunch it
deploy: publish
    #!/usr/bin/env bash
    set -e
    dir="${TICKFIND_DIR:-$(wslpath "$(cmd.exe /c 'echo %USERPROFILE%' 2>/dev/null | tr -d '\r')")/Apps/Tickfind}"
    powershell.exe -NoProfile -Command "Get-Process Tickfind -ErrorAction SilentlyContinue | Where-Object Path -eq '$(wslpath -w "$dir/Tickfind.exe")' | Stop-Process; Start-Sleep 1"
    mkdir -p "$dir" && cp out/Tickfind.exe "$dir/"
    powershell.exe -NoProfile -Command "Start-Process '$(wslpath -w "$dir/Tickfind.exe")'"

# Publish and start a --demo copy from %TEMP% (runs alongside the real one). Extra args go to the exe, e.g. just demo --pin
demo *args: publish
    #!/usr/bin/env bash
    set -e
    dir="$(wslpath "$(cmd.exe /c 'echo %TEMP%' 2>/dev/null | tr -d '\r')")/tickfind-demo"
    powershell.exe -NoProfile -Command "Get-Process Tickfind -ErrorAction SilentlyContinue | Where-Object Path -eq '$(wslpath -w "$dir/Tickfind.exe")' | Stop-Process; Start-Sleep 1"
    mkdir -p "$dir" && cp out/Tickfind.exe "$dir/"
    powershell.exe -NoProfile -Command "Start-Process '$(wslpath -w "$dir/Tickfind.exe")' -ArgumentList '--demo {{args}}'"

# Android: unit tests + signed release APK into android/dist/Tickfind.apk (see docs/android.md)
android:
    #!/usr/bin/env bash
    set -e
    export JAVA_HOME="${JAVA_HOME_17:-$(brew --prefix openjdk@17)/libexec}" ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
    cd android && ./gradlew :app:testDebugUnitTest :app:assembleRelease
    mkdir -p dist && cp app/build/outputs/apk/release/app-release.apk dist/Tickfind.apk && ls -lh dist/Tickfind.apk
