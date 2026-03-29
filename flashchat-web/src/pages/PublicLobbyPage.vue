<template>
  <div class="hall">
    <div class="hall-orb hall-orb-a"></div>
    <div class="hall-orb hall-orb-b"></div>

    <header class="hall-top">
      <button class="hall-back" type="button" @click="router.push('/')">返回聊天</button>
      <div class="hall-top-copy">
        <div class="hall-kicker">Public Lobby</div>
        <h1>公开房间大厅</h1>
        <p>快速发现正在进行中的公开房间，按热度、最新或即将到期筛选。</p>
      </div>
      <div class="hall-identity">
        <span>当前身份</span>
        <strong>{{ identityName }}</strong>
      </div>
    </header>

    <section class="hall-toolbar">
      <div class="hall-sort">
        <button
          v-for="option in sortOptions"
          :key="option.value"
          type="button"
          class="hall-sort-btn"
          :class="{ active: sort === option.value }"
          @click="switchSort(option.value)"
        >
          {{ option.label }}
        </button>
      </div>
      <button class="hall-create" type="button" @click="router.push('/')">去创建房间</button>
    </section>

    <section v-if="loading && rooms.length === 0" class="hall-state">
      <div class="hall-spinner"></div>
      <p>正在加载公开房间...</p>
    </section>

    <section v-else-if="error" class="hall-state">
      <p class="hall-error">{{ error }}</p>
      <button class="hall-retry" type="button" @click="loadRooms(true)">重新加载</button>
    </section>

    <section v-else-if="rooms.length === 0" class="hall-empty">
      <h2>公开大厅暂时还很安静</h2>
      <p>你可以先创建一个公开房间，把它放到大厅里等别人加入。</p>
      <button class="hall-create" type="button" @click="router.push('/')">去发起公开房间</button>
    </section>

    <section v-else class="hall-grid">
      <article v-for="room in rooms" :key="room.roomId" class="hall-card">
        <div class="hall-card-head">
          <div>
            <div class="hall-card-title">{{ room.title || room.roomId }}</div>
            <div class="hall-card-id">{{ room.roomId }}</div>
          </div>
          <span class="hall-badge" :class="statusClass(room.status)">{{ room.statusDesc || '开放中' }}</span>
        </div>

        <div class="hall-card-meta">
          <span>{{ room.memberCount || 0 }} / {{ room.maxMembers || 50 }} 人</span>
          <span>在线 {{ room.onlineCount || 0 }}</span>
          <span>创建于 {{ formatDateTime(room.createTime) }}</span>
        </div>

        <div class="hall-card-countdown" :style="{ color: getCountdownColor(countdownMs(room)) }">
          {{ room.expireTime ? formatCountdown(countdownMs(room)) : '永久不过期' }}
        </div>

        <div class="hall-card-actions">
          <button class="hall-card-btn ghost" type="button" @click="copyShare(room)">
            {{ copyingRoomId === room.roomId ? '已复制' : '复制链接' }}
          </button>
          <button class="hall-card-btn" type="button" @click="router.push(`/room/${room.roomId}`)">
            进入房间
          </button>
        </div>
      </article>
    </section>

    <div v-if="rooms.length > 0" class="hall-more">
      <button class="hall-retry" type="button" :disabled="loading || !hasMore" @click="loadRooms(false)">
        {{ hasMore ? (loading ? '加载中...' : '加载更多') : '已经到底了' }}
      </button>
    </div>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '@/composables/useAuth'
import { listPublicRooms } from '@/api/room'
import { formatCountdown, getCountdownColor } from '@/utils/formatter'

const router = useRouter()
const auth = useAuth()

const rooms = ref([])
const loading = ref(false)
const error = ref('')
const sort = ref('hot')
const page = ref(1)
const size = 20
const hasMore = ref(true)
const copyingRoomId = ref('')
const identityName = ref('游客')
const now = ref(Date.now())

