import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { cpSync, existsSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(fileURLToPath(import.meta.url));

/**
 * Cesium ships ~5 MB of runtime assets (workers, wasm, imagery, widgets) that must be served as
 * plain static files rather than bundled. Cesium's own Vite sample copies them with
 * vite-plugin-static-copy; we do it with three lines of node:fs instead of adding a dependency.
 *
 * They land in `public/cesiumStatic/`, which Vite serves at `/cesiumStatic/` in dev AND copies
 * into `dist/` on build. That folder is gitignored.
 */
const CESIUM_SOURCE = resolve(root, 'node_modules/cesium/Build/Cesium');
const CESIUM_DEST = resolve(root, 'public/cesiumStatic');
const CESIUM_BASE_URL = '/cesiumStatic';

function copyCesiumAssets() {
  if (!existsSync(CESIUM_SOURCE)) {
    console.warn(
      '\n[cesium] node_modules/cesium not found. Run `npm install` before `npm run dev`.\n'
    );
    return;
  }
  mkdirSync(CESIUM_DEST, { recursive: true });
  for (const dir of ['Workers', 'ThirdParty', 'Assets', 'Widgets']) {
    cpSync(resolve(CESIUM_SOURCE, dir), resolve(CESIUM_DEST, dir), { recursive: true });
  }
}

copyCesiumAssets();

export default defineConfig({
  plugins: [react()],
  // Cesium reads this global at runtime to locate its workers and assets.
  define: {
    CESIUM_BASE_URL: JSON.stringify(CESIUM_BASE_URL),
  },
  server: {
    port: 5173,
    // Proxy API + WebSocket calls to the Spring Boot backend so the browser stays
    // same-origin and we never have to think about CORS during development.
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/ws': { target: 'ws://localhost:8080', ws: true },
    },
  },
  build: {
    chunkSizeWarningLimit: 8000,
    target: 'es2022',
  },
});
