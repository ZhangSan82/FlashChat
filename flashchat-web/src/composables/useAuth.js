import { ref, readonly } from 'vue'
import { autoRegister, getMemberInfo } from '@/api/member'
import { saveIdentity, loadIdentity, clearIdentity } from '@/utils/storage'

const identity = ref(null)
const memberId = ref(null)
const isReady = ref(false)
const isLoading = ref(false)

export function useAuth() {
  async function init() {
    if (isReady.value) return
    isLoading.value = true
    try {
      const saved = loadIdentity()
      if (saved?.accountId) {
        try {
          const info = await getMemberInfo(saved.accountId)
          identity.value = { accountId: info.accountId, nickname: info.nickname, avatarColor: info.avatarColor }
          saveIdentity(identity.value)
          isReady.value = true
          return
        } catch { clearIdentity() }
      }
      const info = await autoRegister()
      identity.value = { accountId: info.accountId, nickname: info.nickname, avatarColor: info.avatarColor }
      saveIdentity(identity.value)
      isReady.value = true
    } finally { isLoading.value = false }
  }

  function setMemberId(id) { memberId.value = id }

  return {
    identity: readonly(identity),
    memberId: readonly(memberId),
    isReady: readonly(isReady),
    isLoading: readonly(isLoading),
    init, setMemberId
  }
}
