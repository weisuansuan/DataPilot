import vue from '@vitejs/plugin-vue'
import { defineConfig, loadEnv } from 'vite'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'DATAPILOT_')
  const apiTarget = env.DATAPILOT_API_TARGET ?? process.env.DATAPILOT_API_TARGET ?? 'http://127.0.0.1:8080'
  return {
    plugins: [vue()],
    server: {
      host: '127.0.0.1',
      port: 5173,
      strictPort: true,
      proxy: {
        '/actuator': apiTarget,
        '/api': apiTarget,
      },
    },
    preview: {
      host: '127.0.0.1',
      port: 4173,
      strictPort: true,
    },
  }
})
