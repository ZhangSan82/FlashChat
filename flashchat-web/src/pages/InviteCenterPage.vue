<template>
  <div class="invites-page">
    <div class="invites-orb invites-orb-a"></div>
    <div class="invites-orb invites-orb-b"></div>

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
  padding: 32px;
  position: relative;
  overflow-x: hidden;
  background: var(--fc-app-gradient);
}

.invites-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(22px);
  pointer-events: none;
}

.invites-orb-a {
  width: 260px;
  height: 260px;
  top: -110px;
  right: -80px;
  background: rgba(182, 118, 57, 0.14);
}

.invites-orb-b {
  width: 240px;
  height: 240px;
  bottom: -110px;
  left: -80px;
  background: rgba(224, 194, 161, 0.22);
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

.invites-hero,
.invites-list-card,
.invites-main-card,
.invites-stat-card,
.invites-item,
.invites-state,
.invites-notice {
  position: relative;
  overflow: hidden;
}

.invites-hero::before,
.invites-list-card::before,
.invites-main-card::before,
.invites-stat-card::before,
.invites-item::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    linear-gradient(135deg, rgba(255,255,255,0.14), transparent 44%),
    radial-gradient(circle at top right, rgba(182,118,57,0.08), transparent 32%);
  pointer-events: none;
}

.invites-back,
.invites-copy-main,
.invites-copy-btn,
.invites-refresh {
  border: 1px solid var(--fc-border);
  border-radius: 18px;
  font-family: var(--fc-font);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: transform .2s ease, filter .2s ease, box-shadow .2s ease;
}

.invites-back,
.invites-refresh,
.invites-copy-btn {
  padding: 12px 16px;
  background: rgba(255, 250, 243, 0.88);
  color: var(--fc-text-sec);
}

.invites-copy-main {
  padding: 12px 18px;
  background: linear-gradient(135deg, #bd7b3c 0%, #8a4e22 100%);
  color: #fffaf3;
  box-shadow: 0 18px 30px rgba(138, 78, 34, 0.22);
}

.invites-back:hover,
.invites-copy-main:hover,
.invites-copy-btn:hover,
.invites-refresh:hover {
  transform: translateY(-1px);
  filter: brightness(1.03);
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
  font-family: var(--fc-font-display);
  font-size: clamp(40px, 5vw, 58px);
  line-height: 0.94;
  color: var(--fc-text);
}

.invites-list-head h2 {
  font-size: 34px;
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
  border: 1px solid var(--fc-border);
  font-family: var(--fc-font);
  font-size: 14px;
  color: var(--fc-text);
  box-shadow: var(--fc-shadow-soft);
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
  border: 1px solid var(--fc-border);
  border-radius: 28px;
  background: var(--fc-panel);
  box-shadow: var(--fc-shadow-soft);
}

.invites-main-card {
  padding: 26px;
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
  font-family: var(--fc-font-mono);
  font-size: clamp(32px, 6vw, 52px);
  line-height: 0.96;
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
  font-family: var(--fc-font-display);
  font-size: 30px;
  line-height: 0.96;
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
  font-family: var(--fc-font-mono);
  font-size: 26px;
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
    padding: 20px 16px 28px;
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
