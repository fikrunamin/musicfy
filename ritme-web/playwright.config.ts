import { fileURLToPath } from 'node:url';
import { defineConfig, devices } from '@playwright/test';

const PORT = 47821;

/**
 * Runs the real Ritme server (built with Gradle; needs JDK 21 on PATH or JAVA_HOME) serving
 * this app's dist/, so `npm run build` must run first.
 */
export default defineConfig({
  testDir: './e2e',
  // Its own folder: Playwright empties outputDir on every run, and test-results/redesign holds the
  // before/after screenshot sets.
  outputDir: 'test-results/e2e',
  workers: 1,
  retries: 0,
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    baseURL: `http://localhost:${PORT}`,
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'mobile-chromium', use: { ...devices['Pixel 7'] } }],
  webServer: {
    command:
      'cd .. && ./gradlew --console=plain -q -p ritme-server installDist && exec ritme-server/build/install/ritme-server/bin/ritme-server',
    url: `http://localhost:${PORT}/healthz`,
    env: { PORT: String(PORT), WEB_DIR: fileURLToPath(new URL('./dist', import.meta.url)) },
    timeout: 300_000,
    // Never talk to some other server that happens to hold the port.
    reuseExistingServer: false,
  },
});
