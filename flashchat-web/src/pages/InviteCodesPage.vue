<template>
  <div class="invites">
    <header class="invites-top">
      <button class="invites-back" type="button" @click="router.push('/')">返回聊天</button>
      <div class="invites-title-wrap">
        <div class="invites-kicker">Invite Center</div>
        <h1>邀请码管理</h1>
        <p>查看你当前拥有的邀请码、使用状态，以及被谁使用。</p>
      </div>
    </header>

    <section class="invites-hero">
      <div class="invites-highlight">
        <span>我的邀请码</span>
        <strong>{{ profileCode || '暂无邀请码' }}</strong>
        <button v-if="profileCode" class="invites-copy-btn" type="button" @click="copyText(profileCode, '已复制我的邀请码')">
          复制我的邀请码
        </button>
      </div>

      <div class="invites-stats">
        <div class="invites-stat">
          <span>总数</span>
          <strong>{{ codes.length }}</strong>
        </div>
        <div class="invites-stat">
          <span>未使用</span>
          <strong>{{ unusedCount }}</strong>
        </div>
        <div class="invites-stat">
          <span>已使用</span>
          <strong>{{ usedCount }}</strong>
        </div>
      </div>
    </section>

    <transition name="invites-toast">
      <div v-if="toast" class="invites-toast">{{ toast }}</div>
    </transition>

    <section class="invites-list-card">
      <div class="invites-list-head">
        <div>
          <div class="invites-list-kicker">Codes</div>
          <h2>邀请码列表</h2>
        </div>
      </div>

      <div v-if="loading" class="invites-empty">正在加载邀请码...</div>
      <div v-else-if="codes.length === 0" class="invites-empty">当前账号还没有可展示的邀请码。</div>
      <div v-else class="invites-list">
        <article v-for="code in codes" :key="code.code" class="invites-row">
          <div class="invites-row-main">
            <div class="invites-code">{{ code.code }}</div>
            <div class="invites-row-sub">
              <span>{{ formatDateTime(code.createTime) }}</span>
              <span>·</span>
              <span>{{ code.used ? `已被 ${code.usedByAccountId || '某位用户'} 使用` : '尚未使用' }}</span>
            </div>
          </div>
          <div class="invites-row-side">
            <span class="invites-badge" :class="{ used: code.used }">{{ code.used ? '已使用' : '可使用' }}</span>
            <button class="invites-inline-copy" type="button" @click="copyText(code.code, '邀请码已复制')">复制</button>
          </div>
        </article>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '@/composables/useAuth'
import { getInviteCodes, getMyAccount } from '@/api/account'

const router = useRouter()
const auth = useAuth()

const loading = ref(false)
const codes = ref([])
const profileCode = ref('')
const toast = ref('')
let toastTimer = null

const usedCount = computed(() => codes.value.filter(item => item.used).length)
const unusedCount = computed(() => codes.value.filter(item => !item.used).length)

onMounted(async () => {
  await auth.init()
  await loadData()
})

async function loadData() {
  loading.value = true
  try {
    const [profile, inviteCodes] = await Promise.allSettled([
      getMyAccount(),
      getInviteCodes()
    ])
    profileCode.value = profile.status === 'fulfilled' ? (profile.value?.inviteCode || '') : ''
    codes.value = inviteCodes.status === 'fulfilled' && Array.isArray(inviteCodes.value)
      ? inviteCodes.value
      : []
  } finally {
    loading.value = false
  }
}

async function copyText(value, message) {
  if (!value) return
  try {
    await navigator.clipboard.writeText(value)
  } catch {
    const input = document.createElement('input')
    input.value = value
    document.body.appendChild(input)
    input.select()
    document.execCommand('copy')
    document.body.removeChild(input)
  }
  showToast(message)
}

function showToast(message) {
  toast.value = message
  clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => {
    toast.value = ''
  }, 1800)
}

function formatDateTime(value) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  })
}
</script>

