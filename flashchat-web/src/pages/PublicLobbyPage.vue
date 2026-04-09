<template>
  <div class="hall">
    <div class="hall-content">
      <header class="hall-hero">
        <div class="hall-hero-inner">
          <button class="hall-back" type="button" @click="goRoomList">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M10 13L5 8l5-5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
            返回
          </button>
          <div class="hall-hero-body">
            <div class="hall-hero-left">
              <span class="hall-label">Public Lobby</span>
              <h1>发现公开房间</h1>
              <p>浏览正在进行中的对话，查看氛围与人数，随时加入。</p>
            </div>
            <div class="hall-hero-stats">
              <div class="hall-hero-stat">
                <strong>{{ totalRooms }}</strong>
                <span>开放中</span>
              </div>
              <div class="hall-hero-stat-divider"></div>
              <div class="hall-hero-stat">
                <strong>{{ totalOnline }}</strong>
                <span>在线</span>
              </div>
              <div class="hall-hero-stat-divider"></div>
              <div class="hall-hero-stat">
                <strong>{{ occupancySummary }}</strong>
                <span>席位</span>
              </div>
            </div>
          </div>
        </div>
      </header>

      <section class="hall-bar">
        <div class="hall-tabs">
          <button
            v-for="option in sortOptions"
            :key="option.value"
            type="button"
            class="hall-tab"
            :class="{ active: sort === option.value }"
            @click="switchSort(option.value)"
          >
            {{ option.label }}
          </button>
        </div>
        <button class="hall-create" type="button" @click="goCreateRoom">
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M8 3v10M3 8h10" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>
          创建房间
        </button>
      </section>

      <section v-if="loading && rooms.length === 0" class="hall-state">
        <div class="hall-spinner"></div>
        <h3>正在加载公开房间</h3>
        <p>房间状态与在线人数即将同步完成。</p>
      </section>

      <section v-else-if="error" class="hall-state">
        <h3>加载失败</h3>
        <p class="hall-error">{{ error }}</p>
        <button class="hall-action-btn" type="button" @click="loadRooms(true)">重新加载</button>
      </section>

      <section v-else-if="rooms.length === 0" class="hall-state">
        <span class="hall-label">Quiet Moment</span>
        <h3>大厅暂时没有房间</h3>
        <p>创建一个公开房间，等待其他人加入。</p>
        <button class="hall-create" type="button" @click="goCreateRoom">创建公开房间</button>
      </section>

      <section v-else class="hall-grid">
        <article v-for="room in rooms" :key="room.roomId" class="hall-card" @click="goPreview(room)">
          <div class="hall-card-head">
            <img class="hall-card-avatar" :src="getRoomVisualUrl(room)" :alt="getRoomDisplayName(room)" />
            <div class="hall-card-meta">
              <div class="hall-card-title">{{ getRoomDisplayName(room) }}</div>
              <div class="hall-card-id">{{ room.roomId }}</div>
            </div>
            <span class="hall-badge" :class="statusClass(room.status)">{{ room.statusDesc || '活跃' }}</span>
          </div>

          <div class="hall-card-body">
            <div class="hall-card-timer">
              <span class="hall-card-timer-label">剩余时间</span>
              <span class="hall-card-timer-value" :style="{ color: getCountdownColor(countdownMs(room)) }">
                {{ room.expireTime ? formatCountdown(countdownMs(room)) : '永久' }}
              </span>
            </div>
            <div class="hall-card-progress">
              <div class="hall-card-progress-fill" :style="{ width: `${occupancyPercent(room)}%` }"></div>
            </div>
            <div class="hall-card-info">
              <span>{{ room.onlineCount || 0 }} 在线</span>
              <span>{{ room.memberCount || 0 }}/{{ room.maxMembers || 50 }} 席位</span>
              <span>{{ formatDateTime(room.createTime) }}</span>
            </div>
          </div>

          <div class="hall-card-foot">
            <button class="hall-card-btn ghost" type="button" @click.stop="copyShare(room)">
              {{ copyingRoomId === room.roomId ? '已复制' : '复制链接' }}
            </button>
            <button class="hall-card-btn primary" type="button" @click.stop="goPreview(room)">进入预览</button>
          </div>
        </article>
      </section>

      <div v-if="rooms.length > 0" class="hall-more">
        <button class="hall-action-btn" type="button" :disabled="loading || !hasMore" @click="loadRooms(false)">
          {{ hasMore ? (loading ? '加载中...' : '加载更多') : '没有更多了' }}
        </button>
      </div>
    </div>

    <!-- 鈺愨晲鈺?Preview Sheet Overlay 鈺愨晲鈺?-->
    <transition name="hall-overlay">
      <div v-if="previewRoom" class="hall-overlay" @click.self="closePreview">
        <transition name="hall-sheet" appear>
          <div v-if="previewRoom" class="hall-sheet">
            <button class="hall-sheet-close" type="button" @click="closePreview">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            </button>

            <div class="hall-sheet-hero">
              <img class="hall-sheet-cover" :src="getRoomVisualUrl(previewRoom)" :alt="getRoomDisplayName(previewRoom)" />
              <div class="hall-sheet-label">房间预览</div>
              <div class="hall-sheet-title">{{ getRoomDisplayName(previewRoom) }}</div>
              <div class="hall-sheet-id">{{ previewRoom.roomId }}</div>
            </div>

            <div class="hall-sheet-divider"></div>

            <div class="hall-sheet-info">
              <div class="hall-sheet-info-label">房间信息</div>
              <div class="hall-sheet-stats">
                <div class="hall-sheet-stat">
                  <span class="hall-sheet-dot" :style="{ background: previewStatusColor }"></span>
                  <span>{{ previewRoom.statusDesc || '开放中' }}</span>
                </div>
                <div class="hall-sheet-stat">
                  <span>⏳ {{ previewCountdown }}</span>
                </div>
                <div class="hall-sheet-stat">
                  <span>{{ previewRoom.memberCount || 0 }} / {{ previewRoom.maxMembers || 50 }} 人</span>
                </div>
              </div>
            </div>

            <div class="hall-sheet-recent">
              <div class="hall-sheet-recent-header">
                <div class="hall-sheet-info-label">最近消息</div>
                <span v-if="previewMessages.length > 0" class="hall-sheet-msg-count">{{ previewMessages.length }} 条</span>
              </div>
              <div v-if="previewMessages.length > 0" class="hall-sheet-msg-list">
                <div
                  v-for="(item, index) in previewMessages"
                  :key="item.indexId != null ? 'preview-' + item.indexId : 'preview-' + index + '-' + (item.timestamp || '')"
                  class="hall-sheet-msg-item"
                >
                  <img class="hall-sheet-msg-avatar" :src="previewAvatarOf(item)" :alt="item.username || '匿名用户'" />
                  <div class="hall-sheet-msg-bubble">
                    <div class="hall-sheet-msg-head">
                      <span class="hall-sheet-msg-user">{{ item.username || '匿名用户' }}</span>
                      <span class="hall-sheet-msg-time">{{ formatPreviewTime(item.timestamp) }}</span>
                    </div>
                    <div class="hall-sheet-msg-content">{{ item.content }}</div>
                  </div>
                </div>
                <div class="hall-sheet-msg-fade"></div>
              </div>
              <div v-else class="hall-sheet-empty-shell">
                <div v-if="previewLoading || previewMembersLoading" class="hall-sheet-empty-loading">
                  <span class="hall-sheet-mini-spinner"></span>
                  <span>{{ previewLoading ? '正在加载最近消息...' : '正在同步该房间成员...' }}</span>
                </div>
                <template v-else>
                  <div class="hall-sheet-empty-icon">
                    <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
                  </div>
                  <div v-if="previewMemberSamples.length > 0" class="hall-sheet-member-row">
                    <img
                      v-for="member in previewMemberSamples"
                      :key="'preview-member-' + member.accountId"
                      class="hall-sheet-member-avatar"
                      :src="member.avatar"
                      :alt="member.nickname"
                    />
                  </div>
                  <p class="hall-sheet-msg-empty">暂无可预览消息<br/>进入房间可查看完整聊天内容</p>
                </template>
              </div>
            </div>

            <button
              class="hall-sheet-join"
              :class="{ joining: joining }"
              :disabled="joining"
              @click="doJoinRoom"
            >
              <span v-if="!joining">进入房间</span>
              <span v-else class="hall-sheet-join-loading">
                <span class="hall-sheet-spinner"></span>
                加入中...
              </span>
            </button>

            <transition name="hall-sheet-err">
              <p v-if="joinError" class="hall-sheet-error">{{ joinError }}</p>
            </transition>
          </div>
        </transition>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '@/composables/useAuth'
