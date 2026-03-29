import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
    plugins: [vue()],

    resolve: {
        alias: {
            // 支持 @/xxx 路径导入（项目已有代码大量使用）
            '@': fileURLToPath(new URL('./src', import.meta.url))
        }
    },

    server: {
        port: 3002,
        // 开发代理：将 /api 请求转发到后端 8081 端口
        // WS 端口 8090 前端直连，不走代理
        proxy: {
            '/api': {
                target: 'http://localhost:8081',
                changeOrigin: true
            }
        }
    }
})