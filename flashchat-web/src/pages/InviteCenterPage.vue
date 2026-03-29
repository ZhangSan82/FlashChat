<template>
  <div class="invites-page">
    <div class="invites-orb invites-orb-a"></div>
    <div class="invites-orb invites-orb-b"></div>

    <header class="invites-top">
      <button class="invites-back" type="button" @click="router.push('/')">返回聊天</button>
      <div class="invites-top-copy">
        <div class="invites-kicker">Invite Center</div>
        <h1>邀请码管理</h1>
        <p>查看你的主邀请码和所有可用邀请码，支持一键复制。</p>
      </div>
    </header>

    <div v-if="notice" class="invites-notice">{{ notice }}</div>

    <section class="invites-hero">
      <div class="invites-main-card">
        <div class="invites-main-label">我的主邀请码</div>
        <div class="invites-main-code">{{ account?.inviteCode || '—' }}</div>
        <button class="invites-copy-main" type="button" :disabled="!account?.inviteCode" @click="copyText(account?.inviteCode)">
          复制主邀请码
        </button>
      </div>

      <div class="invites-stats">
        <div class="invites-stat-card">
          <span>总数</span>
          <strong>{{ codes.length }}</strong>
        </div>
        <div class="invites-stat-card">
          <span>未使用</span>
          <strong>{{ unusedCount }}</strong>
        </div>
        <div class="invites-stat-card">
          <span>已使用</span>
          <strong>{{ usedCount }}</strong>
        </div>
      </div>
    </section>

    <section class="invites-list-card">
      <div class="invites-list-head">
        <div>
          <div class="invites-kicker">Inventory</div>
          <h2>邀请码列表</h2>
        </div>
        <button class="invites-refresh" type="button" :disabled="loading" @click="loadPage">
          {{ loading ? '刷新中...' : '刷新' }}
        </button>
      </div>

      <div v-if="loading && codes.length === 0" class="invites-state">
        <div class="invites-spinner"></div>
        <p>正在加载邀请码...</p>
      </div>

      <div v-else-if="codes.length === 0" class="invites-state">
        <p>你当前还没有可展示的邀请码。</p>
      </div>

      <div v-else class="invites-list">
        <article v-for="item in sortedCodes" :key="item.code" class="invites-item">
          <div class="invites-item-main">
            <div class="invites-item-code">{{ item.code }}</div>
            <div class="invites-item-meta">
              <span>{{ item.used ? '已使用' : '未使用' }}</span>
              <span>创建于 {{ formatDateTime(item.createTime) }}</span>
              <span v-if="item.usedByAccountId">使用者 {{ item.usedByAccountId }}</span>
            </div>
          </div>
          <div class="invites-item-side">
            <span class="invites-badge" :class="{ used: item.used, idle: !item.used }">
              {{ item.used ? '已使用' : '可用' }}
            </span>
            <button class="invites-copy-btn" type="button" @click="copyText(item.code)">
              复制
            </button>
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

const account = ref(null)
const codes = ref([])
const loading = ref(false)
const notice = ref('')

const sortedCodes = computed(() =>
  [...codes.value].sort((a, b) => Number(a.used) - Number(b.used) || String(a.code).localeCompare(String(b.code)))
)
const usedCount = computed(() => codes.value.filter(item => item.used).length)
const unusedCount = computed(() => codes.value.filter(item => !item.used).length)

onMounted(async () => {
  await auth.init()
  await loadPage()
})

async function loadPage() {
  loading.value = true
  try {
    const [accountResp, codesResp] = await Promise.all([
      getMyAccount(),
      getInviteCodes()
    ])
    account.value = accountResp
    codes.value = Array.isArray(codesResp) ? codesResp : []
  } catch (error) {
    showNotice(error?.message || '邀请码加载失败')
  } finally {
    loading.value = false
  }
}

async function copyText(value) {
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
  showNotice('已复制到剪贴板')
}

function showNotice(text) {
  notice.value = text
  window.clearTimeout(showNotice.timer)
  showNotice.timer = window.setTimeout(() => {
    notice.value = ''
  }, 2200)
}
showNotice.timer = null

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
.invites-page {
  min-height: 100vh;
  padding: 28px;
  position: relative;
  overflow: hidden;
  background:
    radial-gradient(circle at 0% 0%, rgba(221, 193, 163, 0.46), transparent 28%),
    radial-gradient(circle at 100% 10%, rgba(173, 122, 68, 0.14), transparent 20%),
    linear-gradient(180deg, #f4ebdd 0%, #e9dccb 100%);
}

.invites-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(18px);
  pointer-events: none;
}

.invites-orb-a {
  width: 240px;
  height: 240px;
  top: -90px;
  right: -80px;
  background: rgba(173, 122, 68, 0.14);
}