import { listPublicRooms, joinRoom, getRoomMembers, previewRoom as fetchRoomPreview } from '@/api/room'
import { formatCountdown, getCountdownColor } from '@/utils/formatter'
import { getRoomDisplayName, getRoomVisualUrl } from '@/utils/roomVisual'
import { getRoomPreviewMessages } from '@/utils/roomPreviewCache'
import { resolveBackendAvatar } from '@/utils/avatar'

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
const now = ref(Date.now())

const previewRoom = ref(null)
const previewMessages = ref([])
const previewMembers = ref([])
const previewLoading = ref(false)
const previewMembersLoading = ref(false)
const joining = ref(false)
const joinError = ref('')

const sortOptions = [
  { value: 'hot', label: '最热' },
  { value: 'newest', label: '最新' },
  { value: 'expiring', label: '即将到期' }
]

const totalRooms = computed(() => rooms.value.length)
const totalOnline = computed(() => rooms.value.reduce((sum, room) => sum + Number(room.onlineCount || 0), 0))
const totalMembers = computed(() => rooms.value.reduce((sum, room) => sum + Number(room.memberCount || 0), 0))
const totalCapacity = computed(() => rooms.value.reduce((sum, room) => sum + Number(room.maxMembers || 0), 0))
const occupancySummary = computed(() => {
  if (!totalCapacity.value) return '0%'
  return `${Math.round((totalMembers.value / totalCapacity.value) * 100)}%`
})

