# CLAUDE.md

Notes for coding agents working in oxpulse-android: the native Android shell (Capacitor 8) around the
OxPulse web app. It bundles no web UI; the WebView loads the site named by `CAP_SERVER_URL`, and the
shell adds what a browser tab cannot (a call foreground service, battery and autostart handling,
microphone and network watching, a Bluetooth LE GATT server). Every document is indexed in
[docs/INDEX.md](docs/INDEX.md).

## Layout

`android/app` holds `MainActivity.kt` and `SharePackagePlugin.kt`; `android/call-reliability-lib`
and `android/mesh-core-lib` are library modules, each applying the Kotlin plugin itself.
`capacitor.config.ts` is the Capacitor configuration; `www/` is a placeholder web directory.
`.github/scripts/` holds the assertions CI runs against the built artifacts.

## Gate

CI (`.github/workflows/android.yml`, job `build` on `ubuntu-24.04`, not arm) runs, from the
repository root with JDK 21 and Node 22:

```sh
npm ci
CAP_SERVER_URL=https://oxpulse.chat npx cap sync android
bash .github/scripts/assert-cap-token.sh
cd android && ./gradlew assembleDebug --no-daemon --console=plain
cd android && ./gradlew test --no-daemon --console=plain
```

followed, on a branch build, by `assert-agp-runtime` and `assert-dex-classes.sh` against the debug APK.
On a release tag the job builds, signs and verifies the release APK instead (`assert-dex-classes.sh`,
`assert-r8-version.sh`, `verify-apk-signed.sh`), and a `release-probe` job runs the same release
assertions on pull requests. A docs-only PR (Markdown, `docs/`, licence, issue and PR templates)
skips `build` through the `docs-scope` job; that block is canonical across the fleet and compared
byte for byte, so do not edit it here.

## Invariants that are easy to break

- **`CAP_SERVER_URL` is mandatory** for `cap sync`; `capacitor.config.ts` throws without it. Never
  default it to production in code.
- **The BLE major must match.** `OxPulseCap/<N>` in the user agent is derived from the installed
  `@capacitor-community/bluetooth-le` major; the web app turns BLE mesh on only when it equals the
  major bundled in its JS. A mismatch corrupts or rejects BLE writes silently.
  `assert-cap-token.sh` enforces it; bump the plugin, `@capacitor/core` and `@capacitor/android`
  majors together.
- **Every module must compile its Kotlin.** A module without the Kotlin plugin silently skips its
  `.kt` files and ships an APK with no `MainActivity`. `assert-dex-classes.sh` guards it; keep
  `kotlin-android` applied in each module.
- **`versionCode` is `git rev-list --count HEAD` plus `versionCodeOffset` (1000).** It must only go
  up (Android refuses a downgrade), so never rewrite history and never lower the offset; CI checks
  out with `fetch-depth: 0` for it.
- **Never commit signing material.** The keystore comes from encrypted secrets at release time, and
  the release job fails if one is unset. The release key fingerprint printed in the README is public
  by design; do not change it without a key rotation plan.
- **Versions come from release-please** (Conventional Commits). Never edit `package.json`'s version,
  `CHANGELOG.md` or `.release-please-manifest.json` by hand, and never tag manually.
- **Build on x86_64 Linux or macOS.** The SDK's `aapt2` has no arm64 Linux build, so do not move the
  CI job to an arm runner.
- **This repository is public.** No hostnames other than the product's public site, no operator
  paths, account names, tokens or personal data in any tracked file. The server and the cryptography
  are deliberately not here.
