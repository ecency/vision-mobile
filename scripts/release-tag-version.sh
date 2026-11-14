#!/usr/bin/env bash
# The app version a release tag names, as the update prompt reads tags (UpdateModel.versionOf): the update prefix
# followed by plain X.Y.Z and nothing else. Prints the version, or nothing for any other tag (rn-3.5.11-final,
# v4.0.0, 4.0.0-beta, a tag without the prefix).
#   release-tag-version.sh <tag> [prefix]
set -euo pipefail
tag="${1-}"
prefix="${2-}"
version="${tag#"$prefix"}"
if [[ "$tag" == "$prefix$version" && "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "$version"
fi
