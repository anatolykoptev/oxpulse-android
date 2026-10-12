#!/usr/bin/env bash
# Fails unless the APK carries a valid signature, and prints its certificates.
# An unsigned APK is a plausible-looking build artefact, so the signature is
# asserted rather than inferred from Gradle's exit code.
#
# apksigner comes from the newest installed build-tools, not a pinned directory:
# AGP installs the version it needs, and a hardcoded one breaks when the image
# changes.
set -euo pipefail
APK=${1:-}
[ -n "$APK" ] && [ -f "$APK" ] || { echo "::error::no APK given or found: '$APK'"; exit 1; }
BT=$(ls -1 "$ANDROID_HOME/build-tools" | grep -E '^[0-9]+(\.[0-9]+){2}$' | sort -V | tail -1 || true)
[ -n "$BT" ] && [ -x "$ANDROID_HOME/build-tools/$BT/apksigner" ] \
  || { echo "::error::no apksigner under $ANDROID_HOME/build-tools"; exit 1; }
echo "apksigner from build-tools $BT"
"$ANDROID_HOME/build-tools/$BT/apksigner" verify --print-certs "$APK"
