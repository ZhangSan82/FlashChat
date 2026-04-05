<template>
  <div class="hall">
    <div class="hall-orb hall-orb-a"></div>
    <div class="hall-orb hall-orb-b"></div>

    <header class="hall-top">
      <section class="hall-hero">
        <button class="hall-back" type="button" @click="goRoomList">返回房间列表</button>
        <div class="hall-kicker">Salon Atlas</div>
        <h1>挑一间正在发光的公开房间</h1>
        <p>先看氛围、人数和剩余时间，再决定要不要进入这场即时对话。</p>
        <div class="hall-hero-tags">
          <span>{{ totalRooms }} 间开放房间</span>
          <span>{{ totalOnline }} 人在线</span>
          <span>{{ expiringSoonCount }} 间即将到期</span>
        </div>
      </section>

      <aside class="hall-identity">
        <span class="hall-side-label">当前身份</span>
        <strong>{{ identityName }}</strong>
        <p>{{ sideHint }}</p>
        <div class="hall-side-pills">
          <span>席位占用 {{ occupancySummary }}</span>
          <span>{{ loading ? '同步中' : '每 30 秒刷新' }}</span>
        </div>
      </aside>
    </header>

    <section class="hall-summary">
      <article v-for="item in summaryCards" :key="item.label" class="hall-summary-card">
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
        <p>{{ item.help }}</p>
      </article>
    </section>

    <section class="hall-toolbar">
      <div class="hall-toolbar-copy">
        <div class="hall-kicker">Room Curation</div>
        <h2>按节奏筛选正在进行的对话</h2>
      </div>

      <div class="hall-toolbar-actions">
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
        <button class="hall-create" type="button" @click="goCreateRoom">创建公开房间</button>
      </div>
    </section>

    <section v-if="loading && rooms.length === 0" class="hall-state">
      <div class="hall-spinner"></div>
      <h3>正在同步公开大厅</h3>
      <p>房间状态、在线人数和剩余时间会在这里实时更新。</p>
    </section>

    <section v-else-if="error" class="hall-state">
      <h3>公开大厅暂时无法打开</h3>
      <p class="hall-error">{{ error }}</p>
      <button class="hall-retry" type="button" @click="loadRooms(true)">重新加载</button>
    </section>

    <section v-else-if="rooms.length === 0" class="hall-empty">
      <div class="hall-kicker">Quiet Moment</div>
      <h3>公开大厅现在很安静</h3>
      <p>不如先创建一个公开房间，把它放进大厅里，等别人循着链接和二维码加入。</p>
      <button class="hall-create" type="button" @click="goCreateRoom">去创建公开房间</button>
    </section>

    <section v-else class="hall-grid">
      <article v-for="room in rooms" :key="room.roomId" class="hall-card">
        <div class="hall-card-topline">
          <span class="hall-card-chip">Public Room</span>
          <span class="hall-badge" :class="statusClass(room.status)">{{ room.statusDesc || '开放中' }}</span>
        </div>

        <div class="hall-card-head">
          <div class="hall-card-identity">
            <img class="hall-card-avatar" :src="getRoomVisualUrl(room)" :alt="getRoomDisplayName(room)" />
            <div class="hall-card-copy">
              <div class="hall-card-title">{{ getRoomDisplayName(room) }}</div>
              <div class="hall-card-id">{{ room.roomId }}</div>
            </div>
          </div>
        </div>

        <div class="hall-card-countdown-wrap">
          <div class="hall-card-countdown-label">剩余开放时间</div>
          <div class="hall-card-countdown" :style="{ color: getCountdownColor(countdownMs(room)) }">
            {{ room.expireTime ? formatCountdown(countdownMs(room)) : '永久不过期' }}
          </div>
        </div>

        <div class="hall-card-progress">
          <div class="hall-card-progress-fill" :style="{ width: `${occupancyPercent(room)}%` }"></div>
        </div>

        <div class="hall-card-stats">
          <div class="hall-card-stat">
            <span>在线成员</span>
            <strong>{{ room.onlineCount || 0 }}</strong>
          </div>
          <div class="hall-card-stat">
            <span>当前人数</span>
            <strong>{{ room.memberCount || 0 }} / {{ room.maxMembers || 50 }}</strong>
          </div>
          <div class="hall-card-stat hall-card-stat-wide">
            <span>创建于</span>
            <strong>{{ formatDateTime(room.createTime) }}</strong>
          </div>
        </div>

        <p class="hall-card-note">{{ roomOccupancyText(room) }}</p>

        <div class="hall-card-actions">
          <button class="hall-card-btn ghost" type="button" @click="copyShare(room)">
            {{ copyingRoomId === room.roomId ? '已复制' : '复制链接' }}
          </button>
          <button class="hall-card-btn" type="button" @click="goPreview(room)">房间预览</button>
        </div>
      </article>
    </section>

    <div v-if="rooms.length > 0" class="hall-more">
      <button class="hall-retry" type="button" :disabled="loading || !hasMore" @click="loadRooms(false)">
        {{ hasMore ? (loading ? '加载中...' : '加载更多房间') : '已经到底了' }}
      </button>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '@/composables/useAuth'
