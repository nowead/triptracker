#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="$PWD/.tools/jdk"
export ANDROID_HOME="$PWD/.tools/android-sdk"
export ANDROID_USER_HOME="$PWD/.tools/android-user-home"
export ANDROID_AVD_HOME="$PWD/.tools/avd"
export GRADLE_USER_HOME="$PWD/.tools/gradle-user-home"
avd_name=TripTracker_Preview
serial=emulator-5556
adb="$ANDROID_HOME/platform-tools/adb"
emulator="$ANDROID_HOME/emulator/emulator"
manager="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
system_image='system-images;android-36;google_apis;arm64-v8a'

if [[ "$(uname -s)" != Darwin || "$(uname -m)" != arm64 ]]; then
  echo 'This preview script currently supports Apple Silicon Macs.' >&2
  exit 1
fi
for executable in "$JAVA_HOME/bin/java" "$adb" "$emulator" "$manager"; do
  if [[ ! -x "$executable" ]]; then
    echo "Missing tool: $executable. See docs/development.md." >&2
    exit 1
  fi
done
if [[ "$(file "$emulator")" != *arm64* ]]; then
  echo 'Install the Apple Silicon (aarch64) emulator package; see docs/development.md.' >&2
  exit 1
fi
if [[ ! -f "$ANDROID_HOME/system-images/android-36/google_apis/arm64-v8a/package.xml" ]]; then
  echo "Install $system_image first; see docs/development.md." >&2
  exit 1
fi
mkdir -p "$ANDROID_AVD_HOME" "$PWD/.tools/emulator"
if [[ ! -f "$ANDROID_AVD_HOME/$avd_name.ini" ]]; then
  echo no | "$manager" create avd --name "$avd_name" --package "$system_image" --device pixel_6 --path "$ANDROID_AVD_HOME/$avd_name.avd"
fi

bash ./gradlew --console=plain :app:assembleDebug
"$adb" start-server
if "$adb" devices | awk -v serial="$serial" '$1 == serial { found = 1 } END { exit !found }'; then
  running_avd=$("$adb" -s "$serial" emu avd name | tr -d '\r' | head -n 1)
  if [[ "$running_avd" != "$avd_name" ]]; then
    echo "Port 5556 is occupied by another emulator ($running_avd). Close it before retrying." >&2
    exit 1
  fi
else
  nohup "$emulator" -avd "$avd_name" -port 5556 -memory 2048 -no-boot-anim -scale 0.35 \
    > "$PWD/.tools/emulator/preview.log" 2>&1 < /dev/null &
  emulator_pid=$!
fi

booted=false
for ((attempt=0; attempt<120; attempt++)); do
  if [[ -n "${emulator_pid:-}" ]] && ! kill -0 "$emulator_pid" 2>/dev/null; then
    echo 'Emulator exited. Check .tools/emulator/preview.log.' >&2
    exit 1
  fi
  if [[ "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then
    booted=true
    break
  fi
  sleep 2
done
if [[ "$booted" != true ]]; then
  echo 'Emulator boot timed out. Check .tools/emulator/preview.log.' >&2
  exit 1
fi
"$adb" -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb" -s "$serial" shell input keyevent KEYCODE_WAKEUP
"$adb" -s "$serial" shell wm dismiss-keyguard
"$adb" -s "$serial" shell am start -W -n com.triptracker.app/.MainActivity
echo 'Trip Tracker is open in the Android emulator. Preview data stays in .tools/avd.'
if [[ -n "${emulator_pid:-}" ]]; then
  wait "$emulator_pid"
fi
