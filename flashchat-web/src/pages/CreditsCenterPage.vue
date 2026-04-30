<template>
  <div class="credits-page">
    <PageNoticeToast :notice="notice" />

    <div class="credits-shell">
      <header class="credits-top">
        <div class="credits-toolbar">
          <button class="credits-back" type="button" @click="goRoomList">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M15 6l-6 6 6 6" />
            </svg>
            <span>返回房间列表</span>
          </button>

        </div>

        <div class="credits-overview">
          <section class="credits-main-card">
            <div class="fc-kicker">Credits</div>
            <h1>积分中心</h1>
            <p>当前余额与积分流水</p>

            <div class="credits-balance-panel">
              <div>
                <div class="fc-section-label">当前余额</div>
                <div class="credits-balance-value">{{ balanceDisplay }}</div>
              </div>
              <span class="credits-status" :class="{ ready: checkedIn }">
                {{ checkedIn ? '今日已签到' : '待签到' }}
              </span>
            </div>

            <div class="credits-meta">
              <span>{{ account?.nickname || '游客' }}</span>
              <span>{{ account?.isRegistered ? '注册用户' : '匿名成员' }}</span>
            </div>

            <div class="credits-actions">
              <button class="credits-sign" type="button" :disabled="checkingIn || checkedIn" @click="handleCheckIn">
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M12 3l2.7 5.47 6.03.88-4.36 4.25 1.03 6-5.4-2.84-5.4 2.84 1.03-6L3.27 9.35l6.03-.88L12 3z" />
                </svg>
                <span>{{ checkedIn ? '今日已签到' : (checkingIn ? '签到中...' : '签到 +50') }}</span>
              </button>

              <button class="credits-refresh" type="button" :disabled="loading" @click="loadAll(true)">
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M20 5v6h-6M4 19v-6h6M6.8 9A7 7 0 0 1 18 7l2 2M17.2 15A7 7 0 0 1 6 17l-2-2" />
                </svg>
                <span>{{ loading ? '刷新中...' : '刷新' }}</span>
              </button>
            </div>
          </section>

          <aside class="credits-stats">
            <article class="credits-stat-card">
              <span>签到状态</span>
              <strong>{{ checkedIn ? '已完成' : '待签到' }}</strong>
            </article>

            <article class="credits-stat-card">
              <span>流水条数</span>
              <strong>{{ transactions.length }}</strong>
            </article>

            <article class="credits-stat-card">
              <span>账户身份</span>
              <strong>{{ account?.isRegistered ? '注册用户' : '匿名成员' }}</strong>
            </article>
          </aside>
        </div>
      </header>

      <section class="credits-ledger">
        <div class="credits-ledger-head">
          <div>
            <div class="fc-kicker">Ledger</div>
            <h2>积分流水</h2>
          </div>
        </div>

        <div v-if="loading && transactions.length === 0" class="credits-state">
          <div class="credits-spinner"></div>
          <p>正在加载...</p>
        </div>

        <div v-else-if="transactions.length === 0" class="credits-state">
          <p>暂无积分记录</p>
        </div>

        <div v-else class="credits-list">
          <article v-for="item in transactions" :key="item.id" class="credits-item">
            <div class="credits-item-main">
              <div class="credits-item-title">{{ item.typeDesc || '积分变动' }}</div>
              <div class="credits-item-meta">
                <span>{{ item.remark || '系统记录' }}</span>
                <span>{{ formatDateTime(item.createTime) }}</span>
              </div>
            </div>

            <div class="credits-item-amount" :class="{ positive: Number(item.amount) > 0, negative: Number(item.amount) < 0 }">
              {{ Number(item.amount) > 0 ? `+${item.amount}` : `${item.amount}` }}
            </div>
          </article>
        </div>

        <div v-if="transactions.length > 0" class="credits-more">
          <button class="credits-refresh" type="button" :disabled="loading || !hasMore" @click="loadTransactions(false)">
            <span>{{ hasMore ? (loading ? '加载中...' : '加载更多') : '已经到底了' }}</span>
          </button>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageNoticeToast from '@/components/PageNoticeToast.vue'
import { useAuth } from '@/composables/useAuth'
import { usePageNotice } from '@/composables/usePageNotice'
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
const { notice, showNotice } = usePageNotice(2200)

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
      showNotice('签到成功 +50 积分', 'success')
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
  padding: 20px 18px 28px;
  background: var(--fc-app-gradient);
}

.credits-shell {
  max-width: 1120px;
  margin: 0 auto;
  padding: 20px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 28px;
  background: rgba(255, 253, 249, 0.9);
  box-shadow: 0 18px 40px rgba(33, 26, 20, 0.08);
}

.credits-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.credits-back,
.credits-sign,
.credits-refresh {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 40px;
  padding: 10px 14px;
  border: 1px solid var(--fc-border);
  border-radius: 999px;
  font-family: var(--fc-font);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: border-color var(--fc-duration-normal) var(--fc-ease-in-out), background var(--fc-duration-normal) var(--fc-ease-in-out), color var(--fc-duration-normal) var(--fc-ease-in-out), box-shadow var(--fc-duration-normal) var(--fc-ease-in-out);
}

.credits-back,
.credits-refresh {
  background: rgba(255, 255, 255, 0.84);
  color: var(--fc-text);
}

