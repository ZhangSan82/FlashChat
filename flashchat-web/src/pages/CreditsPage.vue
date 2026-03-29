<template>
  <div class="credits">
    <div class="credits-top">
      <button class="credits-back" type="button" @click="router.push('/')">返回聊天</button>
      <div class="credits-title-wrap">
        <div class="credits-kicker">Credits Center</div>
        <h1>积分中心</h1>
        <p>查看余额、完成每日签到，并追踪每一笔积分收入与支出。</p>
      </div>
    </div>

    <section class="credits-hero">
      <div class="credits-balance-card">
        <span>当前积分</span>
        <strong>{{ balance }}</strong>
      </div>
      <div class="credits-checkin-card">
        <div>
          <span>每日签到</span>
          <strong>{{ checkedIn ? '今日已签到' : '领取今日奖励' }}</strong>
          <p>签到成功可获得 10 积分，适合用来续房、创建新房间。</p>
        </div>
        <button class="credits-checkin-btn" type="button" :disabled="checkingIn || checkedIn" @click="doCheckIn">
          {{ checkingIn ? '签到中...' : (checkedIn ? '已签到' : '立即签到') }}
        </button>
      </div>
    </section>

    <transition name="credits-toast">
      <div v-if="toast" class="credits-toast">{{ toast }}</div>
    </transition>

    <section class="credits-list-card">
      <div class="credits-list-head">
        <div>
          <div class="credits-list-kicker">Transactions</div>
          <h2>积分流水</h2>
        </div>
        <div class="credits-list-meta">共展示 {{ transactions.length }} 条</div>
      </div>

      <div v-if="loading && transactions.length === 0" class="credits-empty">正在加载流水...</div>
      <div v-else-if="transactions.length === 0" class="credits-empty">还没有积分流水记录。</div>
      <div v-else class="credits-list">
        <article v-for="item in transactions" :key="item.id" class="credits-row">
          <div class="credits-row-main">
            <div class="credits-row-title">{{ item.typeDesc || item.type || '积分变动' }}</div>
            <div class="credits-row-sub">
              <span>{{ item.remark || '无备注' }}</span>
              <span>·</span>
              <span>{{ formatDateTime(item.createTime) }}</span>
            </div>
          </div>
          <div class="credits-row-amount" :class="{ positive: item.amount > 0, negative: item.amount < 0 }">
            {{ item.amount > 0 ? '+' : '' }}{{ item.amount }}
          </div>
        </article>
      </div>

      <div class="credits-more">
        <button class="credits-back" type="button" :disabled="loading || !hasMore" @click="loadTransactions(false)">
          {{ hasMore ? (loading ? '加载中...' : '加载更多') : '已经到底了' }}
        </button>
      </div>
    </section>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '@/composables/useAuth'
import { dailyCheckIn, getCreditBalance, getCreditTransactions } from '@/api/account'

const router = useRouter()
const auth = useAuth()

const balance = ref(0)
const transactions = ref([])
const page = ref(1)
const size = 20
const hasMore = ref(true)
const loading = ref(false)
const checkingIn = ref(false)
const checkedIn = ref(false)
const toast = ref('')
let toastTimer = null

onMounted(async () => {
  await auth.init()
  checkedIn.value = loadCheckedInStatus()
  await Promise.all([loadBalance(), loadTransactions(true)])
})

async function loadBalance() {
  try {
    balance.value = await getCreditBalance()
  } catch {
    balance.value = 0
  }
}

async function loadTransactions(reset) {
  if (loading.value) return
  loading.value = true

  if (reset) {
    page.value = 1
    hasMore.value = true
  }

  try {
    const list = await getCreditTransactions(page.value, size)
    const next = Array.isArray(list) ? list : []
    transactions.value = reset ? next : [...transactions.value, ...next]
    hasMore.value = next.length === size
    if (hasMore.value) page.value += 1
  } finally {
    loading.value = false
  }
}

async function doCheckIn() {
  if (checkingIn.value || checkedIn.value) return
  checkingIn.value = true
  try {
    const result = await dailyCheckIn()
    checkedIn.value = true
    saveCheckedInStatus()
    showToast(result ? '签到成功，已到账 10 积分' : '今天已经签过到了')
    await Promise.all([loadBalance(), loadTransactions(true)])
  } catch (err) {
    showToast(err?.message || '签到失败')
  } finally {
    checkingIn.value = false
  }
}

function getTodayKey() {
  const d = new Date()
  return `fc_checkin_${d.getFullYear()}_${d.getMonth() + 1}_${d.getDate()}`
}

function loadCheckedInStatus() {
  try {
    return sessionStorage.getItem(getTodayKey()) === '1'
  } catch {
    return false
  }
}