<style scoped>
.invites {
  min-height: 100vh;
  padding: 28px;
  background:
    radial-gradient(circle at top left, rgba(221, 193, 163, 0.40), transparent 26%),
    linear-gradient(180deg, #f4ebdd 0%, #e9dccb 100%);
}

.invites-top,
.invites-hero {
  display: grid;
  gap: 18px;
}

.invites-top {
  grid-template-columns: auto 1fr;
  align-items: start;
}

.invites-back,
.invites-copy-btn,
.invites-inline-copy {
  padding: 12px 16px;
  border: 1px solid rgba(77, 52, 31, 0.10);
  border-radius: 18px;
  font-family: var(--fc-font);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}

.invites-back,
.invites-inline-copy {
  background: rgba(255, 250, 243, 0.84);
  color: var(--fc-text-sec);
}

.invites-kicker,
.invites-list-kicker {
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.invites-title-wrap h1 {
  margin: 10px 0 10px;
  font-family: var(--fc-font);
  font-size: clamp(34px, 5vw, 50px);
  line-height: .98;
  color: var(--fc-text);
}

.invites-title-wrap p,
.invites-row-sub,
.invites-empty {
  margin: 0;
  font-family: var(--fc-font);
  font-size: 14px;
  line-height: 1.7;
  color: var(--fc-text-sec);
}

.invites-hero {
  margin-top: 28px;
  grid-template-columns: 1.2fr .8fr;
}

.invites-highlight,
.invites-stats,
.invites-list-card {
  border: 1px solid rgba(77, 52, 31, 0.10);
  border-radius: 28px;
  background: rgba(255, 250, 243, 0.82);
  box-shadow: var(--fc-shadow-soft);
}

.invites-highlight,
.invites-stats {
  padding: 24px;
}

.invites-highlight span,
.invites-stat span {
  display: block;
  font-family: var(--fc-font);
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.14em;
  color: var(--fc-text-muted);
}

.invites-highlight strong {
  display: block;
  margin-top: 12px;
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-size: 30px;
  color: var(--fc-text);
}

.invites-copy-btn {
  margin-top: 16px;
  background: linear-gradient(135deg, #b68450 0%, #8c5a2b 100%);
  color: #fffaf3;
  box-shadow: 0 16px 28px rgba(140, 90, 43, 0.22);
}

.invites-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.invites-stat {
  padding: 16px;
  border-radius: 20px;
  background: rgba(243, 231, 215, 0.88);
}

.invites-stat strong {
  display: block;
  margin-top: 10px;
  font-family: var(--fc-font);
  font-size: 26px;
  color: var(--fc-text);
}

.invites-toast {
  position: fixed;
  top: 22px;
  left: 50%;
  transform: translateX(-50%);
  padding: 12px 20px;
  border-radius: 999px;
  background: rgba(255, 250, 243, 0.92);
  border: 1px solid rgba(77, 52, 31, 0.10);
  font-family: var(--fc-font);
  color: var(--fc-text);
  box-shadow: var(--fc-shadow-soft);
  z-index: 10000;
}

.invites-list-card {
  margin-top: 24px;
  padding: 22px;
}

.invites-list-head h2 {
  margin: 8px 0 0;
  font-family: var(--fc-font);
  font-size: 24px;
  color: var(--fc-text);
}

.invites-list {
  margin-top: 18px;
}

.invites-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 0;
  border-bottom: 1px solid rgba(77, 52, 31, 0.06);
}

.invites-row:last-child {
  border-bottom: 0;
}

.invites-code {
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-size: 18px;
  font-weight: 700;
  color: var(--fc-text);
}

.invites-row-sub {
  margin-top: 6px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.invites-row-side {
  display: flex;
  align-items: center;
  gap: 10px;
}

.invites-badge {
  padding: 6px 12px;
  border-radius: 999px;
  background: rgba(235, 245, 230, 0.96);
  color: #42673f;
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 700;
}

.invites-badge.used {
  background: rgba(233, 225, 217, 0.96);
  color: #7d6c5c;
}

.invites-empty {
  padding: 30px 0 10px;
  text-align: center;
}

.invites-toast-enter-active,
.invites-toast-leave-active {
  transition: all .24s ease;
}

.invites-toast-enter-from,
.invites-toast-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(-10px);
}

@media (max-width: 860px) {
  .invites-hero {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .invites {
    padding: 18px 16px 28px;
  }

  .invites-top {
    grid-template-columns: 1fr;
  }

  .invites-stats {
    grid-template-columns: 1fr;
  }

  .invites-row {
    align-items: flex-start;
    flex-direction: column;
  }

  .invites-row-side {
    width: 100%;
    justify-content: space-between;
  }
}
</style>
