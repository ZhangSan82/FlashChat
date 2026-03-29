<template>
  <Teleport to="body">
    <transition name="info">
      <div v-if="visible" class="info-overlay" @click.self="$emit('close')">
        <div class="info-panel">
          <div class="info-head">
            <div>
              <div class="info-kicker">Room Detail</div>
              <div class="info-title">房间信息</div>
            </div>
            <button class="info-close" type="button" @click="$emit('close')">×</button>
          </div>

          <div class="info-body">
            <section class="info-hero">
              <div class="info-avatar">{{ roomInitial }}</div>
              <div class="info-name">{{ room?.title || '未命名房间' }}</div>
              <div class="info-meta">
                <span>{{ room?.roomId || '—' }}</span>
                <span>·</span>
                <span>{{ publicText }}</span>
                <span>·</span>
                <span>{{ room?.memberCount || members.length }} / {{ room?.maxMembers || 50 }} 人</span>
              </div>
            </section>

            <section class="info-card">
              <div class="info-card-head">
                <span>房间状态</span>
                <span class="info-badge" :class="statusClass">{{ statusText }}</span>
              </div>
              <div class="info-countdown">{{ countdownText }}</div>
              <div class="info-bar">
                <div class="info-bar-fill" :style="{ width: progressWidth, background: countdownColor }"></div>
              </div>
              <div class="info-meta-grid">
                <div class="info-meta-item">
                  <span>在线人数</span>
                  <strong>{{ room?.onlineCount ?? onlineCount }}</strong>
                </div>
                <div class="info-meta-item">
                  <span>创建时间</span>
                  <strong>{{ createTimeText }}</strong>
                </div>
              </div>
            </section>

            <section class="info-card" v-if="resolvedShareUrl">
              <div class="info-card-head">
                <span>分享房间</span>
                <button class="info-copy" type="button" @click="copyShareUrl">
                  {{ copied ? '已复制' : '复制链接' }}
                </button>
              </div>
              <div class="info-share-grid">
                <div class="info-qr-shell">
                  <img v-if="qrDataUrl" :src="qrDataUrl" alt="room qrcode" class="info-qr" />
                  <div v-else class="info-qr-loading">生成二维码中...</div>
                </div>
                <div class="info-share-copy">
                  <div class="info-share-label">分享链接</div>
                  <input class="info-share-input" :value="resolvedShareUrl" readonly @click="$event.target.select()" />
                  <div class="info-share-hint">复制给朋友，或让对方扫描二维码进入房间。</div>
                </div>
              </div>
            </section>

            <section class="info-card">
              <div class="info-card-head">
                <span>成员</span>
                <span class="info-members-count">{{ members.length }}</span>
              </div>
              <div class="info-members">
                <div v-for="member in members" :key="member._id" class="info-member">
                  <div class="info-member-avatar-wrap">
                    <img v-if="hasAvatarImage(member)" :src="member.avatar" class="info-member-avatar info-member-avatar-img" alt="" />
                    <div v-else class="info-member-avatar" :style="{ background: memberColor(member) }">
                      {{ memberInitial(member) }}
                    </div>
                    <span class="info-member-dot" :class="{ online: member.status?.state === 'online' }"></span>
                  </div>
                  <div class="info-member-copy">
                    <div class="info-member-name">{{ member.username }}</div>
                    <div class="info-member-state">{{ member.status?.state === 'online' ? '在线' : '离线' }}</div>
                  </div>
                  <span v-if="member._raw?.isHost || member._raw?.role === 1" class="info-member-role">房主</span>
                </div>
              </div>
            </section>

            <section class="info-actions">
              <button v-if="isHost" class="info-action" type="button" @click="$emit('extend-room')">
                <strong>延期房间</strong>
                <small>追加时长并同步给所有成员。</small>
              </button>
              <button v-if="isHost" class="info-action" type="button" @click="$emit('resize-room')">
                <strong>扩容房间</strong>
                <small>提高人数上限，容纳更多成员加入。</small>
              </button>
              <button class="info-action" type="button" @click="$emit('leave')">
                <strong>{{ isHost ? '离开房间' : '退出房间' }}</strong>
                <small>离开后仍可通过房间 ID 或分享链接再次加入。</small>
              </button>
              <button v-if="isHost" class="info-action danger" type="button" @click="$emit('close-room')">
                <strong>关闭房间</strong>
                <small>结束本次会话，成员会同步收到关闭状态。</small>
              </button>
            </section>
          </div>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { getShareUrl } from '@/api/room'
import { formatCountdown, calcProgress, getCountdownColor } from '@/utils/formatter'
import { generateQRCodeDataUrl } from '@/utils/qrcode'

const props = defineProps({
  visible: { type: Boolean, default: false },
  room: { type: Object, default: null },
  members: { type: Array, default: () => [] },
  isHost: { type: Boolean, default: false }
})

defineEmits(['close', 'leave', 'close-room', 'extend-room', 'resize-room'])

const now = ref(Date.now())
const resolvedShareUrl = ref('')
const qrDataUrl = ref('')
const copied = ref(false)
let timer = null

