#!/usr/bin/env bash
# Tests scripts/release-apk.sh offline: a local fake of Google's token endpoint and the Play API (Python standard
# library), stand-ins for aapt2 and apksigner that print what the real tools print, and a throwaway RSA key made
# here. No network, no secret, no SDK. Run from the repository root: scripts/release-apk-test.sh
# With REAL_APK=<an APK> and REAL_ANDROID_HOME=<an SDK>, it also runs the script once with the real aapt2 and
# apksigner on that APK (its package, versionCode, versionName and signer read from the APK itself).
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
server_pid=""
cleanup() {
  if [[ -n "$server_pid" ]]; then { kill "$server_pid" && wait "$server_pid"; } 2>/dev/null || true; fi
  rm -rf "$work"
}
trap cleanup EXIT

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$work/key.pem" 2>/dev/null
openssl rsa -in "$work/key.pem" -pubout -out "$work/pub.pem" 2>/dev/null
SERVICE_ACCOUNT_JSON=$(jq -cn --rawfile k "$work/key.pem" '{client_email: "ci@throwaway.example", private_key: $k}')
export SERVICE_ACCOUNT_JSON

cert_hex=$(printf 'play-app-signing-certificate' | openssl dgst -sha256 -hex | sed 's/.*= //')
unhex() { python3 -I -c 'import binascii, sys; sys.stdout.buffer.write(binascii.unhexlify(sys.argv[1]))' "$1"; }
cert_b64=$(unhex "$cert_hex" | openssl base64 -A)

# ---- stand-ins for the build tools: the real output formats, values from FAKE_* ----
mkdir -p "$work/sdk/build-tools/99.0.0"
cat >"$work/sdk/build-tools/99.0.0/aapt2" <<'EOF'
#!/usr/bin/env bash
[[ "$1 $2" == "dump badging" && -s "$3" ]] || exit 1
echo "package: name='${FAKE_PACKAGE:-app.esteem.mobile.android}' versionCode='${FAKE_CODE}' versionName='${FAKE_NAME}' platformBuildVersionName='17' platformBuildVersionCode='37'"
for i in $(seq 1 2000); do echo "uses-permission: name='android.permission.P$i'"; done
EOF
cat >"$work/sdk/build-tools/99.0.0/apksigner" <<'EOF'
#!/usr/bin/env bash
[[ "$1 $2" == "verify --print-certs" ]] || exit 1
echo "Signer #1 certificate DN: CN=Play"
echo "Signer #1 certificate SHA-256 digest: ${FAKE_SIGNER}"
echo "Signer #1 certificate SHA-1 digest: 00"
EOF
chmod +x "$work/sdk/build-tools/99.0.0/aapt2" "$work/sdk/build-tools/99.0.0/apksigner"
export ANDROID_HOME="$work/sdk"

# ---- the fake Google: answers from $work/fake.json, records every request in $work/requests ----
cat >"$work/fake.py" <<'EOF'
import base64, json, subprocess, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlparse

work = sys.argv[1]
def cfg():
    return json.load(open(work + "/fake.json"))

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def send(self, code, body, kind="application/json"):
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(code); self.send_header("Content-Type", kind); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
    def record(self):
        open(work + "/requests", "a").write(self.command + " " + self.path + "\n")
    def authed(self):
        if self.headers.get("Authorization") != "Bearer fake-token":
            self.send(401, {"error": "no token"}); return False
        return True
    def do_POST(self):
        self.record()
        body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode()
        if self.path == "/token":
            c = cfg()
            if c.get("refuseToken"): return self.send(401, {"error": "invalid_grant"})
            form = parse_qs(body)
            jwt = form["assertion"][0]
            head, claims, sig = jwt.split(".")
            pad = lambda s: s + "=" * (-len(s) % 4)
            # the signature must verify with the throwaway key's public half (RS256 over header.claims)
            open(work + "/signed", "w").write(head + "." + claims)
            open(work + "/sig", "wb").write(base64.urlsafe_b64decode(pad(sig)))
            ok = subprocess.run(["openssl", "dgst", "-sha256", "-verify", work + "/pub.pem", "-signature", work + "/sig", work + "/signed"], capture_output=True).returncode == 0
            c2 = json.loads(base64.urlsafe_b64decode(pad(claims)))
            if not ok or form["grant_type"][0] != "urn:ietf:params:oauth:grant-type:jwt-bearer" or c2["iss"] != "ci@throwaway.example" \
               or c2["scope"] != "https://www.googleapis.com/auth/androidpublisher" or c2["aud"] != c["tokenUrl"]:
                return self.send(400, {"error": "bad assertion"})
            return self.send(200, {"access_token": "fake-token"})
        if not self.authed(): return
        if self.path.endswith("/edits"): return self.send(200, {"id": "e1"})
        self.send(404, {})
    def do_DELETE(self):
        self.record()
        if not self.authed(): return
        self.send(204, b"")
    def do_GET(self):
        self.record()
        if not self.authed(): return
        c = cfg(); p = urlparse(self.path)
        if p.path.endswith("/edits/e1/tracks/production"): return self.send(200, c["track"])
        if "/downloads/" in p.path and p.path.endswith(":download") and parse_qs(p.query).get("alt") == ["media"]:
            return self.send(200, open(c["apk"], "rb").read() if c.get("apk") else b"fake universal apk", "application/octet-stream")
        if "/generatedApks/" in p.path: return self.send(200, c["generated"])
        self.send(404, {})

