import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// GuardLab frontend. In mock mode the app runs fully standalone (no backend).
// When the Spring backend is ready, set VITE_USE_MOCK=false and VITE_API_BASE.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port: 5173,
      // Proxy real API calls to the Spring backend during integration.
      proxy: {
        '/api': {
          target: env.VITE_API_BASE || 'http://localhost:8080',
          changeOrigin: true,
        },
        // 로컬 LLM(Ollama) 프록시 — 브라우저 CORS 회피(서버측 프록시)
        '/ollama': {
          target: process.env.OLLAMA_BASE || 'http://localhost:11434',
          changeOrigin: true,
          rewrite: (p) => p.replace(/^\/ollama/, ''),
        },
      },
    },
  }
})
