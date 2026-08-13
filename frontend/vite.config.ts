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
      fileName: 'app.bundle'
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
    environment: 'jsdom',
    include: ['test/**/*.test.ts']
  }
});
