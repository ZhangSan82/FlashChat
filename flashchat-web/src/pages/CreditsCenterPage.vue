<template>
  <div class="credits-page">
    <div class="credits-orb credits-orb-a"></div>
    <div class="credits-orb credits-orb-b"></div>

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
  padding: 32px;
  position: relative;
  overflow-x: hidden;
  background: var(--fc-app-gradient);
}

.credits-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(22px);
  pointer-events: none;
}

.credits-orb-a {
  width: 260px;
  height: 260px;
  top: -110px;
  right: -80px;
  background: rgba(182, 118, 57, 0.14);
}

.credits-orb-b {
  width: 240px;
  height: 240px;
  bottom: -110px;
  left: -80px;
  background: rgba(224, 194, 161, 0.22);
}

.credits-top,
.credits-hero,
.credits-ledger,
.credits-notice {
  position: relative;
  z-index: 1;
}

.credits-top {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 18px;
  align-items: start;
}

.credits-hero,
.credits-ledger,
.credits-notice,
.credits-balance-card,
.credits-summary-card,
.credits-state,
.credits-item {
  position: relative;
  overflow: hidden;
}

.credits-hero::before,
.credits-ledger::before,
.credits-balance-card::before,
.credits-summary-card::before,
.credits-item::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.14), transparent 44%),
    radial-gradient(circle at top right, rgba(182, 118, 57, 0.08), transparent 32%);
  pointer-events: none;
}

.credits-back,
.credits-sign,
.credits-refresh {
  border: 1px solid var(--fc-border);
  border-radius: 18px;
  font-family: var(--fc-font);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: transform .2s ease, filter .2s ease, box-shadow .2s ease;
}

.credits-back,
.credits-refresh {
  padding: 12px 16px;
  background: rgba(255, 250, 243, 0.88);
  color: var(--fc-text-sec);
}

.credits-sign {
  padding: 12px 18px;
  background: linear-gradient(135deg, #bd7b3c 0%, #8a4e22 100%);
  color: #fffaf3;
  box-shadow: 0 18px 30px rgba(138, 78, 34, 0.22);
}

.credits-sign:disabled,
.credits-refresh:disabled {
  opacity: .55;
  cursor: not-allowed;
  box-shadow: none;
}

.credits-back:hover,
.credits-sign:hover,
.credits-refresh:hover {
  transform: translateY(-1px);
  filter: brightness(1.03);
}

.credits-kicker {
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.credits-top-copy h1,
.credits-ledger-head h2 {
  margin: 10px 0 10px;
  font-family: var(--fc-font-display);
  font-size: clamp(40px, 5vw, 58px);
  line-height: 0.94;
  color: var(--fc-text);
}

.credits-ledger-head h2 {
  font-size: 34px;
}

.credits-top-copy p,
.credits-state p {
  max-width: 640px;
  margin: 0;
  font-family: var(--fc-font);
  font-size: 15px;
  line-height: 1.7;
  color: var(--fc-text-sec);
}

.credits-notice {
  margin-top: 20px;
  padding: 12px 18px;
  border-radius: 18px;
  width: fit-content;
  border: 1px solid var(--fc-border);
  font-family: var(--fc-font);
  font-size: 14px;
  font-weight: 500;
  box-shadow: var(--fc-shadow-soft);
}

.credits-notice.is-success { background: rgba(235, 245, 230, 0.96); color: #42673f; }
.credits-notice.is-error { background: rgba(253, 236, 234, 0.96); color: #8b3a35; }
.credits-notice.is-info { background: rgba(255, 250, 243, 0.92); color: var(--fc-text); }

.credits-hero {
  margin-top: 24px;
  display: grid;
  grid-template-columns: 1.2fr 1fr;
  gap: 18px;
}

.credits-balance-card,
.credits-summary-card,
.credits-ledger,
.credits-state,
.credits-item {
  border: 1px solid var(--fc-border);
  border-radius: 28px;
  background: var(--fc-panel);
  box-shadow: var(--fc-shadow-soft);
}

.credits-balance-card {
  padding: 26px;
}

.credits-balance-label {
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.credits-balance-value {
  margin-top: 18px;
  font-family: var(--fc-font-display);
  font-size: clamp(62px, 10vw, 94px);
  line-height: 0.9;
  color: var(--fc-text);
}

.credits-balance-meta {
  margin-top: 18px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  font-family: var(--fc-font);
  font-size: 14px;
  color: var(--fc-text-sec);
}

.credits-balance-meta span {
  padding: 7px 12px;
  border-radius: var(--fc-radius-pill);
  border: 1px solid rgba(72, 49, 28, 0.08);
  background: rgba(255, 250, 243, 0.82);
  color: var(--fc-accent-strong);
}

.credits-summary-grid {
  display: grid;
  gap: 18px;
}

.credits-summary-card {
  padding: 20px;
}

.credits-summary-card span {
  display: block;
  font-family: var(--fc-font);
  font-size: 12px;
  color: var(--fc-text-muted);
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.credits-summary-card strong {
  display: block;
  margin-top: 12px;
  font-family: var(--fc-font-display);
  font-size: 30px;
  line-height: 0.96;
  color: var(--fc-text);
}

.credits-ledger {
  margin-top: 22px;
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
  width: 34px;
  height: 34px;
  margin: 0 auto 16px;
  border: 3px solid rgba(77, 52, 31, 0.10);
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
  font-size: 26px;
  line-height: 0.98;
  font-weight: 700;
  color: var(--fc-text);
}

.credits-item-remark,
.credits-item-time {
  margin-top: 6px;
  font-family: var(--fc-font);
  font-size: 13px;
  color: var(--fc-text-sec);
}

.credits-item-side {
  text-align: right;
}

.credits-item-amount {
  font-family: var(--fc-font-display);
  font-size: 30px;
  line-height: 0.98;
  font-weight: 700;
  color: var(--fc-text);
}

.credits-item-amount.positive {
  color: var(--fc-success);
}

.credits-item-amount.negative {
  color: var(--fc-danger);
}

.credits-more {
  margin-top: 18px;
  display: flex;
  justify-content: center;
}

@media (max-width: 960px) {
  .credits-hero {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .credits-page {
    padding: 20px 16px 28px;
  }

  .credits-top {
    grid-template-columns: 1fr;
  }

  .credits-item,
  .credits-ledger-head {
    flex-direction: column;
    align-items: flex-start;
  }

  .credits-item-side {
    text-align: left;
  }
}
</style>