import { listPublicRooms } from '@/api/room'
import { formatCountdown, getCountdownColor } from '@/utils/formatter'
import { getRoomDisplayName, getRoomVisualUrl } from '@/utils/roomVisual'

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

const totalRooms = computed(() => rooms.value.length)
const totalOnline = computed(() => rooms.value.reduce((sum, room) => sum + Number(room.onlineCount || 0), 0))
const totalMembers = computed(() => rooms.value.reduce((sum, room) => sum + Number(room.memberCount || 0), 0))
const totalCapacity = computed(() => rooms.value.reduce((sum, room) => sum + Number(room.maxMembers || 0), 0))
const expiringSoonCount = computed(() =>
  rooms.value.filter(room => {
    const remain = countdownMs(room)
    return Number.isFinite(remain) && remain > 0 && remain <= 15 * 60 * 1000
  }).length
)
const occupancySummary = computed(() => {
  if (!totalCapacity.value) return '0%'
  return `${Math.round((totalMembers.value / totalCapacity.value) * 100)}%`
})
const sideHint = computed(() => {
  if (loading.value) return '大厅正在刷新最新房态和在线人数。'
  if (rooms.value.length === 0) return '创建公开房间后，其他人就能在这里看到它。'
  return '挑一间合适的房间预览后，就可以直接进入聊天。'
})
const summaryCards = computed(() => [
  {
    label: '开放房间',
    value: `${totalRooms.value}`,
    help: totalRooms.value ? '可以直接预览并加入' : '等待下一场对话开放'
  },
  {
    label: '在线人数',
    value: `${totalOnline.value}`,
    help: totalOnline.value ? '大厅里已经有人在交流' : '还没有成员在线'
  },
  {
    label: '席位占用',
    value: occupancySummary.value,
    help: totalCapacity.value ? `${totalMembers.value} / ${totalCapacity.value} 个席位已使用` : '当前还没有可统计席位'
  },
  {
    label: '即将到期',
    value: `${expiringSoonCount.value}`,
    help: expiringSoonCount.value ? '适合快速加入即时对话' : '当前没有快到期的房间'
  }
])

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

function goRoomList() {
  router.push({ name: 'Chat', query: { view: 'rooms' } })
}

function goCreateRoom() {
  router.push({ name: 'Chat', query: { view: 'rooms', action: 'create' } })
}

function goPreview(room) {
  router.push({
    name: 'JoinRoom',
    params: { roomId: room.roomId },
    query: { from: 'public' }
  })
}

function countdownMs(room) {
  if (!room?.expireTime) return Number.POSITIVE_INFINITY
  const time = new Date(room.expireTime).getTime()
  if (Number.isNaN(time)) return Number.POSITIVE_INFINITY
  return time - now.value
}

function occupancyPercent(room) {
  const maxMembers = Number(room.maxMembers || 0)
  if (!maxMembers) return 0
  const percent = Math.round((Number(room.memberCount || 0) / maxMembers) * 100)
  return Math.max(8, Math.min(100, percent))
}

function roomOccupancyText(room) {
  const maxMembers = Number(room.maxMembers || 0)
  const currentMembers = Number(room.memberCount || 0)
  const onlineMembers = Number(room.onlineCount || 0)
  if (!maxMembers || currentMembers === 0) return '现在进去，大概率能成为第一批进入的人。'

  const ratio = currentMembers / maxMembers
  if (ratio >= 0.9) return '这个房间接近满员，适合立刻加入。'
  if (ratio >= 0.6) return '房间已经热起来了，加入后能很快接上节奏。'
  if (onlineMembers > 0) return '已经有人在线，适合随时进入互动。'
  return '人数还在缓慢增长，适合提前占位。'
}

function formatDateTime(value) {
  if (!value) return '--'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '--'
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
  padding: 32px 32px 40px;
  position: relative;
  overflow-x: hidden;
  background:
    radial-gradient(circle at 8% 0%, rgba(224, 194, 161, 0.42), transparent 26%),
    radial-gradient(circle at 100% 8%, rgba(182, 118, 57, 0.14), transparent 20%),
    linear-gradient(180deg, #f7eee3 0%, #e7d8c6 100%);
}

.hall-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(20px);
  pointer-events: none;
}

.hall-orb-a {
  width: 260px;
  height: 260px;
  top: -110px;
  right: -80px;
  background: rgba(182, 118, 57, 0.14);
}

