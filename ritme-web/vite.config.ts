import { defineConfig } from 'vitest/config';
import preact from '@preact/preset-vite';

// In development the Ktor server runs on :8080 (`./gradlew -p ritme-server run`); in production it
// serves this build itself, so the WebSocket and /api are same-origin and need no CORS.
export default defineConfig({
  plugins: [preact()],
  server: {
    proxy: {
      '/ritme': { target: 'ws://localhost:8080', ws: true },
      '/api': { target: 'http://localhost:8080' },
    },
  },
  build: {
    target: 'es2022',
    sourcemap: false,
    // hls.js (~600 kB) is its own lazy chunk, fetched only for HLS URL entries.
    chunkSizeWarningLimit: 700,
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
  },
});
