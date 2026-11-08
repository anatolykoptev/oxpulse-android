# Contributing

Bug reports and focused fixes are welcome. For a larger change, open an issue first so the approach
can be agreed before you write it. Report a vulnerability privately, as described in
[SECURITY.md](SECURITY.md), not in an issue. The server, the signalling layer and the cryptography
are not in this repository; this one is the native shell only.

## Development

JDK 21, Node 22 and the Android SDK are required. Use an x86_64 host: the SDK's `aapt2` has no
arm64 Linux build.

```sh
git clone https://github.com/anatolykoptev/oxpulse-android.git   # keep full history
cd oxpulse-android
npm ci
CAP_SERVER_URL=https://oxpulse.chat npx cap sync android
bash .github/scripts/assert-cap-token.sh
cd android && ./gradlew assembleDebug test
```

These are the steps of the `build` job in `.github/workflows/android.yml`; CI then also runs the
AGP-runtime and dex-class assertions against the built APK. Do not use a shallow clone: `versionCode`
is derived from `git rev-list --count HEAD`, and a shallow clone produces an APK that cannot upgrade
an existing install. A pull request that changes only documents skips the build.

## Commits

[Conventional Commits](https://www.conventionalcommits.org): `feat:`, `fix:`, `build:`, `docs:`,
`chore:`, `ci:`. release-please reads them on `main`, opens a release PR that bumps the version and
`CHANGELOG.md`, and tags on merge. Do not edit the version, `CHANGELOG.md` or
`.release-please-manifest.json` by hand, and do not tag manually.

## Pull requests

One concern per pull request. State what changed and how you tested it, on which Android version or
device when it touches the foreground service, Bluetooth or battery code. A change to a module's
build file needs the Kotlin plugin applied, which the dex-class assertion checks. Never commit a
keystore, a password or a server address that is not the product's public site. Add new documents to
[docs/INDEX.md](docs/INDEX.md).
