#!/usr/bin/env bash
# Build the iOS spike app, run it on a dedicated simulator and optionally take a screenshot.
#
#   scripts/ios-sim.sh                 build, install, launch
#   scripts/ios-sim.sh --test          build and run the unit + UI tests on the same simulator
#   scripts/ios-sim.sh --shot out.png  build, install, launch, then save a screenshot
#
# The simulator is created on first use (name and device type can be overridden):
#   ECENCY_SIM_NAME="Ecency Spike" ECENCY_SIM_DEVICE="iPhone 16 Pro"
#   ECENCY_SIM_NAME="Ecency Duo" ECENCY_SIM_DEVICE="iPhone Duo"     (Xcode 27.1+)
# A device with more than one screen (iPhone Duo) needs the screen to capture: ECENCY_SIM_DISPLAY=primary (the outer
# screen, the default) or primary-1 (the inner one); `xcrun simctl io <udid> enumerate` lists them.
set -euo pipefail

SIM_NAME="${ECENCY_SIM_NAME:-Ecency Spike}"
SIM_DEVICE="${ECENCY_SIM_DEVICE:-iPhone 16 Pro}"
# The RN app's id: installing over an RN build on the simulator is an in-place update and runs the RN import on a
# normal (not -fakeData) launch (README "In-place upgrade from RN 3.x").
BUNDLE_ID="app.esteem.mobile.ios"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DERIVED="$ROOT/build/ios-dd"
ARCH="$(uname -m)"

sim_udid() {
  xcrun simctl list devices available -j | python3 -c '
import json, sys
name = sys.argv[1]
for runtime, devices in json.load(sys.stdin)["devices"].items():
    for d in devices:
        if d["name"] == name and "iOS" in runtime:
            print(d["udid"]); sys.exit(0)
' "$SIM_NAME"
}

UDID="$(sim_udid || true)"
if [[ -z "$UDID" ]]; then
  # the newest runtime that runs this device: Xcode 27 ships a separate runtime for iPhone Duo with the same version
  RUNTIME="$(xcrun simctl list runtimes -j | python3 -c '
import json, sys
device = sys.argv[1]
ios = [r for r in json.load(sys.stdin)["runtimes"] if r["platform"] == "iOS" and r["isAvailable"]
       and any(device in (d["name"], d["identifier"]) for d in r.get("supportedDeviceTypes", []))]
if not ios: sys.exit("No installed iOS runtime runs " + device)
print(sorted(ios, key=lambda r: [int(p) for p in r["version"].split(".")])[-1]["identifier"])' "$SIM_DEVICE")"
  UDID="$(xcrun simctl create "$SIM_NAME" "$SIM_DEVICE" "$RUNTIME")"
  echo "Created simulator '$SIM_NAME' ($SIM_DEVICE, $RUNTIME)"
fi

xcrun simctl boot "$UDID" 2>/dev/null || true
xcrun simctl bootstatus "$UDID" -b >/dev/null

cd "$ROOT/iosApp"
# Push: Firebase is linked only when the owner's GoogleService-Info.plist is there (README "Notifications and push").
if [[ -f GoogleService-Info.plist ]]; then export ECENCY_FIREBASE=true ECENCY_PUSH_EXTENSION=true; fi
xcodegen generate --quiet

# Ad-hoc ("Sign to Run Locally") signing: the simulator Keychain refuses unsigned apps (-34018).
SIGNING=(CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM=)

if [[ "${1:-}" == "--test" ]]; then
  # The test run's own status: a failing test must fail the script.
  status=0
  xcodebuild test -project EcencySpike.xcodeproj -scheme EcencyApp \
    -destination "platform=iOS Simulator,id=$UDID" -derivedDataPath "$DERIVED" \
    "${SIGNING[@]}" -quiet || status=$?
  exit "$status"
fi

xcodebuild build -project EcencySpike.xcodeproj -scheme EcencyApp \
  -destination "platform=iOS Simulator,id=$UDID" -derivedDataPath "$DERIVED" \
  ARCHS="$ARCH" ONLY_ACTIVE_ARCH=NO "${SIGNING[@]}" -quiet

xcrun simctl install "$UDID" "$DERIVED/Build/Products/Debug-iphonesimulator/EcencyApp.app"
xcrun simctl terminate "$UDID" "$BUNDLE_ID" 2>/dev/null || true
xcrun simctl launch "$UDID" "$BUNDLE_ID" >/dev/null
# Xcode 27 renamed Simulator.app to Device Hub; older Xcode still has Simulator. Showing the window is a convenience,
# so a missing app never fails the run (the app is already installed and launched).
open -a DeviceHub --args -CurrentDeviceUDID "$UDID" 2>/dev/null \
  || open -a Simulator --args -CurrentDeviceUDID "$UDID" 2>/dev/null \
  || echo "Note: no Device Hub or Simulator app found to show the device" >&2
echo "Running on '$SIM_NAME' ($UDID)"

if [[ "${1:-}" == "--shot" ]]; then
  sleep 3
  SHOT="${2:?screenshot path required}"
  if [[ -n "${ECENCY_SIM_DISPLAY:-}" ]]; then
    xcrun simctl io "$UDID" screenshot --display="$ECENCY_SIM_DISPLAY" "$SHOT" >/dev/null
  else
    # one screen needs no choice; several (iPhone Duo) fail without one, so take the main (outer) screen
    xcrun simctl io "$UDID" screenshot "$SHOT" >/dev/null 2>&1 \
      || xcrun simctl io "$UDID" screenshot --display=primary "$SHOT" >/dev/null
  fi
  echo "Screenshot: $2"
fi
