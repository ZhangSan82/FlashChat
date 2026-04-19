import { ref, readonly } from 'vue'
import { autoRegister, checkLogin, logout as apiLogout } from '@/api/account'
import {
    saveToken, loadToken, clearAll,
    saveIdentity, loadIdentity
} from '@/utils/storage'

const identity = ref(loadIdentity())
const memberId = ref(null)
const isReady = ref(false)
const isLoading = ref(false)

export function useAuth() {

    /**
     * 检查本地是否有 token（同步，不发请求）
     */
    function hasToken() {
        return !!loadToken()
    }

    /**
     * 仅验证现有 token 是否有效，不做 auto-register。
     * 成功返回 true，失败（token 无效/过期）返回 false 并清除本地态。
     */
    async function checkOnly() {
        const token = loadToken()
        if (!token) return false

        try {
            const resp = await checkLogin()
            updateIdentity(resp)
            isReady.value = true
            return true
        } catch (e) {
            console.warn('[Auth] checkOnly 失败，token 无效', e.message)
            clearAll()
            identity.value = null
            isReady.value = false
            return false
        }
    }

    /**
     * 完整初始化：有 token 则验证，无 token 则 auto-register。
     * 注意：扫码场景下不应调用此方法，应使用 checkOnly() 配合路由守卫。
     */
    async function init() {
        if (isReady.value) return
        isLoading.value = true

        try {
            const existingToken = loadToken()

            if (existingToken) {
                try {
                    const resp = await checkLogin()
                    updateIdentity(resp)
                    isReady.value = true
                    return
                } catch (e) {
                    console.warn('[Auth] checkLogin 失败，尝试重新注册', e.message)
                }
            }

            const resp = await autoRegister()
            saveToken(resp.token)
            updateIdentity(resp)
            isReady.value = true
        } finally {
            isLoading.value = false
        }
    }

    function updateIdentity(resp) {
        const data = {
            accountId: resp.accountId,
            nickname: resp.nickname,
            avatarColor: resp.avatarColor,
            avatarUrl: resp.avatarUrl || '',
            isRegistered: resp.isRegistered || false,
            systemRole: Number(resp.systemRole || 0),
            isAdmin: Boolean(resp.isAdmin)
        }
        identity.value = data
        saveIdentity(data)
    }

    function setMemberId(id) {
        memberId.value = id
    }

    function getToken() {
        return loadToken()
    }

    // ★ 新增：账号升级/重新登录后刷新认证状态
    // 升级后 SaToken loginId 从 member_X 变为 user_X，token 会变
    function onAuthRefreshed(resp) {
        if (resp.token) saveToken(resp.token)
        updateIdentity(resp)
    }

    // ★ 新增：局部更新 identity（修改昵称/头像后调用）
    function updateLocalIdentity(partial) {
        if (!identity.value) return
        const updated = { ...identity.value, ...partial }
        identity.value = updated
        saveIdentity(updated)
    }

    function reset() {
        clearAll()
        identity.value = null
        memberId.value = null
        isReady.value = false
        isLoading.value = false
    }

    async function logout() {
        try {
            await apiLogout()
        } catch (e) {
            console.warn('[Auth] logout failed', e?.message || e)
        } finally {
            clearAll()
            reset()
        }
    }

    return {
        identity: readonly(identity),
        memberId: readonly(memberId),
        isReady: readonly(isReady),
        isLoading: readonly(isLoading),
        hasToken,
        checkOnly,
        init,
        setMemberId,
        getToken,
        onAuthRefreshed,
        updateLocalIdentity,
        logout,
        reset
    }
}