HTTPServer(("127.0.0.1", int(sys.argv[2])), H).serve_forever()
EOF
port=$(python3 -I -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')
export PLAY_API="http://127.0.0.1:$port/androidpublisher/v3" TOKEN_URL="http://127.0.0.1:$port/token"

fake() { # fake <generated cert> [track json] [refuse token]
  jq -n --arg cert "$1" --argjson track "${2:-null}" --arg tokenUrl "$TOKEN_URL" --argjson refuse "${3:-false}" '{
    tokenUrl: $tokenUrl, refuseToken: $refuse,
    track: ($track // {releases: [
      {name: "4.0.0", status: "completed", versionCodes: ["1794600000"]},
      {name: "4.0.1", status: "inProgress", versionCodes: ["1794700000"]},
      {name: "4.0.2", status: "draft", versionCodes: ["1794800000"]}]}),
    generated: {generatedApks: [
      {certificateSha256Hash: "ffff", generatedSplitApks: [{downloadId: "split"}]},
      {certificateSha256Hash: $cert, generatedUniversalApk: {downloadId: "uni/v1"}}]}}' >"$work/fake.json"
  : >"$work/requests"
}
fake "$cert_b64"
python3 -I "$work/fake.py" "$work" "$port" &
server_pid=$!
for _ in $(seq 1 50); do curl -s -o /dev/null "http://127.0.0.1:$port/" && break; sleep 0.1; done

passed=0
check() { # check <name> <expect ok|fail> <output must contain> <version argument> -- <env assignments...>
  local name=$1 expect=$2 needle=$3 version=$4; shift 5
  local out="$work/out-$passed" code=0
  env "$@" "$root/scripts/release-apk.sh" "$version" "$out" >"$work/stdout" 2>"$work/stderr" || code=$?
  if [[ "$expect" == ok && $code -ne 0 ]] || [[ "$expect" == fail && $code -eq 0 ]]; then
    echo "FAIL $name (exit $code)"; cat "$work/stderr"; exit 1
  fi
  if [[ -n "$needle" ]] && ! grep -qF -- "$needle" "$work/stderr" "$work/stdout"; then
    echo "FAIL $name: expected '$needle' in"; cat "$work/stderr" "$work/stdout"; exit 1
  fi
  if grep -qF "fake-token" "$work/stderr" "$work/stdout" || grep -qF "PRIVATE KEY" "$work/stderr" "$work/stdout"; then
    echo "FAIL $name: a secret was printed"; exit 1
  fi
  passed=$((passed + 1))
  echo "ok   $name"
}

# the newest production release that reached users (inProgress 1794700000 over completed; the draft is not one)
check "picks the newest live production build, checks it, writes the APK and its hash" ok "versionCode 1794700000" 4.0.1 -- \
  FAKE_CODE=1794700000 FAKE_NAME=4.0.1 FAKE_SIGNER="$cert_hex"
