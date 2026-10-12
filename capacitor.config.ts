import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { CapacitorConfig } from '@capacitor/cli';

// The web app gates BLE mesh on this number: mesh runs only when the shell's
// bluetooth-le major equals the major bundled in the web JS. bluetooth-le 7.0
// changed the JS-to-native hex encoding, so a mismatched pair corrupts writes
// (JS newer than native) or rejects them (JS older than native). Derived from
// the installed package, never typed, so it cannot drift from what the APK
// actually contains. `cap sync` runs from the repo root, where node_modules is.
const bleMajor = ((): number => {
  const pkgPath = join(process.cwd(), 'node_modules/@capacitor-community/bluetooth-le/package.json');
  const major = Number.parseInt(String(JSON.parse(readFileSync(pkgPath, 'utf8')).version), 10);
  if (!Number.isInteger(major) || major < 1) {
    throw new Error(`cannot derive the bluetooth-le major from ${pkgPath}; run npm ci first`);
  }
  return major;
})();

const config: CapacitorConfig = {
  appId: 'run.krolik.oxpulse',
  appName: 'OxPulse',
  // Placeholder. This app bundles no web UI — `server.url` below points the
  // WebView at the live site. Capacitor still requires webDir to exist.
  webDir: 'www',
  server: {
    androidScheme: 'https',
    // Fail build if CAP_SERVER_URL is unset — without this, dev/test builds
    // silently load production content from oxpulse.chat, which makes it
    // impossible to test changes against a staging server (issue #11).
    url: process.env.CAP_SERVER_URL ?? (() => { throw new Error('CAP_SERVER_URL is not set — set it to your server URL (e.g. https://staging.oxpulse.chat)') })(),
    cleartext: false,
    allowNavigation: ['oxpulse.chat', '*.krolik.run'],
  },
  android: {
    minWebViewVersion: 80,
    // Two whitespace-delimited tokens. OxPulseShell/1 identifies the shell;
    // OxPulseCap/<N> is the bluetooth-le major. Capacitor appends this string
    // itself, so MainActivity must not also edit the user agent.
    appendUserAgent: `OxPulseShell/1 OxPulseCap/${bleMajor}`,
    allowMixedContent: false,
    captureInput: true,
    buildOptions: {
      releaseType: 'APK',
    },
  },
  plugins: {
    SplashScreen: {
      launchShowDuration: 600,
      backgroundColor: '#0d0e10',
      showSpinner: false,
    },
    // Targeting SDK 36 forces edge-to-edge. The web app already sets
    // viewport-fit=cover and reads env(safe-area-inset-*); the hint lets the
    // plugin pick the right inset handling before the first page commit.
    SystemBars: {
      insetsHandling: 'css',
      initialViewportFitValueHint: 'cover',
    },
  },
};

export default config;