.invites-orb-b {
  width: 220px;
  height: 220px;
  bottom: -110px;
  left: -80px;
  background: rgba(221, 193, 163, 0.26);
}

.invites-top,
.invites-hero,
.invites-list-card,
.invites-notice {
  position: relative;
  z-index: 1;
}

.invites-top {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 18px;
  align-items: start;
}

.invites-back,
.invites-copy-main,
.invites-copy-btn,
.invites-refresh {
  border: 1px solid rgba(77, 52, 31, 0.10);
  border-radius: 18px;
  font-family: var(--fc-font);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}

.invites-back,
.invites-refresh,
.invites-copy-btn {
  padding: 12px 16px;
  background: rgba(255, 250, 243, 0.84);
  color: var(--fc-text-sec);
}

.invites-copy-main {
  padding: 12px 18px;
  background: linear-gradient(135deg, #b68450 0%, #8c5a2b 100%);
  color: #fffaf3;
  box-shadow: 0 16px 28px rgba(140, 90, 43, 0.22);
}

.invites-kicker {
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.invites-top-copy h1,
.invites-list-head h2 {
  margin: 10px 0 10px;
  font-family: var(--fc-font);
  font-size: clamp(34px, 5vw, 52px);
  line-height: 0.98;
  color: var(--fc-text);
}

.invites-list-head h2 {
  font-size: 30px;
}

.invites-top-copy p,
.invites-state p {
  max-width: 640px;
  margin: 0;
  font-family: var(--fc-font);
  font-size: 15px;
  line-height: 1.7;
  color: var(--fc-text-sec);
}

.invites-notice {
  margin-top: 20px;
  padding: 12px 18px;
  width: fit-content;
  border-radius: 18px;
  background: rgba(255, 250, 243, 0.92);
  border: 1px solid rgba(77, 52, 31, 0.10);
  font-family: var(--fc-font);
  font-size: 14px;
  color: var(--fc-text);
}

.invites-hero {
  margin-top: 24px;
  display: grid;
  grid-template-columns: 1.2fr 1fr;
  gap: 18px;
}

.invites-main-card,
.invites-stat-card,
.invites-list-card,
.invites-item,
.invites-state {
  border: 1px solid rgba(77, 52, 31, 0.10);
  border-radius: 28px;
  background: rgba(255, 250, 243, 0.82);
  box-shadow: var(--fc-shadow-soft);
}

.invites-main-card {
  padding: 24px;
}

.invites-main-label {
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.invites-main-code {
  margin-top: 18px;
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-size: clamp(28px, 6vw, 46px);
  color: var(--fc-text);
  word-break: break-all;
}

.invites-copy-main {
  margin-top: 18px;
}

.invites-stats {
  display: grid;
  gap: 18px;
}

.invites-stat-card {
  padding: 20px;
}

.invites-stat-card span {
  display: block;
  font-family: var(--fc-font);
  font-size: 12px;
  color: var(--fc-text-muted);
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.invites-stat-card strong {
  display: block;
  margin-top: 12px;
  font-family: var(--fc-font);
  font-size: 26px;
  color: var(--fc-text);
}

.invites-list-card {
  margin-top: 22px;
  padding: 24px;
}

.invites-list-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
}

.invites-state {
  margin-top: 18px;
  padding: 30px;
  text-align: center;
}

.invites-spinner {
  width: 34px;
  height: 34px;
  margin: 0 auto 16px;
  border: 3px solid rgba(77, 52, 31, 0.10);
  border-top-color: var(--fc-accent);
  border-radius: 50%;
  animation: invites-spin .7s linear infinite;
}

@keyframes invites-spin {
  to { transform: rotate(360deg); }
}

.invites-list {
  margin-top: 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.invites-item {
  padding: 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.invites-item-main {
  min-width: 0;
  flex: 1;
}

.invites-item-code {
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-size: 24px;
  color: var(--fc-text);
}

.invites-item-meta {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px 12px;
  font-family: var(--fc-font);
  font-size: 13px;
  color: var(--fc-text-sec);
}

.invites-item-side {
  display: flex;
  align-items: center;
  gap: 10px;
}

.invites-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 6px 12px;
  border-radius: 999px;
  font-family: var(--fc-font);
  font-size: 12px;
  font-weight: 700;
}

.invites-badge.idle {
  background: rgba(235, 245, 230, 0.96);
  color: #42673f;
}

.invites-badge.used {
  background: rgba(233, 225, 217, 0.96);
  color: #7d6c5c;
}

@media (max-width: 960px) {
  .invites-hero {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .invites-page {
    padding: 18px 16px 26px;
  }

  .invites-top,
  .invites-list-head,
  .invites-item {
    grid-template-columns: 1fr;
    flex-direction: column;
    align-items: flex-start;
  }

  .invites-item-side {
    width: 100%;
    justify-content: space-between;
  }
}
</style>
