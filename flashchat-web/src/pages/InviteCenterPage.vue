<template>
  <div class="invites-page">
    <PageNoticeToast :notice="notice" />
    <div class="invites-shell">
      <header class="invites-top">
        <div class="invites-toolbar">
          <button class="invites-back" type="button" @click="goRoomList">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M15 6l-6 6 6 6" />
            </svg>
            <span>返回房间列表</span>
          </button>

        </div>

        <div class="invites-overview">
          <section class="invites-main-card">
            <div class="fc-kicker">Invite</div>
            <h1>邀请码</h1>
            <p>主邀请码与邀请码列表</p>

            <div class="invites-code-panel">
              <div class="invites-code-head">
                <div class="fc-section-label">主邀请码</div>
                <span class="invites-status" :class="{ ready: Boolean(account?.inviteCode) }">
                  {{ account?.inviteCode ? '已生成' : '未生成' }}
                </span>
              </div>

              <div class="invites-code-value">{{ account?.inviteCode || '—' }}</div>
            </div>

            <div class="invites-actions">
              <button class="invites-copy-main" type="button" :disabled="!account?.inviteCode" @click="copyText(account?.inviteCode)">
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M9 9h9v11H9zM6 15H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1h9a1 1 0 0 1 1 1v1" />
                </svg>
                <span>复制邀请码</span>
              </button>

              <button class="invites-refresh" type="button" :disabled="loading" @click="loadPage">
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M20 5v6h-6M4 19v-6h6M6.8 9A7 7 0 0 1 18 7l2 2M17.2 15A7 7 0 0 1 6 17l-2-2" />
                </svg>
                <span>{{ loading ? '刷新中...' : '刷新' }}</span>
              </button>
            </div>
          </section>

          <aside class="invites-stats">
            <article class="invites-stat-card">
              <span>总数</span>
              <strong>{{ codes.length }}</strong>
            </article>

            <article class="invites-stat-card">
              <span>可用</span>
              <strong>{{ unusedCount }}</strong>
            </article>

            <article class="invites-stat-card">
              <span>已使用</span>
              <strong>{{ usedCount }}</strong>
            </article>
          </aside>
        </div>
      </header>

      <section class="invites-list-card">
        <div class="invites-list-head">
          <div>
            <div class="fc-kicker">Inventory</div>
            <h2>邀请码列表</h2>
          </div>
        </div>

        <div v-if="loading && codes.length === 0" class="invites-state">
          <div class="invites-spinner"></div>
          <p>正在加载...</p>
        </div>

        <div v-else-if="codes.length === 0" class="invites-state">
          <p>暂无邀请码</p>
        </div>

        <div v-else class="invites-list">
          <article v-for="item in sortedCodes" :key="item.code" class="invites-item">
            <div class="invites-item-main">
              <div class="invites-item-top">
                <div class="invites-item-code">{{ item.code }}</div>
                <span class="invites-badge" :class="{ used: item.used, idle: !item.used }">
                  {{ item.used ? '已使用' : '可用' }}
                </span>
              </div>

              <div class="invites-item-meta">
                <span>{{ formatDateTime(item.createTime) }}</span>
                <span v-if="item.usedByAccountId">使用者 {{ item.usedByAccountId }}</span>
              </div>
            </div>

            <button class="invites-copy-btn" type="button" @click="copyText(item.code)">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M9 9h9v11H9zM6 15H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1h9a1 1 0 0 1 1 1v1" />
              </svg>
              <span>复制</span>
            </button>
          </article>
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
import { getInviteCodes, getMyAccount } from '@/api/account'

const router = useRouter()
const auth = useAuth()

const account = ref(null)
const codes = ref([])
const loading = ref(false)
const { notice, showNotice } = usePageNotice(1800)

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
    showNotice(error?.message || '邀请码加载失败', 'error')
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
  showNotice('已复制', 'success')
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
.invites-page {
  min-height: 100vh;
  padding: 20px 18px 28px;
  background: var(--fc-app-gradient);
}

.invites-shell {
  max-width: 1120px;
  margin: 0 auto;
  padding: 20px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 28px;
  background: rgba(255, 253, 249, 0.9);
  box-shadow: 0 18px 40px rgba(33, 26, 20, 0.08);
}