function saveCheckedInStatus() {
  try {
    sessionStorage.setItem(getTodayKey(), '1')
  } catch {}
}

function showToast(message) {
  toast.value = message
  clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => {
    toast.value = ''
  }, 2200)
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
.credits {
  min-height: 100vh;
  padding: 28px;
  background:
    radial-gradient(circle at top right, rgba(173, 122, 68, 0.12), transparent 24%),
    linear-gradient(180deg, #f4ebdd 0%, #e9dccb 100%);
}

.credits-top,
.credits-hero {
  display: grid;
  gap: 18px;
}

.credits-top {
  grid-template-columns: auto 1fr;
  align-items: start;
}

.credits-kicker,
.credits-list-kicker {
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.credits-title-wrap h1 {
  margin: 10px 0 10px;
  font-family: var(--fc-font);
  font-size: clamp(34px, 5vw, 50px);
  line-height: .98;
  color: var(--fc-text);
}

.credits-title-wrap p,
.credits-checkin-card p,
.credits-row-sub,
.credits-empty {
  margin: 0;
  font-family: var(--fc-font);
  font-size: 14px;
  line-height: 1.7;
  color: var(--fc-text-sec);
}

.credits-back,
.credits-checkin-btn {
  padding: 12px 16px;
  border: 1px solid rgba(77, 52, 31, 0.10);
  border-radius: 18px;
  font-family: var(--fc-font);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}

.credits-back {
  background: rgba(255, 250, 243, 0.84);
  color: var(--fc-text-sec);
}

.credits-hero {
  margin-top: 28px;
  grid-template-columns: 300px 1fr;
}

.credits-balance-card,
.credits-checkin-card,
.credits-list-card {
  border: 1px solid rgba(77, 52, 31, 0.10);
  border-radius: 28px;
  background: rgba(255, 250, 243, 0.82);
  box-shadow: var(--fc-shadow-soft);
}

.credits-balance-card,
.credits-checkin-card {
  padding: 24px;
}

.credits-balance-card span,
.credits-checkin-card span {
  display: block;
  font-family: var(--fc-font);
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.14em;
  color: var(--fc-text-muted);
}

.credits-balance-card strong,
.credits-checkin-card strong {
  display: block;
  margin-top: 12px;
  font-family: var(--fc-font);
  font-size: 40px;
  line-height: 1;
  color: var(--fc-text);
}

.credits-checkin-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
}

.credits-checkin-btn {
  min-width: 138px;
  background: linear-gradient(135deg, #b68450 0%, #8c5a2b 100%);
  color: #fffaf3;
  box-shadow: 0 16px 28px rgba(140, 90, 43, 0.22);
}

.credits-checkin-btn:disabled {
  opacity: .45;
  cursor: not-allowed;
  box-shadow: none;
}

.credits-toast {
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

.credits-list-card {
  margin-top: 24px;
  padding: 22px;
}

.credits-list-head {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 14px;
}

.credits-list-head h2 {
  margin: 8px 0 0;
  font-family: var(--fc-font);
  font-size: 24px;
  color: var(--fc-text);
}

.credits-list-meta {
  font-family: var(--fc-font);
  font-size: 13px;
  color: var(--fc-text-sec);
}

.credits-list {
  margin-top: 18px;
}

.credits-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 0;
  border-bottom: 1px solid rgba(77, 52, 31, 0.06);
}

.credits-row:last-child {
  border-bottom: 0;
}

.credits-row-title {
  font-family: var(--fc-font);
  font-size: 16px;
  font-weight: 700;
  color: var(--fc-text);
}

.credits-row-sub {
  margin-top: 6px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.credits-row-amount {
  font-family: var(--fc-font);
  font-size: 22px;
  font-weight: 700;
}

.credits-row-amount.positive { color: var(--fc-success); }
.credits-row-amount.negative { color: var(--fc-danger); }

.credits-more {
  margin-top: 16px;
  display: flex;
  justify-content: center;
}

.credits-empty {
  padding: 30px 0 10px;
  text-align: center;
}

.credits-toast-enter-active,
.credits-toast-leave-active {
  transition: all .24s ease;
}

.credits-toast-enter-from,
.credits-toast-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(-10px);
}

@media (max-width: 860px) {
  .credits-hero {
    grid-template-columns: 1fr;
  }

  .credits-checkin-card {
    flex-direction: column;
    align-items: stretch;
  }
}

@media (max-width: 640px) {
  .credits {
    padding: 18px 16px 28px;
  }

  .credits-top {
    grid-template-columns: 1fr;
  }

  .credits-row {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