.hall-orb-b {
  width: 240px;
  height: 240px;
  bottom: -120px;
  left: -90px;
  background: rgba(224, 194, 161, 0.24);
}

.hall-top,
.hall-summary,
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
  grid-template-columns: minmax(0, 1.5fr) 360px;
  gap: 18px;
  align-items: stretch;
}

.hall-hero,
.hall-identity,
.hall-summary-card,
.hall-toolbar,
.hall-card,
.hall-state,
.hall-empty {
  border: 1px solid var(--fc-border);
  border-radius: var(--fc-radius-lg);
  background: var(--fc-panel);
  box-shadow: var(--fc-shadow-soft);
  backdrop-filter: blur(14px);
}

.hall-hero,
.hall-identity,
.hall-state,
.hall-empty {
  padding: 28px;
}

.hall-hero {
  position: relative;
  overflow: hidden;
}

.hall-hero::before,
.hall-card::before {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.28), transparent 38%),
    radial-gradient(circle at top right, rgba(182, 118, 57, 0.1), transparent 32%);
}

.hall-back,
.hall-create,
.hall-retry,
.hall-card-btn {
  border: 1px solid var(--fc-border);
  border-radius: 18px;
  font-family: var(--fc-font);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: transform 0.2s ease, filter 0.2s ease, box-shadow 0.2s ease;
}

.hall-back,
.hall-retry,
.hall-card-btn.ghost {
  padding: 12px 16px;
  background: rgba(255, 250, 243, 0.88);
  color: var(--fc-text-sec);
}