.invites-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.invites-back,
.invites-copy-main,
.invites-copy-btn,
.invites-refresh {
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

.invites-back,
.invites-copy-btn,
.invites-refresh {
  background: rgba(255, 255, 255, 0.84);
  color: var(--fc-text);
}

.invites-back svg,
.invites-copy-main svg,
.invites-copy-btn svg,
.invites-refresh svg {
  width: 15px;
  height: 15px;
  stroke: currentColor;
  stroke-width: 1.9;
  fill: none;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.invites-back:hover,
.invites-copy-btn:hover,
.invites-refresh:hover {
  border-color: var(--fc-border-strong);
  background: var(--fc-bg-light);
  box-shadow: 0 0 0 3px rgba(182, 118, 57, 0.08);
}

.invites-copy-main {
  background: var(--fc-accent);
  border-color: transparent;
  color: #fffaf3;
  box-shadow: 0 10px 20px rgba(151, 90, 38, 0.16);
}

.invites-copy-main:hover:not(:disabled) {
  background: var(--fc-accent-strong);
}

.invites-back:disabled,
.invites-copy-main:disabled,
.invites-copy-btn:disabled,
.invites-refresh:disabled {
  opacity: 0.55;
  cursor: not-allowed;
  box-shadow: none;
}

.invites-overview {
  margin-top: 18px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 260px;
  gap: 14px;
}

.invites-main-card,
.invites-stat-card,
.invites-list-card,
.invites-state,
.invites-item {
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.82);
}

.invites-main-card {
  padding: 22px;
}

.invites-main-card h1 {
  margin: 10px 0 6px;
  font-family: var(--fc-font-display);
  font-size: clamp(28px, 4vw, 38px);
  line-height: 1.08;
  font-weight: 600;
  color: var(--fc-text);
}

.invites-main-card p {
  margin: 0;
  font-size: 14px;
  line-height: 1.5;
  color: var(--fc-text-sec);
}

.invites-code-panel {
  margin-top: 18px;
  padding: 18px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 20px;
  background: rgba(255, 253, 249, 0.92);
}

.invites-code-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.invites-status {
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

.invites-status.ready {
  border-color: rgba(84, 120, 76, 0.18);
  background: rgba(84, 120, 76, 0.12);
  color: #42673f;
}

.invites-code-value {
  margin-top: 12px;
  font-family: var(--fc-font-mono);
  font-size: clamp(24px, 4vw, 34px);
  line-height: 1.08;
  color: var(--fc-text);
  word-break: break-all;
}

.invites-actions {
  margin-top: 14px;
  display: flex;
  gap: 10px;
}

.invites-stats {
  display: grid;
  gap: 12px;
}

.invites-stat-card {
  padding: 18px;
}

.invites-stat-card span {
  display: block;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.invites-stat-card strong {
  display: block;
  margin-top: 10px;
  font-family: var(--fc-font-display);
  font-size: 28px;
  line-height: 1.04;
  font-weight: 600;
  color: var(--fc-text);
}

.invites-list-card {
  margin-top: 16px;
  padding: 20px;
}

.invites-list-head h2 {
  margin: 8px 0 0;
  font-family: var(--fc-font-display);
  font-size: 22px;
  font-weight: 600;
  color: var(--fc-text);
}

.invites-state {
  margin-top: 14px;
  padding: 30px 20px;
  text-align: center;
}

.invites-state p {
  margin: 0;
  font-size: 14px;
  color: var(--fc-text-sec);
}

.invites-spinner {
  width: 28px;
  height: 28px;
  margin: 0 auto 12px;
  border: 2px solid rgba(77, 52, 31, 0.12);
  border-top-color: var(--fc-accent);
  border-radius: 50%;
  animation: invites-spin 0.7s linear infinite;
}

@keyframes invites-spin {
  to { transform: rotate(360deg); }
}

.invites-list {
  margin-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.invites-item {
  padding: 14px 16px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  transition: border-color var(--fc-duration-normal) var(--fc-ease-in-out), background var(--fc-duration-normal) var(--fc-ease-in-out);
}

.invites-item:hover {
  border-color: rgba(182, 118, 57, 0.16);
  background: rgba(255, 253, 249, 0.92);
}

.invites-item-main {
  min-width: 0;
}

.invites-item-top {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.invites-item-code {
  font-family: var(--fc-font-mono);
  font-size: 14px;
  color: var(--fc-text);
}

.invites-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 5px 9px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  border: 1px solid transparent;
}

.invites-badge.idle {
  background: rgba(84, 120, 76, 0.12);
  border-color: rgba(84, 120, 76, 0.18);
  color: #42673f;
}

.invites-badge.used {
  background: rgba(233, 225, 217, 0.88);
  border-color: rgba(125, 108, 92, 0.14);
  color: #7d6c5c;
}

.invites-item-meta {
  margin-top: 6px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 12px;
  color: var(--fc-text-muted);
}

.invites-back:focus-visible,
.invites-copy-main:focus-visible,
.invites-copy-btn:focus-visible,
.invites-refresh:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--fc-focus-ring);
}

@media (prefers-reduced-motion: reduce) {
  .invites-back,
  .invites-copy-main,
  .invites-copy-btn,
  .invites-refresh,
  .invites-item,
  .invites-spinner {
    transition: none;
    animation: none !important;
  }
}

@media (max-width: 920px) {
  .invites-overview {
    grid-template-columns: 1fr;
  }

  .invites-stats {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .invites-page {
    padding: 14px 12px 22px;
  }

  .invites-shell {
    padding: 16px;
    border-radius: 22px;
  }

  .invites-toolbar,
  .invites-actions {
    flex-direction: column;
    align-items: stretch;
  }

  .invites-copy-main,
  .invites-copy-btn,
  .invites-refresh {
    width: 100%;
  }

  .invites-stats {
    grid-template-columns: 1fr;
  }

  .invites-item {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 520px) {
  .invites-code-head {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