const sortOptions = [
  { value: 'hot', label: '最热' },
  { value: 'newest', label: '最新' },
  { value: 'expiring', label: '即将到期' }
]

let timer = null

onMounted(async () => {
  timer = window.setInterval(() => {
    now.value = Date.now()
  }, 30000)

  try {
    await auth.init()
    identityName.value = auth.identity.value?.nickname || '游客'
  } catch {
    identityName.value = '游客'
  }

  await loadRooms(true)
})

onUnmounted(() => {
  if (timer) window.clearInterval(timer)
})

async function loadRooms(reset) {
  if (loading.value) return
  loading.value = true
  error.value = ''

  if (reset) {
    page.value = 1
    hasMore.value = true
  }

  try {
    const list = await listPublicRooms({
      page: page.value,
      size,
      sort: sort.value
    })
    const next = Array.isArray(list) ? list : []
    rooms.value = reset ? next : [...rooms.value, ...next]
    hasMore.value = next.length === size
    if (hasMore.value) page.value += 1
  } catch (err) {
    error.value = err?.message || '公开房间加载失败'
  } finally {
    loading.value = false
  }
}

async function copyShare(room) {
  const url = room.shareUrl || `${window.location.origin}/room/${room.roomId}`
  try {
    await navigator.clipboard.writeText(url)
  } catch {
    const input = document.createElement('input')
    input.value = url
    document.body.appendChild(input)
    input.select()
    document.execCommand('copy')
    document.body.removeChild(input)
  }

  copyingRoomId.value = room.roomId
  window.setTimeout(() => {
    if (copyingRoomId.value === room.roomId) copyingRoomId.value = ''
  }, 1500)
}

function switchSort(nextSort) {
  if (sort.value === nextSort) return
  sort.value = nextSort
  loadRooms(true)
}

