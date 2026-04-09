<template>
  <div class="invites-page">

    <header class="invites-top">
      <button class="invites-back" type="button" @click="goRoomList">返回房间列表</button>
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

function goRoomList() {
  router.push({ name: 'Chat', query: { view: 'rooms' } })
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
.invites-page {
  min-height: 100vh;
  padding: 40px;
  background: var(--fc-bg);
  max-width: 1200px;
  margin: 0 auto;
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
  border: 1px solid var(--fc-border);
  border-radius: 999px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all .2s ease;
}

.invites-back,
.invites-refresh,
.invites-copy-btn {
  padding: 10px 16px;
  background: var(--fc-surface);
  color: var(--fc-text);
}

.invites-back:hover,
.invites-refresh:hover,
.invites-copy-btn:hover {
  border-color: var(--fc-border-strong);
}

.invites-copy-main {
  padding: 10px 18px;
  background: var(--fc-accent);
  border-color: transparent;
  color: #fff;
}

.invites-copy-main:hover {
  background: var(--fc-accent-strong);
}

.invites-kicker {
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--fc-accent);
}

.invites-top-copy h1 {
  margin: 12px 0 10px;
  font-family: var(--fc-font-display);
  font-size: clamp(32px, 4vw, 48px);
  line-height: 1.1;
  font-weight: 600;
  letter-spacing: -0.015em;
  color: var(--fc-text);
}

.invites-list-head h2 {
  margin: 10px 0 10px;
  font-family: var(--fc-font-display);
  font-size: 22px;
  font-weight: 600;
  color: var(--fc-text);
}

.invites-top-copy p,
.invites-state p {
  max-width: 640px;
  margin: 0;
  font-size: 16px;
  line-height: 1.6;
  color: var(--fc-text-muted);
}

.invites-notice {
  margin-top: 20px;
  padding: 12px 18px;
  width: fit-content;
  border-radius: 12px;
  background: var(--fc-surface);
  border: 1px solid var(--fc-border);
  font-size: 14px;
  color: var(--fc-text);
}

.invites-hero {
  margin-top: 32px;
  display: grid;
  grid-template-columns: 1.2fr 1fr;
  gap: 16px;
}

.invites-main-card,
.invites-stat-card,
.invites-list-card,
.invites-item,
.invites-state {
  border: 1px solid var(--fc-border);
  border-radius: var(--fc-radius-lg);
  background: var(--fc-surface);
}

.invites-main-card {
  padding: 28px;
}

.invites-main-label {
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.invites-main-code {
  margin-top: 18px;
  font-family: var(--fc-font-mono);
  font-size: clamp(24px, 5vw, 38px);
  line-height: 1.02;
  color: var(--fc-text);
  word-break: break-all;
}

.invites-copy-main {
  margin-top: 18px;
}

.invites-stats {
  display: grid;
  gap: 16px;
}

.invites-stat-card {
  padding: 20px;
}

.invites-stat-card span {
  display: block;
  font-size: 12px;
  color: var(--fc-text-muted);
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.invites-stat-card strong {
  display: block;
  margin-top: 12px;
  font-family: var(--fc-font-display);
  font-size: 22px;
  font-weight: 600;
  line-height: 1.05;
  color: var(--fc-text);
}

.invites-list-card {
  margin-top: 24px;
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
  width: 32px;
  height: 32px;
  margin: 0 auto 16px;
  border: 2px solid var(--fc-border);
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
  font-family: var(--fc-font-mono);
  font-size: 16px;
  color: var(--fc-text);
}

.invites-item-meta {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px 12px;
  font-size: 13px;
  color: var(--fc-text-muted);
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
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
}

.invites-badge.idle {
  background: rgba(82, 122, 77, 0.10);
  color: var(--fc-success);
}

.invites-badge.used {
  background: var(--fc-bg-dark);
  color: var(--fc-text-muted);
}

@media (max-width: 960px) {
  .invites-hero { grid-template-columns: 1fr; }
}

@media (max-width: 720px) {
  .invites-page { padding: 24px 16px 32px; }
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
