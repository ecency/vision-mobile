#!/usr/bin/env bash
# Build the Android spike app, run it on an emulator (or a connected device) and optionally take a screenshot.
#
#   native/scripts/android-emu.sh                 build, install, launch, print launch time and the app_start / first_frame marks
#   native/scripts/android-emu.sh --shot out.png  same, then save a screenshot
#
# Starts the AVD named by ECENCY_AVD (default Ecency_Spike_API_34) when no device is attached.
set -euo pipefail

: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator"
AVD="${ECENCY_AVD:-Ecency_Spike_API_34}"
# The RN app's id: native installs over an RN build signed with the same key (README "In-place upgrade from RN 3.x").
APP_ID="app.esteem.mobile.android"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if ! adb get-state >/dev/null 2>&1; then
  echo "Starting emulator $AVD"
  nohup emulator -avd "$AVD" -no-snapshot-save -no-boot-anim >/dev/null 2>&1 &
  adb wait-for-device
  until [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do sleep 2; done
fi

cd "$ROOT"
./gradlew -q :androidApp:assembleDebug
if ! out="$(adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk 2>&1)"; then
  echo "$out"
  if [[ "$out" == *INSTALL_FAILED_UPDATE_INCOMPATIBLE* || "$out" == *INSTALL_FAILED_VERSION_DOWNGRADE* ]]; then
    echo "An RN build of $APP_ID signed with another key (or with a higher versionCode) is installed. Uninstalling it" \
      "deletes its data; do that only on a device whose RN data you do not need." >&2
  fi
  exit 1
fi
adb shell am force-stop "$APP_ID"
adb logcat -c
# TotalTime is the OS launch time; the EcencyPerf marks (app_start, first_frame, ...) are defined in README (App layer)
adb shell am start -W -n "$APP_ID/com.ecency.app.MainActivity" | grep -E "TotalTime"
sleep 1
adb logcat -d -s EcencyPerf | grep -E "app_start|first_frame" || true

if [[ "${1:-}" == "--shot" ]]; then
  sleep 2
  adb exec-out screencap -p > "${2:?screenshot path required}"
  echo "Screenshot: $2"
fi
