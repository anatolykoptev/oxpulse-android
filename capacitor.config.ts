import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'run.krolik.oxpulse',
  appName: 'OxPulse',
  // Placeholder. This app bundles no web UI — `server.url` below points the
  // WebView at the live site. Capacitor still requires webDir to exist.
  webDir: 'www',
  server: {
    androidScheme: 'https',
    url: process.env.CAP_SERVER_URL || 'https://oxpulse.chat',
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
