import vue from '@vitejs/plugin-vue'
import { defineConfig, loadEnv } from 'vite'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'DATAPILOT_')
  const apiTarget = env.DATAPILOT_API_TARGET ?? process.env.DATAPILOT_API_TARGET ?? 'http://127.0.0.1:8080'
  return {
    plugins: [vue()],
    build: {
      rolldownOptions: {
        output: {
          codeSplitting: {
            groups: [
              { name: 'echarts-charts', test: /node_modules[\\/]echarts[\\/]lib[\\/]chart[\\/]/, priority: 20 },
              { name: 'echarts-components', test: /node_modules[\\/]echarts[\\/]lib[\\/]component[\\/]/, priority: 15 },
              { name: 'echarts-core', test: /node_modules[\\/]echarts[\\/]/, priority: 10 },
            ],
          },
        },
      },
    },
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
