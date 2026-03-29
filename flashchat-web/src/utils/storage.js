/**
 * 本地持久化存储
 *
 * 存储两个独立的 key：
 *   flashchat_token    — SaToken 令牌（字符串）
 *   flashchat_identity — 用户身份信息（JSON 对象）
 *
 * 分开存储的原因：
 *   token 变更频率低（只有注册/登录/升级时才变）
 *   identity 可能因改昵称等操作需要单独更新
 */

const TOKEN_KEY = 'flashchat_token'
const IDENTITY_KEY = 'flashchat_identity'

// ==================== Token ====================

export function saveToken(token) {
    try {
        if (token) localStorage.setItem(TOKEN_KEY, token)
    } catch {}
}

export function loadToken() {
    try {
        return localStorage.getItem(TOKEN_KEY) || null
    } catch {
        return null
    }
}

export function clearToken() {
    try {
        localStorage.removeItem(TOKEN_KEY)
    } catch {}
}

// ==================== Identity ====================

/**
 * 存储用户身份信息
 * @param {Object} data - { accountId, nickname, avatarColor, avatarUrl, isRegistered }
 */
export function saveIdentity(data) {
    try {
        localStorage.setItem(IDENTITY_KEY, JSON.stringify(data))
    } catch {}
}

export function loadIdentity() {
    try {
        const raw = localStorage.getItem(IDENTITY_KEY)
        return raw ? JSON.parse(raw) : null
    } catch {
        return null
    }
}

export function clearIdentity() {
    try {
        localStorage.removeItem(IDENTITY_KEY)
    } catch {}
}

// ==================== 全量清除 ====================

/**
 * 清除所有认证数据（登出/401/注销 时调用）
 */
export function clearAll() {
    clearToken()
    clearIdentity()
}