/**
 * HTTP 请求封装
 *
 * 核心职责：
 *   1. 自动注入 satoken Header（从 localStorage 读取）
 *   2. 统一解包 Result<T> 结构，业务层直接拿 data
 *   3. 401 抛出带标记的错误，由调用方决定如何处理（不自动 reload）
 *
 * 401 处理策略：
 *   不在拦截器中 reload（防止 auto-register 也 401 时死循环）
 *   而是清除本地凭证 + 抛出 isAuthError 标记的错误
 *   useAuth.init() 中 checkLogin 捕获 401 → 走 autoRegister
 *   FlashChat.vue 中 doInit 捕获最终失败 → 展示错误页面 + 重试按钮
 */
import axios from 'axios'
import { loadToken, clearAll } from '@/utils/storage'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api/FlashChat/v1'

const request = axios.create({
    baseURL: API_BASE_URL,
    timeout: 10000,
    headers: { 'Content-Type': 'application/json' }
})

// ==================== 请求拦截器 ====================
request.interceptors.request.use(
    (config) => {
        const token = loadToken()
        if (token) {
            config.headers['satoken'] = token
        }
        return config
    },
    (error) => Promise.reject(error)
)

// ==================== 响应拦截器 ====================
request.interceptors.response.use(
    (response) => {
        const res = response.data

        if (String(res.code) === '200') {
            return res.data
        }

        const errMsg = res.message || `业务错误 [${res.code}]`
        console.warn('[API 业务错误]', errMsg)
        return Promise.reject(new Error(errMsg))
    },
    (error) => {
        if (error.response) {
            const status = error.response.status

            if (status === 401) {
                // 清除本地凭证，但不自动 reload
                // 由调用方根据 isAuthError 标记决定后续处理
                console.warn('[API] 401 — Token 失效')
                clearAll()
                const authErr = new Error('Token 失效，请重新登录')
                authErr.isAuthError = true
                return Promise.reject(authErr)
            }

            const data = error.response.data
            const msg = data?.message || data?.msg || `HTTP ${status}`
            console.error('[API]', status, msg)
            return Promise.reject(new Error(msg))
        }

        if (error.code === 'ERR_NETWORK') {
            console.error('[API] 网络异常 — 请确认后端服务已启动（8081 端口）')
        }
        return Promise.reject(error)
    }
)

export default request
