#!/usr/bin/env bash
# Fails unless every class the app cannot start without is present in the APK's
# dex files. A green Gradle build does not prove that: a module whose sources
# were silently skipped still assembles. The manifest is not evidence either,
# since it names MainActivity whether or not the class shipped.
set -euo pipefail
APK=${1:-}
[ -n "$APK" ] && [ -f "$APK" ] || { echo "::error::no APK given or found: '$APK'"; exit 1; }
ls -l "$APK"
dex=$(mktemp)
trap 'rm -f "$dex"' EXIT
unzip -o -q -p "$APK" 'classes*.dex' > "$dex"
fail=0
for c in MainActivity SharePackagePlugin \
         callreliability/VoiceCallForegroundService \
         callreliability/CallReliabilityPlugin \
         mesh/MeshGattServerPlugin \
         com/capacitorjs/community/plugins/bluetoothle/BluetoothLe; do
  # Third-party plugins live outside run/krolik/oxpulse.
  case "$c" in com/*) fq="$c" ;; *) fq="run/krolik/oxpulse/$c" ;; esac
  if grep -aq "L$fq;" "$dex"; then
    echo "  ok      ${fq//\//.}"
  else
    echo "::error::${fq//\//.} is ABSENT from the dex: the APK cannot work"
    fail=1
  fi
done
exit $fail