.credits-back svg,
.credits-sign svg,
.credits-refresh svg {
  width: 15px;
  height: 15px;
  stroke: currentColor;
  stroke-width: 1.9;
  fill: none;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.credits-back:hover,
.credits-refresh:hover {
  border-color: var(--fc-border-strong);
  background: var(--fc-bg-light);
  box-shadow: 0 0 0 3px rgba(182, 118, 57, 0.08);
}

.credits-sign {
  background: var(--fc-accent);
  border-color: transparent;
  color: #fffaf3;
  box-shadow: 0 10px 20px rgba(151, 90, 38, 0.16);
}

.credits-sign:hover:not(:disabled) {
  background: var(--fc-accent-strong);
}

.credits-back:disabled,
.credits-sign:disabled,
.credits-refresh:disabled {
  opacity: 0.55;
  cursor: not-allowed;
  box-shadow: none;
}

.credits-overview {
  margin-top: 18px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 260px;
  gap: 14px;
}

.credits-main-card,
.credits-stat-card,
.credits-ledger,
.credits-state,
.credits-item {
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.82);
}

.credits-main-card {
  padding: 22px;
}

.credits-main-card h1 {
  margin: 10px 0 6px;
  font-family: var(--fc-font-display);
  font-size: clamp(28px, 4vw, 38px);
  line-height: 1.08;
  font-weight: 600;
  color: var(--fc-text);
}

.credits-main-card p {
  margin: 0;
  font-size: 14px;
  line-height: 1.5;
  color: var(--fc-text-sec);
}

.credits-balance-panel {
  margin-top: 18px;
  padding: 18px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 20px;
  background: rgba(255, 253, 249, 0.92);
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.credits-balance-value {
  margin-top: 10px;
  font-family: var(--fc-font-display);
  font-size: clamp(42px, 8vw, 58px);
  line-height: 0.96;
  font-weight: 600;
  color: var(--fc-text);
}

.credits-status {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 6px 10px;
  border-radius: 999px;
  border: 1px solid rgba(186, 91, 64, 0.16);
  background: rgba(255, 244, 241, 0.88);
  font-size: 11px;
  font-weight: 700;
  color: var(--fc-danger);
  white-space: nowrap;
}

.credits-status.ready {
  border-color: rgba(84, 120, 76, 0.18);
  background: rgba(84, 120, 76, 0.12);
  color: #42673f;
}

.credits-meta {
  margin-top: 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.credits-meta span {
  padding: 6px 10px;
  border-radius: 999px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  background: rgba(255, 255, 255, 0.88);
  font-size: 12px;
  color: var(--fc-text-sec);
}

.credits-actions {
  margin-top: 14px;
  display: flex;
  gap: 10px;
}

.credits-stats {
  display: grid;
  gap: 12px;
}

.credits-stat-card {
  padding: 18px;
}

.credits-stat-card span {
  display: block;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.credits-stat-card strong {
  display: block;
  margin-top: 10px;
  font-family: var(--fc-font-display);
  font-size: 28px;
  line-height: 1.04;
  font-weight: 600;
  color: var(--fc-text);
}

.credits-ledger {
  margin-top: 16px;
  padding: 20px;
}

.credits-ledger-head h2 {
  margin: 8px 0 0;
  font-family: var(--fc-font-display);
  font-size: 22px;
  font-weight: 600;
  color: var(--fc-text);
}

.credits-state {
  margin-top: 14px;
  padding: 30px 20px;
  text-align: center;
}

.credits-state p {
  margin: 0;
  font-size: 14px;
  color: var(--fc-text-sec);
}

.credits-spinner {
  width: 28px;
  height: 28px;
  margin: 0 auto 12px;
  border: 2px solid rgba(77, 52, 31, 0.12);
  border-top-color: var(--fc-accent);
  border-radius: 50%;
  animation: credits-spin 0.7s linear infinite;
}

@keyframes credits-spin {
  to { transform: rotate(360deg); }
}

.credits-list {
  margin-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.credits-item {
  padding: 14px 16px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  transition: border-color var(--fc-duration-normal) var(--fc-ease-in-out), background var(--fc-duration-normal) var(--fc-ease-in-out);
}

.credits-item:hover {
  border-color: rgba(182, 118, 57, 0.16);
  background: rgba(255, 253, 249, 0.92);
}

.credits-item-main {
  min-width: 0;
}

.credits-item-title {
  font-family: var(--fc-font);
  font-size: 14px;
  font-weight: 600;
  color: var(--fc-text);
}

.credits-item-meta {
  margin-top: 6px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 12px;
  color: var(--fc-text-muted);
}

.credits-item-amount {
  font-family: var(--fc-font-display);
  font-size: 20px;
  line-height: 1;
  font-weight: 600;
  color: var(--fc-text);
}

.credits-item-amount.positive { color: var(--fc-success); }
.credits-item-amount.negative { color: var(--fc-danger); }

.credits-more {
  margin-top: 14px;
  display: flex;
  justify-content: center;
}

.credits-back:focus-visible,
.credits-sign:focus-visible,
.credits-refresh:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--fc-focus-ring);
}

@media (prefers-reduced-motion: reduce) {
  .credits-back,
  .credits-sign,
  .credits-refresh,
  .credits-item,
  .credits-spinner {
    transition: none;
    animation: none !important;
  }
}

@media (max-width: 920px) {
  .credits-overview {
    grid-template-columns: 1fr;
  }

  .credits-stats {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .credits-page {
    padding: 14px 12px 22px;
  }

  .credits-shell {
    padding: 16px;
    border-radius: 22px;
  }

  .credits-toolbar,
  .credits-actions {
    flex-direction: column;
    align-items: stretch;
  }

  .credits-sign,
  .credits-refresh {
    width: 100%;
  }

  .credits-stats {
    grid-template-columns: 1fr;
  }

  .credits-item {
    grid-template-columns: 1fr;
  }

  .credits-item-amount {
    font-size: 18px;
  }
}

@media (max-width: 520px) {
  .credits-balance-panel {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
