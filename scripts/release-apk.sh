#!/usr/bin/env bash
# Fetches Google Play's universal APK of a release, for the GitHub release (docs/release-builds.md, "APK for devices
# without Google Play"). Play signs it with the app signing key it already signs Play installs with, so the APK and
# a Play install update each other; a key of ours would make users uninstall (losing their data) to switch.
#
#   release-apk.sh <version X.Y.Z> <output dir>
#
# Environment:
#   SERVICE_ACCOUNT_JSON  the Play service account key (the Play upload's secret), never printed
#   VERSION_CODE          optional: the Play versionCode to fetch; default the newest production release
#   PACKAGE_NAME          default app.esteem.mobile.android
#   PLAY_API, TOKEN_URL   default Google's; the test points them at a local fake (scripts/release-apk-test.sh)
#   ANDROID_HOME          the SDK whose newest build-tools (aapt2, apksigner) check the APK
#
# Writes <output dir>/Ecency-<version>.apk and its .sha256, after checking that the APK is that package, that
# version name and versionCode, and that it is signed by the certificate Play names for it.
set -euo pipefail

version="${1-}"
out="${2-}"
package="${PACKAGE_NAME:-app.esteem.mobile.android}"
api="${PLAY_API:-https://androidpublisher.googleapis.com/androidpublisher/v3}/applications/$package"

fail() {
  echo "::error::$1 (docs/release-builds.md)" >&2
  exit 1
}

[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "the version must be X.Y.Z, got '$version'"
[[ -n "$out" ]] || fail "usage: release-apk.sh <version> <output dir>"
[[ -n "${SERVICE_ACCOUNT_JSON:-}" ]] || fail "SERVICE_ACCOUNT_JSON is not set"

tools=$( (ls -d "${ANDROID_HOME:?ANDROID_HOME is not set}"/build-tools/* 2>/dev/null || true) | sort -V | tail -1)
[[ -x "$tools/aapt2" && -x "$tools/apksigner" ]] || fail "no build-tools with aapt2 and apksigner under ANDROID_HOME"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
umask 077

# ---- an access token: a JWT signed with the service account's key (RS256), traded at Google's token endpoint ----
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
email=$(jq -r '.client_email // empty' <<<"$SERVICE_ACCOUNT_JSON")
[[ -n "$email" ]] || fail "SERVICE_ACCOUNT_JSON has no client_email"
jq -r '.private_key // empty' <<<"$SERVICE_ACCOUNT_JSON" >"$work/key.pem"
[[ -s "$work/key.pem" ]] || fail "SERVICE_ACCOUNT_JSON has no private_key"
token_url="${TOKEN_URL:-https://oauth2.googleapis.com/token}"
now=$(date +%s)
header=$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)
claims=$(jq -cn --arg iss "$email" --arg aud "$token_url" --argjson iat "$now" \
  '{iss: $iss, scope: "https://www.googleapis.com/auth/androidpublisher", aud: $aud, iat: $iat, exp: ($iat + 900)}' | b64url)
signature=$(printf '%s.%s' "$header" "$claims" | openssl dgst -sha256 -sign "$work/key.pem" -binary | b64url)
rm -f "$work/key.pem"
token=$(curl -sS --fail-with-body -X POST "$token_url" \
  --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer' \
  --data-urlencode "assertion=$header.$claims.$signature" | jq -r '.access_token // empty') || fail "Google refused the service account (token request)"
[[ -n "$token" ]] || fail "Google answered the token request without a token"

play() { curl -sS --fail-with-body -H "Authorization: Bearer $token" "$@"; }

# ---- the versionCode: given, or the newest release on the production track (an edit opened and dropped) ----
code="${VERSION_CODE:-}"
if [[ -z "$code" ]]; then
  edit=$(play -X POST -H 'Content-Type: application/json' -d '{}' "$api/edits" | jq -r '.id // empty') ||
    fail "Play refused to open an edit (the service account needs access to the app)"
  track=$(play "$api/edits/$edit/tracks/production") || fail "Play refused to read the production track"
  play -X DELETE "$api/edits/$edit" >/dev/null || true
  # releases that reached users (completed, or a staged rollout in progress)
  code=$(jq -r '[.releases[]? | select(.status == "completed" or .status == "inProgress") | .versionCodes[]? | tonumber] | max // empty' <<<"$track")
  [[ -n "$code" ]] || fail "the production track has no completed or rolling out release yet: publish the GitHub release once $version is live on Play, or pass its versionCode"
fi
[[ "$code" =~ ^[0-9]+$ ]] || fail "the versionCode must be a number, got '$code'"

# ---- Play's universal APK of that build, and the certificate Play signed it with ----
list=$(play "$api/generatedApks/$code") ||
  fail "Play refused to list the generated APKs of $code (the service account needs \"Release to production, exclude devices, and use Play App Signing\")"
pick=$(jq -c '[.generatedApks[]? | select(.generatedUniversalApk.downloadId) | {cert: .certificateSha256Hash, id: .generatedUniversalApk.downloadId}] | first // empty' <<<"$list")
[[ -n "$pick" ]] || fail "Play has no universal APK for versionCode $code"
download_id=$(jq -r '.id' <<<"$pick")
cert=$(jq -r '.cert // empty' <<<"$pick")

apk="$work/app.apk"
play -o "$apk" "$api/generatedApks/$code/downloads/$(jq -rn --arg s "$download_id" '$s | @uri'):download?alt=media" ||
  fail "Play refused the APK download of $code"

# ---- checks: package, version, signer ----
# whole outputs, first lines taken here: a reader that stops early would fail the tool with SIGPIPE under pipefail
badging=$("$tools/aapt2" dump badging "$apk" 2>/dev/null) || fail "the download is not an APK"
badging="${badging%%$'\n'*}"
field() { sed -n "s/.* $1='\([^']*\)'.*/\1/p" <<<"$badging"; }
[[ "$(field name)" == "$package" ]] || fail "the APK is $(field name), not $package"
[[ "$(field versionCode)" == "$code" ]] || fail "the APK has versionCode $(field versionCode), not $code"
[[ "$(field versionName)" == "$version" ]] ||
  fail "versionCode $code is version $(field versionName), not $version: pass the versionCode of $version"

# Play's hash is hex or base64 depending on the API surface: compare as lower case hex
want=$(tr -d ':' <<<"$cert" | tr 'A-F' 'a-f')
if ! [[ "$want" =~ ^[0-9a-f]{64}$ ]]; then
  want=$(openssl base64 -d -A <<<"$cert" 2>/dev/null | od -An -tx1 | tr -d ' \n')
fi
[[ "$want" =~ ^[0-9a-f]{64}$ ]] || fail "Play named no readable signing certificate for $code"
certs=$("$tools/apksigner" verify --print-certs "$apk" 2>/dev/null) || fail "the APK's signature does not verify"
signed=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$certs")
signed="${signed%%$'\n'*}"
[[ "$signed" == "$want" ]] || fail "the APK is signed by $signed, Play names $want"

mkdir -p "$out"
name="Ecency-$version.apk"
mv "$apk" "$out/$name"
(cd "$out" && shasum -a 256 "$name" >"$name.sha256" 2>/dev/null || sha256sum "$name" >"$name.sha256")
echo "Ecency $version (versionCode $code), signed by $signed: $out/$name"