let timer = null

onMounted(async () => {
  timer = window.setInterval(() => {
    now.value = Date.now()
  }, 30000)

  try {
    await auth.init()
  } catch {
    // identity not required for public lobby
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
  previewRoom.value = room
  previewMessages.value = getRoomPreviewMessages(room?.roomId, 10)
  previewMembers.value = []
  previewLoading.value = false
  loadPreviewMembers(room?.roomId)
  loadPreviewRoomDetail(room?.roomId)
  joining.value = false
  joinError.value = ''
}

function closePreview() {
  previewRoom.value = null
  previewMessages.value = []
  previewMembers.value = []
  previewLoading.value = false
  previewMembersLoading.value = false
  joining.value = false
  joinError.value = ''
}

function normalizePreviewMessages(list, limit = 10) {
  if (!Array.isArray(list)) return []
  return list
    .filter(Boolean)
    .slice(0, limit)
    .map((item) => {
      const rawText = String(item.content || '').replace(/\s+/g, ' ').trim()
      const normalizedText = rawText.length > 120 ? `${rawText.slice(0, 120)}...` : rawText
      const rawTs = Number(item.timestamp)
      const parsedTs = Number.isFinite(rawTs) ? rawTs : new Date(item.timestamp).getTime()

      return {
        indexId: item.indexId ?? null,
        senderId: item.senderId != null ? String(item.senderId) : '',
        username: String(item.username || '').trim(),
        avatar: String(item.avatar || '').trim(),
        content: normalizedText || '[文件]',
        timestamp: Number.isFinite(parsedTs) ? parsedTs : Date.now()
      }
    })
}

async function loadPreviewRoomDetail(roomId) {
  if (!roomId) return
  previewLoading.value = true
  try {
    const detail = await fetchRoomPreview(roomId)
    if (!previewRoom.value || previewRoom.value.roomId !== roomId) return

    if (detail && typeof detail === 'object') {
      previewRoom.value = { ...previewRoom.value, ...detail }
    }

    const latest = normalizePreviewMessages(detail?.recentMessages, 10)
    if (latest.length > 0) {
      previewMessages.value = latest
    } else if (previewMessages.value.length === 0) {
      previewMessages.value = getRoomPreviewMessages(roomId, 10)
    }
  } catch {
    if (previewRoom.value?.roomId === roomId && previewMessages.value.length === 0) {
      previewMessages.value = getRoomPreviewMessages(roomId, 10)
    }
  } finally {
    if (previewRoom.value?.roomId === roomId) {
      previewLoading.value = false
    }
  }
}

async function loadPreviewMembers(roomId) {
  if (!roomId) {
    previewMembers.value = []
    previewMembersLoading.value = false
    return
  }
  previewMembersLoading.value = true
  try {
    const list = await getRoomMembers(roomId)
    previewMembers.value = Array.isArray(list) ? list : []
  } catch {
    previewMembers.value = []
  } finally {
    previewMembersLoading.value = false
  }
}

const previewMemberAvatarMap = computed(() => {
  const map = new Map()
  previewMembers.value.forEach((member) => {
    const key = member?.accountId != null ? String(member.accountId) : ''
    if (!key) return
    map.set(key, resolveBackendAvatar(member.avatar, member.nickname))
  })
  return map
})

const previewMemberSamples = computed(() => {
  return previewMembers.value.slice(0, 8).map(member => ({
    accountId: member.accountId,
    nickname: member.nickname || '成员',
    avatar: resolveBackendAvatar(member.avatar, member.nickname || '成员')
  }))
})

function previewAvatarOf(item) {
  if (item?.avatar) {
    return resolveBackendAvatar(item.avatar, item?.username || '匿名用户')
  }
  const senderId = item?.senderId != null ? String(item.senderId) : ''
  if (senderId && previewMemberAvatarMap.value.has(senderId)) {
    return previewMemberAvatarMap.value.get(senderId)
  }
  return resolveBackendAvatar('', item?.username || '匿名用户')
}

const previewCountdown = computed(() => {
  if (!previewRoom.value?.expireTime) return '永久不过期'
  const remain = new Date(previewRoom.value.expireTime).getTime() - now.value
  if (remain <= 0) return '已过期'
  return formatCountdown(remain)
})

const previewStatusColor = computed(() => {
  if (!previewRoom.value) return '#B68450'
  const s = previewRoom.value.status
  if (s === 1) return '#7BAF6E'
  if (s === 2) return '#D4A84C'
  if (s === 3 || s === 4) return '#D4736C'
  return '#B68450'
})

let joinErrorTimer = null
async function doJoinRoom() {
  if (!previewRoom.value || joining.value) return
  joining.value = true
  joinError.value = ''
  clearTimeout(joinErrorTimer)

  try {
    await joinRoom({ roomId: previewRoom.value.roomId })
    sessionStorage.setItem('fc_last_room', previewRoom.value.roomId)
    router.push('/')
  } catch (e) {
    joinError.value = e.message || '加入房间失败，请重试'
    joinErrorTimer = setTimeout(() => { joinError.value = '' }, 4000)
  } finally {
    joining.value = false
  }
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

function formatPreviewTime(value) {
  if (!value) return '--:--'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '--:--'
  return date.toLocaleTimeString('zh-CN', {
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
/* 鈺愨晲鈺?Root 鈺愨晲鈺?*/
.hall {
  min-height: 100vh;
  background: var(--fc-bg);
  color: var(--fc-text);
}

/* 鈺愨晲鈺?Content 鈺愨晲鈺?*/
.hall-content {
  max-width: 1200px;
  margin: 0 auto;
  padding: 40px 40px 60px;
}

/* 鈺愨晲鈺?Hero 鈺愨晲鈺?*/
.hall-hero {
  margin-bottom: 48px;
}

.hall-hero-inner {
  padding: 0;
}

.hall-back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: var(--fc-text-muted);
  font-family: var(--fc-font);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: color 0.2s ease;
  margin-bottom: 48px;
}

.hall-back:hover {
  color: var(--fc-text);
}

.hall-back svg {
  flex-shrink: 0;
}

.hall-hero-body {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 32px;
}

.hall-hero-left {
  max-width: 520px;
}

.hall-label {
  display: inline-block;
  font-family: var(--fc-font);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--fc-accent);
}

.hall-hero h1 {
  margin: 16px 0 0;
  font-family: var(--fc-font-display);
  font-size: clamp(32px, 4vw, 48px);
  line-height: 1.1;
  font-weight: 600;
  color: var(--fc-text);
  letter-spacing: -0.015em;
}

.hall-hero p {
  margin: 12px 0 0;
  font-family: var(--fc-font);
  font-size: 16px;
  line-height: 1.6;
  color: var(--fc-text-muted);
  max-width: 480px;
}

.hall-hero-stats {
  display: flex;
  align-items: center;
  gap: 32px;
  flex-shrink: 0;
}

.hall-hero-stat {
  display: flex;
  flex-direction: column;
}

.hall-hero-stat strong {
  font-family: var(--fc-font-display);
  font-size: 28px;
  font-weight: 700;
  color: var(--fc-text);
  line-height: 1;
}

.hall-hero-stat span {
  margin-top: 4px;
  font-family: var(--fc-font);
  font-size: 13px;
  color: var(--fc-text-muted);
}

.hall-hero-stat-divider {
  width: 1px;
  height: 36px;
  background: var(--fc-border);
  flex-shrink: 0;
}

/* 鈺愨晲鈺?Filter Bar 鈺愨晲鈺?*/
.hall-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 28px;
  gap: 16px;
}

.hall-tabs {
  display: flex;
  gap: 4px;
  padding: 4px;
  background: var(--fc-bg-dark);
  border-radius: 999px;
}

.hall-tab {
  padding: 8px 18px;
  border: 1px solid transparent;
  border-radius: 999px;
  background: transparent;
  font-family: var(--fc-font);
  font-size: 13px;
  font-weight: 500;
  color: var(--fc-text-muted);
  cursor: pointer;
  transition: all 0.2s ease;
}

.hall-tab:hover {
  color: var(--fc-text-sec);
}

.hall-tab.active {
  background: var(--fc-selected-bg);
  border-color: var(--fc-selected-border);
  color: var(--fc-selected-text);
  box-shadow: var(--fc-selected-shadow);
  font-weight: 600;
}

.hall-create {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 10px 20px;
  border: none;
  border-radius: 999px;
  background: var(--fc-accent);
  color: #fff;
  font-family: var(--fc-font);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.2s ease;
}

.hall-create:hover {
  background: var(--fc-accent-strong);
}

.hall-create svg {
  flex-shrink: 0;
}

/* 鈺愨晲鈺?Grid 鈺愨晲鈺?*/
.hall-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

/* 鈺愨晲鈺?Card 鈺愨晲鈺?*/
.hall-card {
  border-radius: var(--fc-radius-lg);
  border: 1px solid var(--fc-border);
  background: var(--fc-surface);
  padding: 24px;
  cursor: pointer;
  transition: border-color 0.25s ease, box-shadow 0.25s ease;
}

.hall-card:hover {
  border-color: var(--fc-border-strong);
  box-shadow: var(--fc-shadow-soft);
}

.hall-card-head {
  display: flex;
  align-items: center;
  gap: 14px;
}

.hall-card-avatar {
  width: 48px;
  height: 48px;
  border-radius: 14px;
  object-fit: cover;
  background: linear-gradient(135deg, #E8D5BF, #C9A87C);
  flex-shrink: 0;
}

.hall-card-meta {
  min-width: 0;
  flex: 1;
}

.hall-card-title {
  font-family: var(--fc-font-display);
  font-size: 16px;
  font-weight: 600;
  color: var(--fc-text);
  line-height: 1.25;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.hall-card-id {
  margin-top: 2px;
  font-family: var(--fc-font-mono);
  font-size: 12px;
  color: var(--fc-text-muted);
  letter-spacing: 0.04em;
}

.hall-badge {
  flex-shrink: 0;
  padding: 4px 10px;
  border-radius: 999px;
  font-family: var(--fc-font);
  font-size: 12px;
  font-weight: 500;
}

.hall-badge.waiting {
  background: var(--fc-accent-veil);
  color: var(--fc-accent);
}

.hall-badge.active {
  background: rgba(82, 122, 77, 0.14);
  border: 1px solid rgba(82, 122, 77, 0.28);
  color: var(--fc-success);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.72);
}

.hall-badge.warning {
  background: rgba(158, 110, 31, 0.10);
  color: var(--fc-warn);
}

.hall-card-body {
  margin-top: 20px;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.hall-card-timer-label {
  font-family: var(--fc-font);
  font-size: 13px;
  color: var(--fc-text-muted);
}

.hall-card-timer-value {
  font-family: var(--fc-font-display);
  font-size: 22px;
  font-weight: 700;
  color: var(--fc-accent);
  line-height: 1;
  letter-spacing: -0.01em;
}

.hall-card-progress {
  margin-top: 14px;
  height: 3px;
  border-radius: 999px;
  background: var(--fc-bg-dark);
  overflow: hidden;
}

.hall-card-progress-fill {
  height: 100%;
  border-radius: inherit;
  background: var(--fc-accent);
  opacity: 0.6;
}

.hall-card-info {
  margin-top: 12px;
  display: flex;
  gap: 16px;
  font-family: var(--fc-font);
  font-size: 13px;
  color: var(--fc-text-muted);
}

/* 鈺愨晲鈺?Card Footer 鈺愨晲鈺?*/
.hall-card-foot {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--fc-border);
  display: flex;
  gap: 8px;
}

.hall-card-btn {
  flex: 1;
  padding: 10px 16px;
  border-radius: 12px;
  font-family: var(--fc-font);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
  text-align: center;
}

.hall-card-btn.ghost {
  background: var(--fc-surface);
  border: 1px solid var(--fc-border);
  color: var(--fc-text);
}

.hall-card-btn.ghost:hover {
  border-color: var(--fc-border-strong);
  background: var(--fc-bg-dark);
}

.hall-card-btn.primary {
  background: var(--fc-accent);
  border: none;
  color: #fff;
}

.hall-card-btn.primary:hover {
  background: var(--fc-accent-strong);
}

/* 鈺愨晲鈺?States 鈺愨晲鈺?*/
.hall-state {
  margin-top: 48px;
  text-align: center;
  padding: 64px 24px;
}

.hall-state h3 {
  margin: 16px 0 8px;
  font-family: var(--fc-font-display);
  font-size: 22px;
  font-weight: 600;
  color: var(--fc-text);
}

.hall-state p {
  margin: 0;
  font-family: var(--fc-font);
  font-size: 14px;
  color: var(--fc-text-muted);
  line-height: 1.6;
}

.hall-error {
  color: var(--fc-danger);
}

.hall-action-btn {
  margin-top: 20px;
  padding: 10px 20px;
  border: 1px solid var(--fc-border);
  border-radius: 999px;
  background: var(--fc-surface);
  color: var(--fc-text);
  font-family: var(--fc-font);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
}

.hall-action-btn:hover {
  border-color: var(--fc-border-strong);
}

.hall-action-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.hall-spinner {
  width: 32px;
  height: 32px;
  margin: 0 auto;
  border: 2px solid var(--fc-border);
  border-top-color: var(--fc-accent);
  border-radius: 50%;
  animation: hall-spin 0.7s linear infinite;
}

.hall-more {
  margin-top: 28px;
  display: flex;
  justify-content: center;
}

@keyframes hall-spin {
  to { transform: rotate(360deg); }
}

/* 鈺愨晲鈺?Preview Sheet Overlay 鈺愨晲鈺?*/
.hall-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 16px;
  background: var(--fc-backdrop);
}

.hall-overlay-enter-active { transition: opacity 0.3s ease; }
.hall-overlay-leave-active { transition: opacity 0.22s ease; }
.hall-overlay-enter-from,
.hall-overlay-leave-to { opacity: 0; }

.hall-sheet {
  width: 100%;
  max-width: 400px;
  background: var(--fc-surface);
  border: 1px solid var(--fc-border);
  border-radius: var(--fc-radius-lg);
  box-shadow: var(--fc-shadow-panel);
  padding: 36px 32px 28px;
  position: relative;
}

.hall-sheet-enter-active {
  transition: opacity 0.35s ease, transform 0.35s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.hall-sheet-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}
.hall-sheet-enter-from {
  opacity: 0;
  transform: translateY(24px) scale(0.96);
}
.hall-sheet-leave-to {
  opacity: 0;
  transform: translateY(12px) scale(0.98);
}

.hall-sheet-close {
  position: absolute;
  top: 16px;
  right: 16px;
  width: 36px;
  height: 36px;
  border: 1px solid var(--fc-border);
  border-radius: 50%;
  background: var(--fc-surface);
  color: var(--fc-text-sec);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.22s ease;
  z-index: 2;
}

.hall-sheet-close:hover {
  border-color: var(--fc-border-strong);
  color: var(--fc-text);
}

.hall-sheet-hero {
  text-align: center;
}

.hall-sheet-cover {
  width: 88px;
  height: 88px;
  border-radius: 24px;
  object-fit: cover;
  margin: 0 auto 16px;
  background: linear-gradient(135deg, #E8D5BF, #C9A87C);
}

.hall-sheet-label {
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
  margin-bottom: 10px;
}

.hall-sheet-title {
  font-family: var(--fc-font-display);
  font-size: 24px;
  font-weight: 600;
  color: var(--fc-text);
  line-height: 1.15;
  word-break: break-word;
}

.hall-sheet-id {
  margin-top: 4px;
  font-family: var(--fc-font-mono);
  font-size: 12px;
  color: var(--fc-text-muted);
  letter-spacing: 0.1em;
}

.hall-sheet-divider {
  height: 1px;
  margin: 20px 0;
  background: var(--fc-border);
}

.hall-sheet-info-label {
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
  text-align: center;
  margin-bottom: 14px;
}

.hall-sheet-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  justify-content: center;
  font-size: 13px;
  color: var(--fc-text-sec);
}

.hall-sheet-stat {
  display: flex;
  align-items: center;
  gap: 6px;
}

.hall-sheet-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.hall-sheet-info {
  margin-bottom: 14px;
}

.hall-sheet-recent {
  border: 1px solid var(--fc-border);
  border-radius: 16px;
  background: var(--fc-bg);
  padding: 14px;
  position: relative;
}

.hall-sheet-recent-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.hall-sheet-recent-header .hall-sheet-info-label {
  margin-bottom: 0;
}

.hall-sheet-msg-count {
  font-size: 11px;
  font-weight: 500;
  color: var(--fc-text-muted);
  background: var(--fc-surface);
  border: 1px solid var(--fc-border);
  border-radius: 999px;
  padding: 2px 8px;
  letter-spacing: 0.02em;
}

.hall-sheet-msg-list {
  display: grid;
  gap: 6px;
  max-height: 260px;
  overflow-y: auto;
  overflow-x: hidden;
  padding-right: 4px;
  position: relative;
  scrollbar-width: thin;
  scrollbar-color: var(--fc-border) transparent;
}

.hall-sheet-msg-list::-webkit-scrollbar {
  width: 4px;
}

.hall-sheet-msg-list::-webkit-scrollbar-track {
  background: transparent;
}

.hall-sheet-msg-list::-webkit-scrollbar-thumb {
  background: var(--fc-border);
  border-radius: 999px;
}

.hall-sheet-msg-list::-webkit-scrollbar-thumb:hover {
  background: var(--fc-border-strong);
}

.hall-sheet-msg-fade {
  position: sticky;
  bottom: 0;
  left: 0;
  right: 0;
  height: 24px;
  background: linear-gradient(to top, var(--fc-bg), transparent);
  pointer-events: none;
  margin-top: -24px;
}

.hall-sheet-msg-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 12px;
  transition: background 0.18s ease;
}

.hall-sheet-msg-item:hover {
  background: var(--fc-bg-dark);
}

.hall-sheet-msg-avatar {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border-radius: 50%;
  object-fit: cover;
  display: block;
  border: 1.5px solid var(--fc-border);
  background: var(--fc-surface);
  box-shadow: 0 1px 3px rgba(77, 52, 31, 0.06);
}

.hall-sheet-msg-bubble {
  min-width: 0;
  flex: 1;
}

.hall-sheet-msg-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 2px;
}

.hall-sheet-msg-user {
  font-size: 12px;
  font-weight: 600;
  color: var(--fc-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 120px;
}

.hall-sheet-msg-time {
  flex-shrink: 0;
  font-size: 10px;
  color: var(--fc-text-muted);
  font-variant-numeric: tabular-nums;
  opacity: 0.7;
  margin-left: auto;
}

.hall-sheet-msg-content {
  font-size: 12.5px;
  line-height: 1.5;
  color: var(--fc-text-sec);
  word-break: break-word;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.hall-sheet-empty-shell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 16px 8px 8px;
}

.hall-sheet-empty-icon {
  color: var(--fc-border-strong);
  opacity: 0.5;
  margin-bottom: 2px;
}

.hall-sheet-empty-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 16px 8px;
  font-size: 12px;
  color: var(--fc-text-muted);
}

.hall-sheet-mini-spinner {
  width: 14px;
  height: 14px;
  border: 1.5px solid var(--fc-border);
  border-top-color: var(--fc-accent);
  border-radius: 50%;
  animation: hall-spin 0.7s linear infinite;
  flex-shrink: 0;
}

.hall-sheet-member-row {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 4px;
}

.hall-sheet-member-avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  object-fit: cover;
  border: 2px solid var(--fc-surface);
  background: var(--fc-surface);
  box-shadow: 0 1px 3px rgba(77, 52, 31, 0.08);
  margin-left: -6px;
}

