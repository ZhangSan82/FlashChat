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
            isRegistered: resp.isRegistered || false
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
        init,
        setMemberId,
        getToken,
        onAuthRefreshed,
        updateLocalIdentity,
        logout,
        reset
    }
}
