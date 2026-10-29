#!/usr/bin/env bash
# Launches a release build once and fails when it crashes or does not stay in front. Unit and device tests run
# debug builds, so a crash that only a minified (R8) or optimised build has passes them all; this catches it
# before testers get the build (docs/release-builds.md, "Release launch check").
#
#   release-launch-check.sh android <app.apk> [seconds]   the attached device or emulator (ANDROID_SERIAL picks one)
#   release-launch-check.sh ios <EcencyApp.app> [seconds] the booted simulator (ECENCY_SIM_UDID picks one)
#
# Installs over the app on the device (same id, the RN app's), starts it from cold and waits [seconds] (default
# 25). Android: fails on a Java or native crash, an ANR (a key event halfway makes a frozen main thread show as
# one), a dead process or the app no longer in front. iOS: fails on a crash report or a dead process.
# Exit 0 passed, 1 failed, 2 could not run (bad arguments, install failed).
set -euo pipefail

platform="${1-}"
app="${2-}"
wait_s="${3-25}"
usage() {
  echo "usage: $0 android <app.apk> [seconds] | ios <Ecency.app> [seconds]" >&2
  exit 2
}
[[ -n "$platform" && -e "$app" && "$wait_s" =~ ^[1-9][0-9]*$ ]] || usage
fail() {
  echo "::error::Release launch check failed: $1" >&2
  exit 1
}

android() {
  local id="app.esteem.mobile.android"
  local activity="$id/com.ecency.app.MainActivity"
  : "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  local adb="adb"
  command -v adb >/dev/null || adb="$ANDROID_HOME/platform-tools/adb"
  "$adb" wait-for-device
  until [[ "$("$adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do sleep 2; done

  local out
  if ! out="$("$adb" install -r "$app" 2>&1)"; then
    echo "$out" >&2
    echo "Install failed. A build signed with another key cannot replace the installed app; uninstalling it deletes its data." >&2
    exit 2
  fi
  "$adb" shell am force-stop "$id"
  # every buffer: a plain -c leaves the events buffer, whose am_crash lines of an earlier run would fail this one
  "$adb" logcat -b all -c 2>/dev/null || "$adb" logcat -b main,system,crash,events -c

  crash_log() { "$adb" logcat -d -b crash 2>/dev/null | tr -d '\r' | grep -A40 -E "Process: $id|>>> $id <<<" || true; }
  out="$("$adb" shell am start -W -n "$activity" 2>&1 | tr -d '\r')"
  echo "$out"
  if [[ "$out" != *"Status: ok"* ]]; then
    crash_log >&2
    fail "the activity did not start"
  fi
  # an input event early on: a main thread that froze after the first frame then shows as an ANR, which needs
  # 5 s times the image's timeout multiplier to fire, so at least that much time is left after the event
  local mult first rest
  mult="$("$adb" shell getprop ro.hw_timeout_multiplier 2>/dev/null | tr -d '\r')"
  [[ "$mult" =~ ^[1-9][0-9]?$ ]] || mult=1
  first=8
  (( first < wait_s )) || first=$(( (wait_s + 1) / 2 ))
  sleep "$first"
  "$adb" shell input keyevent KEYCODE_SHIFT_LEFT
  rest=$(( wait_s - first ))
  (( rest >= 5 * mult + 3 )) || rest=$(( 5 * mult + 3 ))
  sleep "$rest"

  local crashes events
  crashes="$(crash_log)"
  # am_crash covers Java and native crashes, am_anr the freezes
  events="$("$adb" logcat -d -b events -s am_crash am_anr 2>/dev/null | tr -d '\r' | grep -F "$id" || true)"
  if [[ -n "$crashes" || -n "$events" ]]; then
    [[ -z "$crashes" ]] || echo "$crashes" >&2
    [[ -z "$events" ]] || echo "$events" >&2
    fail "the app crashed or stopped responding (log above)"
  fi
  [[ -n "$("$adb" shell pidof "$id" 2>/dev/null | tr -d '\r')" ]] || fail "the process is gone"
  local front
  front="$("$adb" shell dumpsys activity activities 2>/dev/null | tr -d '\r' | grep -E "topResumedActivity|mResumedActivity" | head -1)"
  # a first start on Android 13+ asks for notifications: the system's permission prompt over the app is fine
  [[ "$front" == *"$id/"* || "$front" == *"permissioncontroller/"*"GrantPermissionsActivity"* ]] \
    || fail "the app is not in front ($front)"
  echo "Release launch check passed: $id ran ${wait_s}s without a crash"
}

ios() {
  local udid="${ECENCY_SIM_UDID:-}"
  if [[ -z "$udid" ]]; then
    udid="$(xcrun simctl list devices booted -j | python3 -c 'import json,sys; d=[x["udid"] for r in json.load(sys.stdin)["devices"].values() for x in r if x["state"]=="Booted"]; print(d[0] if d else "")')"
  fi
  [[ -n "$udid" ]] || { echo "No booted simulator (set ECENCY_SIM_UDID or boot one)" >&2; exit 2; }
  local plist="$app/Info.plist"
  local id exe
  id="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$plist")"
  exe="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$plist")"

  xcrun simctl install "$udid" "$app" || { echo "Install failed" >&2; exit 2; }
  xcrun simctl terminate "$udid" "$id" >/dev/null 2>&1 || true
  local marker
  marker="$(mktemp)"
  local out pid
  out="$(xcrun simctl launch "$udid" "$id")" || fail "launch failed"
  # simctl prints "<bundle id>: <pid>"
  pid="$(printf '%s\n' "$out" | sed -n "s/^$id: \([0-9][0-9]*\)\$/\1/p" | tail -1)"
  [[ -n "$pid" ]] || fail "launch printed no process id: $out"
  sleep "$wait_s"

  # simulator apps are host processes, and their crash reports land in the host's DiagnosticReports
  local reports
  reports="$(find "$HOME/Library/Logs/DiagnosticReports" -maxdepth 1 -name "$exe*" -newer "$marker" 2>/dev/null || true)"
  rm -f "$marker"
  if [[ -n "$reports" ]]; then
    for r in $reports; do echo "== $r" >&2; head -c 6000 "$r" >&2; echo >&2; done
    fail "the app crashed (report above)"
  fi
  kill -0 "$pid" 2>/dev/null || fail "the process is gone"
  echo "Release launch check passed: $id ran ${wait_s}s without a crash"
}

case "$platform" in
  android) android ;;
  ios) ios ;;
  *) usage ;;
esac
