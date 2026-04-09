<template>
  <div class="credits-page">

    <header class="credits-top">
      <button class="credits-back" type="button" @click="goRoomList">返回房间列表</button>
      <div class="credits-top-copy">
        <div class="credits-kicker">Credits Center</div>
        <h1>积分中心</h1>
        <p>查看余额、每日签到以及最近的积分流水。</p>
      </div>
      <button class="credits-sign" type="button" :disabled="checkingIn || checkedIn" @click="handleCheckIn">
        {{ checkedIn ? '今日已签到' : (checkingIn ? '签到中...' : '每日签到 +10') }}
      </button>
    </header>

    <div v-if="notice" class="credits-notice" :class="`is-${notice.type}`">{{ notice.text }}</div>

    <section class="credits-hero">
      <div class="credits-balance-card">
        <div class="credits-balance-label">当前余额</div>
        <div class="credits-balance-value">{{ balanceDisplay }}</div>
        <div class="credits-balance-meta">
          <span>{{ account?.nickname || '游客' }}</span>
          <span v-if="account?.inviteCode">邀请码 {{ account.inviteCode }}</span>
        </div>
      </div>

      <div class="credits-summary-grid">
        <div class="credits-summary-card">
          <span>今日签到状态</span>
          <strong>{{ checkedIn ? '已完成' : '待签到' }}</strong>
        </div>
        <div class="credits-summary-card">
          <span>流水条数</span>
          <strong>{{ transactions.length }}</strong>
        </div>
        <div class="credits-summary-card">
          <span>账号身份</span>
          <strong>{{ account?.isRegistered ? '注册用户' : '匿名成员' }}</strong>
        </div>
      </div>
    </section>

    <section class="credits-ledger">
      <div class="credits-ledger-head">
        <div>
          <div class="credits-kicker">Ledger</div>
          <h2>积分流水</h2>
        </div>
        <button class="credits-refresh" type="button" :disabled="loading" @click="loadAll(true)">刷新</button>
      </div>

      <div v-if="loading && transactions.length === 0" class="credits-state">
        <div class="credits-spinner"></div>
        <p>正在加载积分流水...</p>
      </div>

      <div v-else-if="transactions.length === 0" class="credits-state">
        <p>还没有积分流水记录。</p>
      </div>

      <div v-else class="credits-list">
        <article v-for="item in transactions" :key="item.id" class="credits-item">
          <div class="credits-item-main">
            <div class="credits-item-title">{{ item.typeDesc || '积分变动' }}</div>
            <div class="credits-item-remark">{{ item.remark || '系统记录' }}</div>
          </div>
          <div class="credits-item-side">
            <div class="credits-item-amount" :class="{ positive: Number(item.amount) > 0, negative: Number(item.amount) < 0 }">
              {{ Number(item.amount) > 0 ? `+${item.amount}` : `${item.amount}` }}
            </div>
            <div class="credits-item-time">{{ formatDateTime(item.createTime) }}</div>
          </div>
        </article>
      </div>

      <div class="credits-more" v-if="transactions.length > 0">
        <button class="credits-refresh" type="button" :disabled="loading || !hasMore" @click="loadTransactions(false)">
          {{ hasMore ? (loading ? '加载中...' : '加载更多') : '已经到底了' }}
        </button>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '@/composables/useAuth'
import { dailyCheckIn, getCreditBalance, getCreditTransactions, getMyAccount } from '@/api/account'

const router = useRouter()
const auth = useAuth()

const account = ref(null)
const balance = ref(null)
const transactions = ref([])
const loading = ref(false)
const checkingIn = ref(false)
const checkedIn = ref(false)
const page = ref(1)
const size = 20
const hasMore = ref(true)
const notice = ref(null)

const balanceDisplay = computed(() => (balance.value == null ? '—' : `${balance.value}`))

onMounted(async () => {
  loadCheckedInStatus()
  await auth.init()
  await loadAll(true)
})

