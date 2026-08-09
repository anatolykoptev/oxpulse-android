# OxPulse for Android

The Android app for [OxPulse](https://oxpulse.chat), an end-to-end encrypted calling service.

This repository holds the **native shell only**. It exists so a call keeps running when you leave the app — something a web page cannot do on its own.

## What this app actually is

The app does not contain the OxPulse interface. `capacitor.config.ts` points the WebView at `https://oxpulse.chat`, so you are using the same web app either way. What the shell adds is the set of things Android will not give a browser tab:

| Source | What it does |
|---|---|
| [`VoiceCallForegroundService.kt`](android/call-reliability-lib/src/main/java/run/krolik/oxpulse/callreliability/VoiceCallForegroundService.kt) | A `phoneCall\|microphone\|camera` foreground service, so a call survives leaving the app instead of being suspended |
| [`OemAutostart.kt`](android/call-reliability-lib/src/main/java/run/krolik/oxpulse/callreliability/OemAutostart.kt) | Guides the user through the vendor autostart screens that Xiaomi, Huawei, Oppo and others use to kill background apps |
| [`BatteryOptimization.kt`](android/call-reliability-lib/src/main/java/run/krolik/oxpulse/callreliability/BatteryOptimization.kt) | Requests the battery-optimisation exemption a long call needs |
| [`MicWatch.kt`](android/call-reliability-lib/src/main/java/run/krolik/oxpulse/callreliability/MicWatch.kt) | Notices when the microphone is taken away by another app or by the system |
| [`NetworkCallbackManager.kt`](android/call-reliability-lib/src/main/java/run/krolik/oxpulse/callreliability/NetworkCallbackManager.kt) | Tracks network transitions so a call can recover across Wi-Fi/mobile handover |
| [`MeshGattServerPlugin.kt`](android/mesh-core-lib/src/main/java/run/krolik/oxpulse/mesh/MeshGattServerPlugin.kt) | Bluetooth LE GATT server for local peer discovery |

Because the interface is loaded from the web, updates to it reach you immediately — they do not wait on a store review. This repository changes only when the native layer does.

## Why it is public

We ask people to install an APK from a website. That is, structurally, exactly how malware is delivered, and "trust us" is not an answer we are willing to give to people who are relying on this software to talk safely.

So the shell that holds the microphone permission, the foreground service and the Bluetooth stack is readable here, and every release is built by [the workflow in this repository](.github/workflows/android.yml) rather than on someone's laptop. The build publishes the APK's SHA-256 next to it, so you can check that the file you downloaded is the file the workflow produced.

The server, the signalling layer and the cryptography are not in this repository.

## Install

Releases are on the [releases page](../../releases).

**Check the file is the one CI built.** Download `apk-sha256.txt` alongside the APK:

```sh
sha256sum -c apk-sha256.txt
```

**Check who signed it.** The hash only proves the file is intact; the signature proves who
made it. Every release is signed by this key, and it will not change:

```
SHA256: A1:CD:92:83:F2:3B:7C:AA:78:FB:62:BF:F3:BD:4E:C2:42:09:7C:18:C3:69:74:7A:6E:F0:9E:F7:42:0B:2E:52
```

```sh
apksigner verify --print-certs app-release.apk
```

If that fingerprint differs from the one printed above, do not install the file — whatever
you downloaded was not built and signed by this project.

Android will warn about installing from an unknown source. That warning is correct and you
should read it — it is the same warning you would get for anything not from a store.

## Build it yourself

Requires JDK 17, Node 22, and the Android SDK.

```sh
npm ci
npx cap sync android
cd android && ./gradlew assembleDebug
```

The output lands in `android/app/build/outputs/apk/debug/`.

To point the app at a different backend, set `CAP_SERVER_URL` before `cap sync`.

Two things worth knowing before you file a build issue:

- **`aapt2` in the Android SDK is x86-64 only.** On an aarch64 Linux host the build fails with `AAPT2 Daemon startup failed`. This is why CI runs on `ubuntu-24.04` rather than an arm64 runner.
- **`versionCode` comes from `git rev-list --count HEAD`.** A shallow clone makes it 1, which produces an APK that will not upgrade an existing install. CI checks out with `fetch-depth: 0` for this reason.

## Signing

Signing material is never in this repository. CI injects a keystore from encrypted secrets, and the release job fails loudly if any of them is unset — without that check an absent keystore would silently produce an unsigned APK that looks like a successful build.

The same signing key is used for every distribution channel deliberately. If the direct APK and a store build were signed by different keys, Android would refuse to upgrade one with the other, and a user could not move between channels without uninstalling and losing their data.

## Licence

AGPL-3.0-only. See [LICENSE](LICENSE).