onMounted(() => {
  timer = setInterval(() => {
    now.value = Date.now()
  }, 10000)
})

onUnmounted(() => {
  clearInterval(timer)
})

watch(
  () => [props.visible, props.room?.roomId, props.room?.shareUrl, props.room?._raw?.shareUrl],
  async ([visible]) => {
    if (!visible) return
    await ensureShareUrl()
  },
  { immediate: true }
)

watch(resolvedShareUrl, async url => {
  if (!url) {
    qrDataUrl.value = ''
    return
  }
  qrDataUrl.value = (await generateQRCodeDataUrl(url, 200)) || ''
})

const roomInitial = computed(() => (props.room?.title || '?')[0].toUpperCase())
const publicText = computed(() => (props.room?.isPublic === 1 ? '公开房间' : '私密房间'))
const statusText = computed(() => props.room?.statusDesc || '未知状态')
const createTimeText = computed(() => formatDateTime(props.room?.createTime))
const onlineCount = computed(() => props.members.filter(member => member.status?.state === 'online').length)

const countdownText = computed(() => {
  if (!props.room?.expireTime) return '永久不过期'
  const remain = new Date(props.room.expireTime).getTime() - now.value
  return formatCountdown(remain)
})

const countdownColor = computed(() => {
  if (!props.room?.expireTime) return 'var(--fc-success)'
  return getCountdownColor(new Date(props.room.expireTime).getTime() - now.value)
})

const progressWidth = computed(() => {
  if (!props.room?.expireTime || !props.room?.createTime) return '100%'
  return `${Math.round(calcProgress(props.room.expireTime, props.room.createTime) * 100)}%`
})

const statusClass = computed(() => {
  const status = props.room?.status
  if (status === 1) return 'active'
  if (status === 2) return 'warning'
  if (status === 3) return 'danger'
  if (status === 4) return 'muted'
  return 'waiting'
})

async function ensureShareUrl() {
  const existing = props.room?.shareUrl || props.room?._raw?.shareUrl || ''
  if (existing) {
    resolvedShareUrl.value = existing
    return
  }
  if (!props.room?.roomId) {
    resolvedShareUrl.value = ''
    return
  }
  try {
    resolvedShareUrl.value = await getShareUrl(props.room.roomId)
  } catch {
    resolvedShareUrl.value = `${window.location.origin}/room/${props.room.roomId}`
  }
}

async function copyShareUrl() {
  if (!resolvedShareUrl.value) return
  try {
    await navigator.clipboard.writeText(resolvedShareUrl.value)
  } catch {
    const input = document.createElement('input')
    input.value = resolvedShareUrl.value
    document.body.appendChild(input)
    input.select()
    document.execCommand('copy')
    document.body.removeChild(input)
  }
  copied.value = true
  window.setTimeout(() => {
    copied.value = false
  }, 1800)
}

function hasAvatarImage(member) {
  return typeof member?.avatar === 'string' && member.avatar.length > 0 && !member.avatar.startsWith('#')
}

function memberInitial(member) {
  return (member?.username || '?')[0].toUpperCase()
}

