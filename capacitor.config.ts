import type { CapacitorConfig } from '@capacitor/cli';

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
  },
};

export default config;