.hall-sheet-member-avatar:first-child {
  margin-left: 0;
}

.hall-sheet-msg-empty {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--fc-text-muted);
  text-align: center;
}

.hall-sheet-join {
  margin-top: 16px;
  width: 100%;
  padding: 14px;
  border: none;
  border-radius: 14px;
  background: var(--fc-accent);
  color: #fff;
  font-family: var(--fc-font);
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s ease;
}

.hall-sheet-join:hover:not(:disabled) {
  background: var(--fc-accent-strong);
}

.hall-sheet-join:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.hall-sheet-join-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.hall-sheet-spinner {
  width: 18px;
  height: 18px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: hall-spin 0.6s linear infinite;
}

.hall-sheet-error {
  text-align: center;
  margin-top: 12px;
  font-size: 13px;
  font-weight: 500;
  color: var(--fc-danger);
  background: rgba(184, 96, 75, 0.06);
  border: 1px solid rgba(184, 96, 75, 0.12);
  border-radius: 12px;
  padding: 10px 16px;
}

.hall-sheet-err-enter-active { transition: all 0.3s ease; }
.hall-sheet-err-leave-active { transition: all 0.25s ease; }
.hall-sheet-err-enter-from { opacity: 0; transform: translateY(-6px); }
.hall-sheet-err-leave-to { opacity: 0; transform: translateY(-6px); }

