import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig(({ mode }) => {
    const env = loadEnv(mode, process.cwd(), '')
    const proxyTarget = env.VITE_PROXY_TARGET || 'http://localhost:8081'

    return {
        plugins: [vue()],

        resolve: {
            alias: {
                '@': fileURLToPath(new URL('./src', import.meta.url))
            }
        },

        server: {
            host: '0.0.0.0',
            port: 3002,
            proxy: {
                '/api': {
                    target: proxyTarget,
                    changeOrigin: true
                }
            }
        },

        preview: {
            host: '0.0.0.0',
            port: 3002
        }
    }
})
