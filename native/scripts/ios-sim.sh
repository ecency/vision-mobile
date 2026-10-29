#!/usr/bin/env bash
# Build the iOS spike app, run it on a dedicated simulator and optionally take a screenshot.
#
#   native/scripts/ios-sim.sh                 build, install, launch
#   native/scripts/ios-sim.sh --test          build and run the unit + UI tests on the same simulator
#   native/scripts/ios-sim.sh --shot out.png  build, install, launch, then save a screenshot
#
# The simulator is created on first use (name and device type can be overridden):
#   ECENCY_SIM_NAME="Ecency Spike" ECENCY_SIM_DEVICE="iPhone 16 Pro"
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
  RUNTIME="$(xcrun simctl list runtimes -j | python3 -c '
import json, sys
ios = [r for r in json.load(sys.stdin)["runtimes"] if r["platform"] == "iOS" and r["isAvailable"]]
print(sorted(ios, key=lambda r: [int(p) for p in r["version"].split(".")])[-1]["identifier"])')"
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
open -a Simulator --args -CurrentDeviceUDID "$UDID"
echo "Running on '$SIM_NAME' ($UDID)"

if [[ "${1:-}" == "--shot" ]]; then
  sleep 3
  xcrun simctl io "$UDID" screenshot "${2:?screenshot path required}" >/dev/null
  echo "Screenshot: $2"
fi