function countdownMs(room) {
  return new Date(room.expireTime).getTime() - now.value
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

function statusClass(status) {
  if (status === 1) return 'active'
  if (status === 2) return 'warning'
  return 'waiting'
}
</script>

<style scoped>
.hall {
  min-height: 100vh;
  padding: 28px 28px 36px;
  position: relative;
  overflow: hidden;
  background:
    radial-gradient(circle at 10% 0%, rgba(221, 193, 163, 0.42), transparent 26%),
    radial-gradient(circle at 100% 10%, rgba(173, 122, 68, 0.16), transparent 20%),
    linear-gradient(180deg, #f4ebdd 0%, #e9dccb 100%);
}

.hall-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(18px);
  pointer-events: none;
}

.hall-orb-a {
  width: 240px;
  height: 240px;
  top: -90px;
  right: -80px;
  background: rgba(173, 122, 68, 0.12);
}

.hall-orb-b {
  width: 220px;
  height: 220px;
  bottom: -110px;
  left: -80px;
  background: rgba(221, 193, 163, 0.26);
}

.hall-top,
.hall-toolbar,
.hall-grid,
.hall-more,
.hall-state,
.hall-empty {
  position: relative;
  z-index: 1;
}

.hall-top {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 18px;
  align-items: start;
}

.hall-back,
.hall-create,
.hall-retry,
.hall-card-btn {
  border: 1px solid rgba(77, 52, 31, 0.10);
  border-radius: 18px;
  font-family: var(--fc-font);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}

.hall-back,
.hall-retry,
.hall-card-btn.ghost {
  padding: 12px 16px;
  background: rgba(255, 250, 243, 0.84);
  color: var(--fc-text-sec);
}

.hall-create,
.hall-card-btn {
  padding: 12px 18px;
  background: linear-gradient(135deg, #b68450 0%, #8c5a2b 100%);
  color: #fffaf3;
  box-shadow: 0 16px 28px rgba(140, 90, 43, 0.22);
}

.hall-kicker {
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.hall-top-copy h1 {
  margin: 10px 0 10px;
  font-family: var(--fc-font);
  font-size: clamp(34px, 5vw, 52px);
  line-height: 0.98;
  color: var(--fc-text);
}

.hall-top-copy p,
.hall-state p,
.hall-empty p {
  max-width: 640px;
  margin: 0;
  font-family: var(--fc-font);
  font-size: 15px;
  line-height: 1.7;
  color: var(--fc-text-sec);
}

.hall-identity {
  padding: 16px 18px;
  min-width: 180px;
  border: 1px solid rgba(77, 52, 31, 0.10);
  border-radius: 22px;
  background: rgba(255, 250, 243, 0.76);
  box-shadow: var(--fc-shadow-soft);
}

.hall-identity span {
  display: block;
  font-family: var(--fc-font);
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.12em;
  color: var(--fc-text-muted);
}

.hall-identity strong {
  display: block;
  margin-top: 10px;
  font-family: var(--fc-font);
  font-size: 20px;
  color: var(--fc-text);
}

.hall-toolbar {
  margin-top: 28px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
}

.hall-sort {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.hall-sort-btn {
  padding: 11px 16px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 999px;
  background: rgba(255, 250, 243, 0.76);
  font-family: var(--fc-font);
  font-size: 13px;
  font-weight: 600;
  color: var(--fc-text-sec);
  cursor: pointer;
}

.hall-sort-btn.active {
  background: linear-gradient(135deg, #b68450 0%, #8c5a2b 100%);
  border-color: transparent;
  color: #fffaf3;
}

.hall-grid {
  margin-top: 24px;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}

.hall-card {
  padding: 20px;
  border: 1px solid rgba(77, 52, 31, 0.10);
  border-radius: 28px;
  background: rgba(255, 250, 243, 0.82);
  box-shadow: var(--fc-shadow-soft);
}

.hall-card-head,
.hall-card-actions {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.hall-card-title {
  font-family: var(--fc-font);
  font-size: 22px;
  font-weight: 700;
  color: var(--fc-text);
}

.hall-card-id,
.hall-card-meta {
  font-family: var(--fc-font);
  color: var(--fc-text-sec);
}

.hall-card-id {
  margin-top: 8px;
  font-size: 12px;
  letter-spacing: 0.08em;
}

.hall-badge {
  padding: 6px 12px;
  border-radius: 999px;
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 700;
}

.hall-badge.waiting { background: rgba(243, 231, 215, 0.92); color: var(--fc-accent-strong); }
.hall-badge.active { background: rgba(235, 245, 230, 0.96); color: #42673f; }
.hall-badge.warning { background: rgba(255, 242, 224, 0.96); color: #8b641c; }

.hall-card-meta {
  margin-top: 16px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px 12px;
  font-size: 13px;
}

.hall-card-countdown {
  margin-top: 18px;
  font-family: var(--fc-font);
  font-size: 28px;
  font-weight: 700;
}

.hall-card-actions {
  margin-top: 18px;
}

.hall-state,
.hall-empty,
.hall-more {
  margin-top: 30px;
}

.hall-state,
.hall-empty {
  padding: 32px;
  border: 1px solid rgba(77, 52, 31, 0.10);
  border-radius: 28px;
  background: rgba(255, 250, 243, 0.82);
  box-shadow: var(--fc-shadow-soft);
  text-align: center;
}

.hall-empty h2,
.hall-error {
  margin: 0 0 12px;
  font-family: var(--fc-font);
  color: var(--fc-text);
}

.hall-spinner {
  width: 34px;
  height: 34px;
  margin: 0 auto 16px;
  border: 3px solid rgba(77, 52, 31, 0.10);
  border-top-color: var(--fc-accent);
  border-radius: 50%;
  animation: hall-spin .7s linear infinite;
}

@keyframes hall-spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 1100px) {
  .hall-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .hall {
    padding: 18px 16px 28px;
  }

  .hall-top {
    grid-template-columns: 1fr;
  }

  .hall-toolbar,
  .hall-card-actions {
    flex-direction: column;
    align-items: stretch;
  }

  .hall-grid {
    grid-template-columns: 1fr;
  }
}
</style>
