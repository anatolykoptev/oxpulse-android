#!/usr/bin/env bash
# Fails unless every R8 marker in the APK's dex files reports an 8.13.x R8.
#
# R8 stamps the dex string table with ~~R8{...,"version":"8.13.19"}. Reading it
# from the shipped artifact proves which R8 actually minified it, regardless of
# what any classpath listing claims.
set -euo pipefail
APK=${1:-}
[ -n "$APK" ] && [ -f "$APK" ] || { echo "::error::no APK given or found: '$APK'"; exit 1; }
markers=$(unzip -o -q -p "$APK" 'classes*.dex' | strings -a | grep '~~R8' || true)
[ -n "$markers" ] || { echo "::error::no ~~R8 marker in $APK: the APK was not minified by R8"; exit 1; }
echo "$markers" | sort | uniq -c
if echo "$markers" | grep -qv '"version":"8\.13'; then
  echo "::error::an R8 marker is not version 8.13.x (expected AGP 8.13.2's bundled R8)"
  exit 1
fi
echo "  ok      R8 8.13.x"