async function loadAll(reset = true) {
  loading.value = true
  try {
    const [accountResp, balanceResp] = await Promise.all([
      getMyAccount(),
      getCreditBalance()
    ])
    account.value = accountResp
    balance.value = balanceResp
    await loadTransactions(reset)
  } catch (error) {
    showNotice(error?.message || '积分中心加载失败', 'error')
  } finally {
    loading.value = false
  }
}

async function loadTransactions(reset) {
  if (loading.value && !reset) return

  if (reset) {
    page.value = 1
    hasMore.value = true
  } else {
    loading.value = true
  }

  try {
    const list = await getCreditTransactions(page.value, size)
    const rows = Array.isArray(list) ? list : []
    transactions.value = reset ? rows : [...transactions.value, ...rows]
    hasMore.value = rows.length === size
    if (hasMore.value) page.value += 1
  } catch (error) {
    showNotice(error?.message || '积分流水加载失败', 'error')
  } finally {
    if (!reset) {
      loading.value = false
    }
  }
}

async function handleCheckIn() {
  if (checkedIn.value || checkingIn.value) return
  checkingIn.value = true
  try {
    const result = await dailyCheckIn()
    if (result === true) {
      markCheckedIn()
      showNotice('签到成功，已获得 10 积分', 'success')
      await loadAll(true)
    } else {
      markCheckedIn()
      showNotice('今天已经签到过了', 'info')
    }
  } catch (error) {
    showNotice(error?.message || '签到失败', 'error')
  } finally {
    checkingIn.value = false
  }
}

function getTodayKey() {
  const date = new Date()
  return `fc_checkin_${date.getFullYear()}_${date.getMonth() + 1}_${date.getDate()}`
}

function loadCheckedInStatus() {
  try {
    checkedIn.value = sessionStorage.getItem(getTodayKey()) === '1'
  } catch {
    checkedIn.value = false
  }
}

function markCheckedIn() {
  checkedIn.value = true
  try {
    sessionStorage.setItem(getTodayKey(), '1')
  } catch {}
}

function goRoomList() {
  router.push({ name: 'Chat', query: { view: 'rooms' } })
}

function showNotice(text, type = 'info') {
  notice.value = { text, type }
  window.clearTimeout(showNotice.timer)
  showNotice.timer = window.setTimeout(() => {
    notice.value = null
  }, 2600)
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
.credits-page {
  min-height: 100vh;
  padding: 40px;
  background: var(--fc-bg);
  max-width: 1200px;
  margin: 0 auto;
}

.credits-top {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 18px;
  align-items: start;
}

.credits-back,
.credits-sign,
.credits-refresh {
  border: 1px solid var(--fc-border);
  border-radius: 999px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all .2s ease;
}

.credits-back,
.credits-refresh {
  padding: 10px 16px;
  background: var(--fc-surface);
  color: var(--fc-text);
}

.credits-back:hover,
.credits-refresh:hover {
  border-color: var(--fc-border-strong);
}

.credits-sign {
  padding: 10px 18px;
  background: var(--fc-accent);
  border-color: transparent;
  color: #fff;
}

.credits-sign:hover {
  background: var(--fc-accent-strong);
}

.credits-sign:disabled,
.credits-refresh:disabled {
  opacity: .55;
  cursor: not-allowed;
}

.credits-kicker {
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--fc-accent);
}

.credits-top-copy h1 {
  margin: 12px 0 10px;
  font-family: var(--fc-font-display);
  font-size: clamp(32px, 4vw, 48px);
  line-height: 1.1;
  font-weight: 600;
  letter-spacing: -0.015em;
  color: var(--fc-text);
}

.credits-ledger-head h2 {
  margin: 10px 0 10px;
  font-family: var(--fc-font-display);
  font-size: 22px;
  font-weight: 600;
  color: var(--fc-text);
}

.credits-top-copy p,
.credits-state p {
  max-width: 640px;
  margin: 0;
  font-size: 16px;
  line-height: 1.6;
  color: var(--fc-text-muted);
}