.hall-create,
.hall-card-btn {
  padding: 12px 18px;
  background: linear-gradient(135deg, #bd7b3c 0%, #8a4e22 100%);
  color: #fffaf3;
  box-shadow: 0 18px 30px rgba(138, 78, 34, 0.22);
}

.hall-back:hover,
.hall-create:hover,
.hall-retry:hover,
.hall-card-btn:hover,
.hall-sort-btn:hover {
  transform: translateY(-1px);
  filter: brightness(1.03);
}

.hall-kicker,
.hall-side-label,
.hall-summary-card span,
.hall-card-countdown-label,
.hall-card-stat span {
  display: block;
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.hall-kicker {
  margin-top: 18px;
}

.hall-hero h1,
.hall-toolbar-copy h2,
.hall-card-title,
.hall-state h3,
.hall-empty h3 {
  font-family: var(--fc-font-display);
  color: var(--fc-text);
}

.hall-hero h1 {
  margin: 14px 0 14px;
  max-width: 720px;
  font-size: clamp(42px, 5vw, 64px);
  line-height: 0.95;
  font-weight: 700;
}

.hall-hero p,
.hall-identity p,
.hall-summary-card p,
.hall-state p,
.hall-empty p,
.hall-card-note {
  margin: 0;
  font-family: var(--fc-font);
  font-size: 14px;
  line-height: 1.7;
  color: var(--fc-text-sec);
}

.hall-hero-tags,
.hall-side-pills {
  margin-top: 22px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.hall-hero-tags span,
.hall-side-pills span,
.hall-card-chip,
.hall-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 7px 12px;
  border-radius: var(--fc-radius-pill);
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 700;
}

.hall-hero-tags span,
.hall-side-pills span,
.hall-card-chip {
  background: rgba(255, 250, 243, 0.82);
  border: 1px solid rgba(72, 49, 28, 0.08);
  color: var(--fc-accent-strong);
}

.hall-identity {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  background: var(--fc-panel-elevated);
  box-shadow: var(--fc-shadow-panel);
}

.hall-identity strong {
  display: block;
  margin-top: 12px;
  font-family: var(--fc-font-display);
  font-size: 42px;
  line-height: 0.96;
  color: var(--fc-text);
  word-break: break-word;
}

.hall-summary {
  margin-top: 18px;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.hall-summary-card {
  padding: 18px;
}

.hall-summary-card strong {
  display: block;
  margin-top: 10px;
  font-family: var(--fc-font-display);
  font-size: 30px;
  line-height: 1;
  color: var(--fc-text);
}

.hall-summary-card p {
  margin-top: 12px;
}

.hall-toolbar {
  margin-top: 18px;
  padding: 20px 24px;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
}

.hall-toolbar-copy h2 {
  margin: 10px 0 0;
  font-size: clamp(26px, 3.6vw, 36px);
  line-height: 1;
  font-weight: 700;
}

.hall-toolbar-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 12px;
}

.hall-sort {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.hall-sort-btn {
  padding: 11px 16px;
  border: 1px solid rgba(72, 49, 28, 0.08);
  border-radius: var(--fc-radius-pill);
  background: rgba(255, 250, 243, 0.76);
  font-family: var(--fc-font);
  font-size: 13px;
  font-weight: 600;
  color: var(--fc-text-sec);
  cursor: pointer;
  transition: transform 0.2s ease, filter 0.2s ease, box-shadow 0.2s ease;
}

.hall-sort-btn.active {
  background: linear-gradient(135deg, #bd7b3c 0%, #8a4e22 100%);
  border-color: transparent;
  color: #fffaf3;
  box-shadow: 0 14px 26px rgba(138, 78, 34, 0.18);
}

.hall-grid {
  margin-top: 18px;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
}

.hall-card {
  position: relative;
  padding: 20px;
  overflow: hidden;
}

.hall-card-topline,
.hall-card-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.hall-card-head {
  margin-top: 16px;
}

.hall-card-identity {
  min-width: 0;
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 14px;
  align-items: center;
}

.hall-card-avatar {
  width: 72px;
  height: 72px;
  border-radius: 24px;
  object-fit: cover;
  box-shadow: 0 16px 28px rgba(61, 40, 22, 0.14);
  background: rgba(243, 231, 215, 0.92);
  flex-shrink: 0;
}

.hall-card-copy {
  min-width: 0;
}

.hall-card-title {
  font-size: 30px;
  line-height: 0.94;
  font-weight: 700;
  word-break: break-word;
}

.hall-card-id {
  margin-top: 10px;
  font-family: var(--fc-font-mono);
  font-size: 12px;
  letter-spacing: 0.1em;
  color: var(--fc-text-sec);
}

.hall-badge.waiting {
  background: rgba(243, 231, 215, 0.92);
  color: var(--fc-accent-strong);
}

.hall-badge.active {
  background: rgba(235, 245, 230, 0.96);
  color: #42673f;
}

.hall-badge.warning {
  background: rgba(255, 242, 224, 0.96);
  color: #8b641c;
}

.hall-card-countdown-wrap {
  margin-top: 18px;
}

.hall-card-countdown {
  margin-top: 10px;
  font-family: var(--fc-font-display);
  font-size: 40px;
  line-height: 0.95;
  font-weight: 700;
}

.hall-card-progress {
  margin-top: 18px;
  height: 8px;
  border-radius: var(--fc-radius-pill);
  background: rgba(72, 49, 28, 0.08);
  overflow: hidden;
}

.hall-card-progress-fill {
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, rgba(224, 194, 161, 0.9), rgba(182, 118, 57, 0.92));
}

.hall-card-stats {
  margin-top: 18px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.hall-card-stat {
  padding: 14px;
  border-radius: 20px;
  background: rgba(255, 250, 243, 0.72);
  border: 1px solid rgba(72, 49, 28, 0.08);
}

.hall-card-stat strong {
  display: block;
  margin-top: 8px;
  font-family: var(--fc-font);
  font-size: 16px;
  font-weight: 700;
  color: var(--fc-text);
}

.hall-card-stat-wide {
  grid-column: 1 / -1;
}

.hall-card-note {
  margin-top: 16px;
}

.hall-card-actions {
  margin-top: 18px;
}

.hall-state,
.hall-empty,
.hall-more {
  margin-top: 26px;
}

.hall-state,
.hall-empty {
  text-align: center;
}

.hall-state h3,
.hall-empty h3 {
  margin: 12px 0;
  font-size: clamp(30px, 4vw, 42px);
  line-height: 0.98;
  font-weight: 700;
}

.hall-spinner {
  width: 36px;
  height: 36px;
  margin: 0 auto;
  border: 3px solid rgba(72, 49, 28, 0.12);
  border-top-color: var(--fc-accent);
  border-radius: 50%;
  animation: hall-spin 0.7s linear infinite;
}

.hall-error {
  margin-top: 6px;
  color: var(--fc-danger);
}

.hall-more {
  display: flex;
  justify-content: center;
}

@keyframes hall-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 1200px) {
  .hall-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 960px) {
  .hall {
    padding: 20px 18px 30px;
  }

  .hall-top,
  .hall-summary {
    grid-template-columns: 1fr;
  }

  .hall-summary {
    gap: 12px;
  }

  .hall-toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .hall-toolbar-actions {
    justify-content: flex-start;
  }
}

@media (max-width: 720px) {
  .hall-grid,
  .hall-card-stats {
    grid-template-columns: 1fr;
  }

  .hall-card-stat-wide {
    grid-column: auto;
  }

  .hall-card-actions,
  .hall-toolbar-actions {
    flex-direction: column;
    align-items: stretch;
  }

  .hall-card-avatar {
    width: 62px;
    height: 62px;
    border-radius: 20px;
  }

  .hall-card-title {
    font-size: 26px;
  }

  .hall-card-countdown {
    font-size: 34px;
  }
}
</style>
