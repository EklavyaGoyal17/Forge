/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// FORGE API runs on 8090 (5173/8080 are commonly taken by other local projects).
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    host: '127.0.0.1',
    port: 5190,
    strictPort: true,
    proxy: { '/api': 'http://127.0.0.1:8090', '/actuator': 'http://127.0.0.1:8090' },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
})