function memberColor(member) {
  return member?._raw?.avatar?.startsWith?.('#')
    ? member._raw.avatar
    : (member?.avatar?.startsWith?.('#') ? member.avatar : '#C8956C')
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
.info-overlay {
  position: fixed;
  inset: 0;
  display: flex;
  justify-content: flex-end;
  background: var(--fc-backdrop);
  backdrop-filter: blur(18px);
  z-index: 9500;
}

.info-panel {
  width: 430px;
  max-width: 96vw;
  height: 100%;
  border-left: 1px solid rgba(77, 52, 31, 0.12);
  background:
    radial-gradient(circle at top right, rgba(221, 193, 163, 0.18), transparent 28%),
    linear-gradient(180deg, rgba(255, 250, 243, 0.98), rgba(247, 239, 228, 0.98));
  box-shadow: -20px 0 50px rgba(61, 40, 22, 0.18);
  display: flex;
  flex-direction: column;
}

.info-head {
  padding: 22px 22px 18px;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

.info-kicker,
.info-card-head span:first-child,
.info-share-label {
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.info-title {
  margin-top: 8px;
  font-family: var(--fc-font);
  font-size: 24px;
  font-weight: 700;
  color: var(--fc-text);
}

.info-close {
  width: 40px;
  height: 40px;
  border: 1px solid var(--fc-border);
  border-radius: 50%;
  background: rgba(255, 250, 243, 0.82);
  color: var(--fc-text);
  font-size: 24px;
  cursor: pointer;
}

.info-body {
  flex: 1;
  overflow: auto;
  padding: 0 22px 22px;
}

.info-hero,
.info-card,
.info-action {
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 26px;
  background: rgba(255, 250, 243, 0.78);
  box-shadow: var(--fc-shadow-soft);
}

.info-hero {
  padding: 24px;
  text-align: center;
}

.info-avatar {
  width: 78px;
  height: 78px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: linear-gradient(145deg, #be8c58, #8c5a2b);
  color: #fffaf3;
  font-family: var(--fc-font);
  font-size: 30px;
  font-weight: 700;
}

.info-name {
  margin-top: 16px;
  font-family: var(--fc-font);
  font-size: 24px;
  font-weight: 700;
  color: var(--fc-text);
}

.info-meta {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  font-family: var(--fc-font);
  font-size: 12px;
  color: var(--fc-text-sec);
}

.info-card {
  margin-top: 16px;
  padding: 18px;
}

.info-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.info-badge,
.info-members-count,
.info-member-role {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 5px 12px;
  border-radius: 999px;
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 700;
}

.info-badge.waiting,
.info-members-count {
  background: rgba(243, 231, 215, 0.92);
  color: var(--fc-accent-strong);
}

.info-badge.active {
  background: rgba(235, 245, 230, 0.96);
  color: #42673f;
}

.info-badge.warning {
  background: rgba(255, 242, 224, 0.96);
  color: #8b641c;
}

.info-badge.danger {
  background: rgba(253, 236, 234, 0.96);
  color: #8b3a35;
}

.info-badge.muted {
  background: rgba(233, 225, 217, 0.96);
  color: #7d6c5c;
}

.info-countdown {
  margin-top: 14px;
  font-family: var(--fc-font);
  font-size: 28px;
  font-weight: 700;
  color: var(--fc-text);
}

.info-bar {
  height: 6px;
  margin-top: 12px;
  border-radius: 999px;
  background: rgba(77, 52, 31, 0.08);
}

.info-bar-fill {
  height: 6px;
  border-radius: 999px;
}

.info-meta-grid {
  margin-top: 16px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.info-meta-item {
  padding: 14px;
  border-radius: 20px;
  background: rgba(243, 231, 215, 0.88);
}

.info-meta-item span {
  display: block;
  font-family: var(--fc-font);
  font-size: 11px;
  color: var(--fc-text-muted);
  text-transform: uppercase;
  letter-spacing: 0.12em;
}

.info-meta-item strong {
  display: block;
  margin-top: 8px;
  font-family: var(--fc-font);
  font-size: 18px;
  color: var(--fc-text);
}

.info-copy {
  border: 0;
  background: transparent;
  color: var(--fc-accent-strong);
  font-family: var(--fc-font);
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

.info-share-grid {
  margin-top: 14px;
  display: grid;
  grid-template-columns: 150px minmax(0, 1fr);
  gap: 14px;
  align-items: center;
}

.info-qr-shell {
  min-height: 150px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 22px;
  background: rgba(243, 231, 215, 0.88);
}

.info-qr {
  width: 140px;
  height: 140px;
  border-radius: 16px;
  background: #fffaf3;
  padding: 8px;
}

.info-qr-loading,
.info-share-hint,
.info-member-state,
.info-action small {
  font-family: var(--fc-font);
  font-size: 12px;
  line-height: 1.5;
  color: var(--fc-text-sec);
}

.info-share-input {
  width: 100%;
  margin-top: 10px;
  padding: 12px 14px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 16px;
  background: rgba(243, 231, 215, 0.92);
  color: var(--fc-text-sec);
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-size: 12px;
}

.info-share-hint {
  margin-top: 10px;
}

.info-members {
  margin-top: 14px;
}

.info-member {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid rgba(77, 52, 31, 0.06);
}

.info-member:last-child {
  border-bottom: 0;
}

.info-member-avatar-wrap {
  position: relative;
  flex-shrink: 0;
}

.info-member-avatar {
  width: 42px;
  height: 42px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fffaf3;
  font-family: var(--fc-font);
  font-size: 15px;
  font-weight: 700;
}

.info-member-avatar-img {
  object-fit: cover;
}

.info-member-dot {
  position: absolute;
  right: -2px;
  bottom: -2px;
  width: 12px;
  height: 12px;
  border: 2px solid #fffaf3;
  border-radius: 50%;
  background: #b9aa99;
}

.info-member-dot.online {
  background: var(--fc-success);
}

.info-member-copy {
  min-width: 0;
  flex: 1;
}

.info-member-name {
  font-family: var(--fc-font);
  font-size: 15px;
  font-weight: 600;
  color: var(--fc-text);
}

.info-member-role {
  background: rgba(243, 231, 215, 0.92);
  color: var(--fc-accent-strong);
}

.info-actions {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.info-action {
  padding: 16px;
  text-align: left;
  cursor: pointer;
}

.info-action strong {
  display: block;
  font-family: var(--fc-font);
  font-size: 15px;
  color: var(--fc-text);
}

.info-action small {
  display: block;
  margin-top: 6px;
}

.info-action.danger strong,
.info-action.danger small {
  color: var(--fc-danger);
}

@media (max-width: 640px) {
  .info-panel {
    width: 100%;
  }

  .info-share-grid,
  .info-meta-grid {
    grid-template-columns: 1fr;
  }

  .info-qr-shell {
    min-height: 170px;
  }
}
</style>