apk="$work/out-0/Ecency-4.0.1.apk"
[[ "$(cat "$apk")" == "fake universal apk" ]] || { echo "FAIL the APK body"; exit 1; }
(cd "$work/out-0" && shasum -a 256 -c Ecency-4.0.1.apk.sha256 >/dev/null) || { echo "FAIL the .sha256"; exit 1; }
grep -q "GET /androidpublisher/v3/applications/app.esteem.mobile.android/generatedApks/1794700000/downloads/uni%2Fv1:download?alt=media" "$work/requests" ||
  { echo "FAIL the download URL"; cat "$work/requests"; exit 1; }
grep -q "^DELETE .*/edits/e1$" "$work/requests" || { echo "FAIL the edit was not dropped"; exit 1; }
echo "ok   the download id is URL encoded and the edit is dropped"

: >"$work/requests"
check "a given versionCode skips the track" ok "versionCode 1794600000" 4.0.0 -- \
  VERSION_CODE=1794600000 FAKE_CODE=1794600000 FAKE_NAME=4.0.0 FAKE_SIGNER="$cert_hex"
! grep -q "/edits" "$work/requests" || { echo "FAIL edits were opened"; exit 1; }

fake "$(printf '%s' "$cert_hex" | tr 'a-f' 'A-F' | sed 's/../&:/g; s/:$//')"
check "Play's hash as colon separated upper case hex" ok "" 4.0.1 -- FAKE_CODE=1794700000 FAKE_NAME=4.0.1 FAKE_SIGNER="$cert_hex"

fake "$cert_b64"
check "the newest build is another version" fail "versionCode 1794700000 is version 4.0.1, not 4.0.0" 4.0.0 -- \
  FAKE_CODE=1794700000 FAKE_NAME=4.0.1 FAKE_SIGNER="$cert_hex"
check "signed by another key" fail "Play names $cert_hex" 4.0.1 -- \
  FAKE_CODE=1794700000 FAKE_NAME=4.0.1 FAKE_SIGNER="$(printf '%064d' 0)"
check "another package" fail "not app.esteem.mobile.android" 4.0.1 -- \
  FAKE_PACKAGE=com.example FAKE_CODE=1794700000 FAKE_NAME=4.0.1 FAKE_SIGNER="$cert_hex"

fake "$cert_b64" '{"releases":[{"status":"draft","versionCodes":["1"]},{"status":"halted","versionCodes":["2"]}]}'
check "nothing live on production" fail "no completed or rolling out release" 4.0.1 -- FAKE_CODE=1 FAKE_NAME=4.0.1 FAKE_SIGNER="$cert_hex"

fake "$cert_b64" null true
check "the service account is refused" fail "Google refused the service account" 4.0.1 -- FAKE_CODE=1 FAKE_NAME=4.0.1 FAKE_SIGNER="$cert_hex"

fake "$cert_b64"
check "a tag that is not X.Y.Z" fail "the version must be X.Y.Z" v4.0.1 -- FAKE_CODE=1 FAKE_NAME=4.0.1 FAKE_SIGNER="$cert_hex"

if [[ -n "${REAL_APK:-}" && -n "${REAL_ANDROID_HOME:-}" ]]; then
  tools=$(ls -d "$REAL_ANDROID_HOME"/build-tools/* | sort -V | tail -1)
  badging=$("$tools/aapt2" dump badging "$REAL_APK")
  badging="${badging%%$'\n'*}"
  real_code=$(sed -n "s/.* versionCode='\([^']*\)'.*/\1/p" <<<"$badging")
  real_name=$(sed -n "s/.* versionName='\([^']*\)'.*/\1/p" <<<"$badging")
  real_package=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badging")
  certs=$("$tools/apksigner" verify --print-certs "$REAL_APK")
  real_cert=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$certs")
  fake "$(unhex "${real_cert%%$'\n'*}" | openssl base64 -A)"
  jq --arg apk "$REAL_APK" '.apk = $apk' "$work/fake.json" >"$work/fake2.json" && mv "$work/fake2.json" "$work/fake.json"
  check "the real aapt2 and apksigner on $REAL_APK" ok "versionCode $real_code" "$real_name" -- \
    ANDROID_HOME="$REAL_ANDROID_HOME" PACKAGE_NAME="$real_package" VERSION_CODE="$real_code"
  cmp -s "$REAL_APK" "$work/out-$((passed - 1))/Ecency-$real_name.apk" || { echo "FAIL the real APK changed on the way"; exit 1; }
fi

echo "$passed checks passed"
