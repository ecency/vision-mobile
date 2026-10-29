#!/usr/bin/env bash
# Checks the update prompt's build settings before they are written into local.properties (release workflows):
#   check-update-source.sh "<releases url>" "<tag prefix>"
# Both are optional; an empty value means the default. A value that is set must be one the app would use
# (UpdateSource.of in shared/.../app/ShellPrompts.kt): the app silently falls back to the default for anything
# else, and a release build must never ship with a source nobody chose. So a set but malformed value, or a line
# break in either (it would add a line to local.properties), fails the build. Stricter than the app on purpose:
# no spaces around the value, ASCII only.
set -euo pipefail

url="${1-}"
prefix="${2-}"
fail() {
  echo "::error::$1 (native/docs/release-builds.md)" >&2
  exit 1
}

nl=$'\n'
cr=$'\r'
for pair in "ECENCY_UPDATE_RELEASES_URL:$url" "ECENCY_UPDATE_TAG_PREFIX:$prefix"; do
  name="${pair%%:*}"
  value="${pair#*:}"
  case "$value" in
    *"$nl"* | *"$cr"*) fail "$name contains a line break" ;;
  esac
done

if [[ -n "$url" ]]; then
  if (( ${#url} > 512 )) || ! [[ "$url" =~ ^https://api\.github\.com/repos/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+/releases/latest$ ]]; then
    fail "ECENCY_UPDATE_RELEASES_URL is set but is not https://api.github.com/repos/<owner>/<repo>/releases/latest"
  fi
fi

if [[ -n "$prefix" ]]; then
  if ! [[ "$prefix" =~ ^[A-Za-z0-9._/-]{1,32}$ ]]; then
    fail "ECENCY_UPDATE_TAG_PREFIX is set but is not 1 to 32 characters of letters, digits and - _ . /"
  fi
fi
