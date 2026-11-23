import { defineConfig } from '@playwright/test';
import base from './playwright.config';

/**
 * Redesign screenshots (not assertions): `npm run shots` (after `npm run build`), optionally
 * `-- --grep @room`. Same server and fake YouTube as the e2e; PNGs land in
 * test-results/redesign/<project>/<screen>.png (gitignored) for comparison with the handoff.
 */
export default defineConfig({
  ...base,
  testMatch: /.*\.shots\.ts$/,
  outputDir: 'test-results/shots-artifacts',
  reporter: 'list',
  timeout: 120_000,
  projects: [
    { name: 'mobile-dark', use: { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, colorScheme: 'dark' } },
    { name: 'mobile-light', use: { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, colorScheme: 'light' } },
    { name: 'desktop-dark', use: { viewport: { width: 1440, height: 900 }, colorScheme: 'dark' } },
    { name: 'desktop-light', use: { viewport: { width: 1440, height: 900 }, colorScheme: 'light' } },
  ],
});
