#!/usr/bin/env bash
# iPhone Duo App Store screenshots (README "iPhone Duo"): the app on its synthetic data, one PNG per screen, at the
# display's own size, which is App Store Connect's iPhone Duo size (outer 1398 x 2034 px, inner 2007 x 2853 or
# 2853 x 2007 px held sideways). No alpha channel (App Store Connect refuses one).
#
#   native/scripts/duo-screenshots.sh inner  out/dir   the unfolded device's inner display
#   native/scripts/duo-screenshots.sh outer  out/dir   the folded device's outer display
#
# Fold or unfold the simulator yourself in Device Hub first (the script never switches displays: turning one off
# from the command line crashed the simulator's SpringBoard). The app must be installed on it: ios-sim.sh with
# ECENCY_SIM_NAME="Ecency Duo" ECENCY_SIM_DEVICE="iPhone Duo".
set -euo pipefail

WHICH="${1:?inner or outer}"
OUT="${2:?output folder}"
SIM_NAME="${ECENCY_SIM_NAME:-Ecency Duo}"
BUNDLE_ID="app.esteem.mobile.ios"
case "$WHICH" in
  inner) DISPLAY_ID="primary-1" ;;
  outer) DISPLAY_ID="primary" ;;
  *) echo "inner or outer" >&2; exit 2 ;;
esac

UDID="$(xcrun simctl list devices available -j | python3 -c '
import json, sys
for devices in json.load(sys.stdin)["devices"].values():
    for d in devices:
        if d["name"] == sys.argv[1]: print(d["udid"]); sys.exit(0)
sys.exit("no simulator named " + sys.argv[1])' "$SIM_NAME")"
mkdir -p "$OUT"
# the raw shots in a folder of their own, never beside the user's files
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# The display's pixels as an RGB PNG: the alpha channel dropped without re-encoding (a JPEG round trip loses detail).
opaque_png() {
  swift - "$1" "$2" <<'SWIFT'
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers
let args = CommandLine.arguments
guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: args[1]) as CFURL, nil),
      let image = CGImageSourceCreateImageAtIndex(source, 0, nil),
      let space = image.colorSpace ?? CGColorSpace(name: CGColorSpace.sRGB),
      let context = CGContext(data: nil, width: image.width, height: image.height, bitsPerComponent: 8, bytesPerRow: 0,
                              space: space, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { exit(1) }
context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
guard let opaque = context.makeImage(),
      let out = CGImageDestinationCreateWithURL(URL(fileURLWithPath: args[2]) as CFURL, UTType.png.identifier as CFString, 1, nil)
else { exit(1) }
CGImageDestinationAddImage(out, opaque, nil)
exit(CGImageDestinationFinalize(out) ? 0 : 1)
SWIFT
}

# name, then the launch arguments. On the inner display held sideways each tab shows two columns: the user menu
# beside the list, or the list beside what it opened (a link opens there as a tap does).
SHOTS=(
  "1-feed|-homeFilter trending"
  "2-post|-homeFilter trending -openPost spike-alice/fake-post-1"
  "3-waves|-startTab WAVES"
  "4-chats|-startTab CHATS -openScreen chat:ch-ecency"
  "5-wallet|-startTab WALLET -openScreen wallet:assetDetails,HIVE"
  "6-notifications|-startTab NOTIFICATION"
)

for shot in "${SHOTS[@]}"; do
  name="${shot%%|*}"
  read -r -a args <<< "${shot#*|}"
  xcrun simctl terminate "$UDID" "$BUNDLE_ID" 2>/dev/null || true
  xcrun simctl launch "$UDID" "$BUNDLE_ID" -fakeData YES -seedAccounts YES "${args[@]}" >/dev/null
  # past the launch splash and the first data
  sleep 9
  raw="$TMP/$name.png"
  xcrun simctl io "$UDID" screenshot --display="$DISPLAY_ID" "$raw" >/dev/null 2>&1
  # the size stays the display's
  opaque_png "$raw" "$OUT/duo-$WHICH-$name.png"
  echo "$OUT/duo-$WHICH-$name.png $(sips -g pixelWidth -g pixelHeight "$OUT/duo-$WHICH-$name.png" | awk '/pixel/ {printf "%s ", $2}')"
done