.credits-notice {
  margin-top: 20px;
  padding: 12px 18px;
  border-radius: 12px;
  width: fit-content;
  border: 1px solid var(--fc-border);
  font-size: 14px;
  font-weight: 500;
}

.credits-notice.is-success { background: var(--fc-surface); color: var(--fc-success); }
.credits-notice.is-error { background: var(--fc-surface); color: var(--fc-danger); }
.credits-notice.is-info { background: var(--fc-surface); color: var(--fc-text); }

.credits-hero {
  margin-top: 32px;
  display: grid;
  grid-template-columns: 1.2fr 1fr;
  gap: 16px;
}

.credits-balance-card,
.credits-summary-card,
.credits-ledger,
.credits-state,
.credits-item {
  border: 1px solid var(--fc-border);
  border-radius: var(--fc-radius-lg);
  background: var(--fc-surface);
}

.credits-balance-card {
  padding: 28px;
}

.credits-balance-label {
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.credits-balance-value {
  margin-top: 18px;
  font-family: var(--fc-font-display);
  font-size: clamp(42px, 8vw, 64px);
  font-weight: 600;
  line-height: 0.95;
  color: var(--fc-text);
}

.credits-balance-meta {
  margin-top: 18px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 13px;
  color: var(--fc-text-sec);
}

.credits-balance-meta span {
  padding: 6px 12px;
  border-radius: var(--fc-radius-pill);
  border: 1px solid var(--fc-border);
  background: var(--fc-bg);
  color: var(--fc-accent);
}

.credits-summary-grid {
  display: grid;
  gap: 16px;
}

.credits-summary-card {
  padding: 20px;
}

.credits-summary-card span {
  display: block;
  font-size: 12px;
  color: var(--fc-text-muted);
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.credits-summary-card strong {
  display: block;
  margin-top: 12px;
  font-family: var(--fc-font-display);
  font-size: 22px;
  font-weight: 600;
  line-height: 1.05;
  color: var(--fc-text);
}

.credits-ledger {
  margin-top: 24px;
  padding: 24px;
}

.credits-ledger-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
}

.credits-state {
  margin-top: 18px;
  padding: 30px;
  text-align: center;
}

.credits-spinner {
  width: 32px;
  height: 32px;
  margin: 0 auto 16px;
  border: 2px solid var(--fc-border);
  border-top-color: var(--fc-accent);
  border-radius: 50%;
  animation: credits-spin .7s linear infinite;
}

@keyframes credits-spin {
  to { transform: rotate(360deg); }
}

.credits-list {
  margin-top: 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.credits-item {
  padding: 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.credits-item-main {
  min-width: 0;
  flex: 1;
}

.credits-item-title {
  font-family: var(--fc-font-display);
  font-size: 16px;
  line-height: 1.2;
  font-weight: 600;
  color: var(--fc-text);
}

.credits-item-remark,
.credits-item-time {
  margin-top: 6px;
  font-size: 13px;
  color: var(--fc-text-muted);
}

.credits-item-side {
  text-align: right;
}

.credits-item-amount {
  font-family: var(--fc-font-display);
  font-size: 20px;
  line-height: 1.05;
  font-weight: 600;
  color: var(--fc-text);
}

.credits-item-amount.positive { color: var(--fc-success); }
.credits-item-amount.negative { color: var(--fc-danger); }

.credits-more {
  margin-top: 18px;
  display: flex;
  justify-content: center;
}

@media (max-width: 960px) {
  .credits-hero { grid-template-columns: 1fr; }
}

@media (max-width: 720px) {
  .credits-page { padding: 24px 16px 32px; }
  .credits-top { grid-template-columns: 1fr; }
  .credits-item,
  .credits-ledger-head {
    flex-direction: column;
    align-items: flex-start;
  }
  .credits-item-side { text-align: left; }
}
</style>