/* 鈺愨晲鈺?Responsive 鈺愨晲鈺?*/
@media (max-width: 1200px) {
  .hall-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 900px) {
  .hall-content {
    padding: 24px 20px 40px;
  }

  .hall-hero-body {
    flex-direction: column;
    align-items: flex-start;
    gap: 24px;
  }

  .hall-hero-stats {
    width: 100%;
    justify-content: flex-start;
  }

  .hall-bar {
    flex-direction: column;
    align-items: stretch;
    gap: 12px;
  }

  .hall-tabs {
    align-self: center;
  }

  .hall-create {
    justify-content: center;
  }
}

@media (max-width: 640px) {
  .hall-grid {
    grid-template-columns: 1fr;
    gap: 12px;
  }

  .hall-hero h1 {
    font-size: 28px;
  }

  .hall-hero-stat strong {
    font-size: 22px;
  }

  .hall-hero-stats {
    gap: 20px;
  }

  .hall-card-timer-value {
    font-size: 18px;
  }

  .hall-overlay {
    align-items: flex-end;
    padding: 0;
  }

  .hall-sheet {
    max-width: 100%;
    border-radius: var(--fc-radius-lg) var(--fc-radius-lg) 0 0;
    padding: 28px 20px 24px;
  }

  .hall-sheet-enter-from,
  .hall-sheet-leave-to {
    transform: translateY(100%);
    opacity: 0;
  }

  .hall-sheet-cover {
    width: 72px;
    height: 72px;
    border-radius: 20px;
  }

  .hall-sheet-title {
    font-size: 20px;
  }

  .hall-sheet-msg-list {
    max-height: 200px;
  }

  .hall-sheet-msg-item {
    padding: 5px 6px;
  }
}
</style>

