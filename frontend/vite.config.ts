import { defineConfig } from 'vite';
import { resolve } from 'node:path';

export default defineConfig({
  root: resolve(__dirname),
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    sourcemap: true,
    target: 'es2022',
    lib: {
      entry: resolve(__dirname, 'src/main.ts'),
      formats: ['iife'],
      name: 'finsight',
      fileName: () => 'app.bundle.js'
    },
    rollupOptions: {
      output: { extend: true }
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  test: {
    // The static `app.js` IIFE still owns the DOM; our typed-client
    // bundle is a thin wrapper around `fetch`. Default to the node
    // environment so `fetch` is the real undici implementation; the
    // tests stub it explicitly. Per-file overrides go to the top of
    // the test file (`// @vitest-environment jsdom`).
    environment: 'node',
    include: ['test/**/*.test.ts']
  }
});
