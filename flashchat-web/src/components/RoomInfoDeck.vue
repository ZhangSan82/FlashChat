<template>
  <Teleport to="body">
    <transition name="info">
      <div v-if="visible" class="info-overlay" @click.self="emit('close')">
        <aside class="info-panel">
          <header class="info-head">
            <div>
              <div class="info-kicker">Room Salon</div>
              <div class="info-title">房间名片</div>
            </div>
            <button class="info-close" type="button" @click="emit('close')">×</button>
          </header>

          <div class="info-body">
            <section class="info-hero">
              <div class="info-hero-cover" :style="heroCoverStyle">
                <div class="info-hero-mist"></div>
                <div class="info-hero-content">
                  <span class="info-status-pill" :class="statusClass">{{ statusText }}</span>
                  <div class="info-avatar-ring">
                    <img class="info-avatar" :src="roomVisualUrl" :alt="roomName" />
                  </div>
                  <div class="info-name">{{ roomName }}</div>
                  <div class="info-meta">
                    <span>{{ room?.roomId || '--' }}</span>
                    <span>{{ publicText }}</span>
                    <span>{{ memberCount }} / {{ room?.maxMembers || 50 }} 人</span>
                  </div>
                </div>
              </div>

              <div class="info-summary">
                <div class="info-summary-item">
                  <span>剩余时间</span>
                  <strong :style="{ color: countdownColor }">{{ countdownText }}</strong>
                </div>
                <div class="info-summary-divider"></div>
                <div class="info-summary-item">
                  <span>在线成员</span>
                  <strong>{{ room?.onlineCount ?? onlineCount }}</strong>
                </div>
              </div>
            </section>

            <section class="info-card">
              <div class="info-card-head">
                <span>房间状态</span>
                <span class="info-badge" :class="statusClass">{{ statusText }}</span>
              </div>
              <div class="info-countdown-row">
                <div class="info-countdown">{{ countdownText }}</div>
                <div class="info-create-time">创建于 {{ createTimeText }}</div>
              </div>
              <div class="info-bar">
                <div class="info-bar-fill" :style="{ width: progressWidth, background: countdownColor }"></div>
              </div>
              <div class="info-meta-grid">
                <div class="info-meta-item">
                  <span>房间类型</span>
                  <strong>{{ publicText }}</strong>
                </div>
                <div class="info-meta-item">
                  <span>人数上限</span>
                  <strong>{{ room?.maxMembers || 50 }} 人</strong>
                </div>
              </div>
            </section>

            <section v-if="isHost" class="info-card">
              <div class="info-card-head">
                <span>房间头像</span>
                <span class="info-badge waiting">{{ room?.avatarUrl ? '已设置' : '默认' }}</span>
              </div>
              <div class="info-avatar-edit">
                <div class="info-avatar-edit-preview">
                  <img :src="roomVisualUrl" :alt="roomName" />
                </div>
                <div class="info-avatar-edit-actions">
                  <button class="info-mini-btn" type="button" :disabled="avatarUploading" @click="triggerRoomAvatarUpload">
                    {{ avatarUploading ? '上传中...' : '上传/更换' }}
                  </button>
                  <button
                    class="info-mini-btn"
                    type="button"
                    :disabled="avatarUploading || !room?.avatarUrl"
                    @click="clearRoomAvatar"
                  >
                    清除头像
                  </button>
                  <div class="info-avatar-edit-hint">请尽量使用 1:1 图片，建议 5MB 以内</div>
                  <div v-if="avatarUploadError" class="info-avatar-edit-error">{{ avatarUploadError }}</div>
                </div>
                <input
                  ref="avatarInputRef"
                  type="file"
                  accept="image/*"
                  class="info-file-hidden"
                  @change="onRoomAvatarSelected"
                />
              </div>
            </section>

            <section v-if="showLifecycleCard" class="info-card info-card-alert" :class="`is-${roomState.kind}`">
              <div class="info-card-head">
                <span>生命周期提醒</span>
                <span class="info-badge" :class="lifecycleBadgeClass">{{ lifecycleBadgeText }}</span>
              </div>
              <div class="info-alert-title">{{ roomState.title }}</div>
              <p class="info-alert-text">{{ roomState.detail }}</p>
              <div v-if="lifecycleCountdownText" class="info-alert-countdown">{{ lifecycleCountdownText }}</div>
            </section>

            <section v-if="resolvedShareUrl" class="info-card">
              <div class="info-card-head">
                <span>分享房间</span>
                <button class="info-copy" :class="{ copied }" type="button" @click="copyShareUrl">
                  {{ copied ? '已复制' : '复制链接' }}
                </button>
              </div>
              <div class="info-share-grid">
                <div class="info-qr-shell">
                  <img v-if="qrDataUrl" :src="qrDataUrl" alt="room qrcode" class="info-qr" />
                  <div v-else class="info-qr-loading">正在生成二维码...</div>
                </div>
                <div class="info-share-copy">
                  <div class="info-share-label">分享链接</div>
                  <input class="info-share-input" :value="resolvedShareUrl" readonly @click="$event.target.select()" />
                  <div class="info-share-hint">发给朋友后，他们可以通过链接或二维码快速进入这个房间。</div>
                </div>
              </div>
            </section>

            <section class="info-card">
              <div class="info-card-head">
                <span>成员</span>
                <span class="info-members-count">{{ members.length }}</span>
              </div>
              <div class="info-members">
                <div v-for="member in sortedMembers" :key="memberKey(member)" class="info-member">
                  <div class="info-member-avatar-wrap">
                    <img
                      v-if="hasAvatarImage(member)"
                      :src="member.avatar"
                      class="info-member-avatar info-member-avatar-img"
                      alt=""
                    />
                    <div v-else class="info-member-avatar" :style="{ background: memberColor(member) }">
                      {{ memberInitial(member) }}
                    </div>
                    <span class="info-member-dot" :class="{ online: member.status?.state === 'online' }"></span>
                  </div>

                  <div class="info-member-copy">
                    <div class="info-member-name-row">
                      <div class="info-member-name">{{ member.username || '匿名成员' }}</div>
                      <span v-if="isSelf(member)" class="info-member-tag">你</span>
                      <span v-if="isMuted(member)" class="info-member-tag muted">禁言中</span>
                    </div>
                    <div class="info-member-state-row">
                      <div class="info-member-state">{{ member.status?.state === 'online' ? '在线' : '离线' }}</div>
                      <span v-if="member._raw?.isHost || member._raw?.role === 1" class="info-member-role">房主</span>
                    </div>
                  </div>

                  <div v-if="canOperateMember(member)" class="info-member-actions">
                    <button
                      class="info-mini-btn"
                      type="button"
                      @click="emitMemberAction(isMuted(member) ? 'unmute' : 'mute', member)"
                    >
                      {{ isMuted(member) ? '解除禁言' : '禁言' }}
                    </button>
                    <button class="info-mini-btn danger" type="button" @click="emitMemberAction('kick', member)">
                      踢出
                    </button>
                  </div>
                </div>
              </div>
              <div v-if="isHost" class="info-member-footnote">
                房主可以直接对普通成员执行禁言、解除禁言和踢出操作。
              </div>
            </section>

            <section v-if="showGameCreate" class="info-card info-game-card">
              <div class="info-card-head">
                <span>房间游戏</span>
                <span class="info-badge active">可创建</span>
              </div>
              <div class="info-game-title">谁是卧底</div>
              <p class="info-game-desc">开局后进入等待大厅，邀请玩家加入后即可开始。</p>
              <div class="info-game-actions">
                <button class="info-game-btn primary" type="button" :disabled="gameActionPending" @click="handleQuickCreate">
                  {{ gameActionPending ? '准备中...' : '开始组局' }}
                </button>
                <button
                  class="info-game-btn ghost"
                  :class="{ active: gameAdvancedOpen }"
                  :aria-expanded="gameAdvancedOpen ? 'true' : 'false'"
                  type="button"
                  @click="gameAdvancedOpen = !gameAdvancedOpen"
                >
                  {{ gameAdvancedOpen ? '收起设置' : '展开设置' }}
                </button>
              </div>
              <div v-if="gameAdvancedOpen" class="info-game-settings">
                <label><span>最少人数</span><input v-model.number="gameCreateForm.minPlayers" type="number" min="4" max="10" /></label>
                <label><span>最多人数</span><input v-model.number="gameCreateForm.maxPlayers" type="number" min="4" max="10" /></label>
                <label><span>发言秒数</span><input v-model.number="gameCreateForm.describeTimeout" type="number" min="15" max="120" /></label>
                <label><span>投票秒数</span><input v-model.number="gameCreateForm.voteTimeout" type="number" min="10" max="90" /></label>
                <label><span>最多 AI</span><input v-model.number="gameCreateForm.maxAiPlayers" type="number" min="0" max="8" /></label>
              </div>
            </section>

            <section class="info-actions">
              <div v-if="isHost" class="info-host-note">
                房主不能直接离开房间，请使用“关闭房间”结束本次会话。
              </div>
              <button v-if="isHost" class="info-action" type="button" @click="emit('extend-room')">
                <strong>延期房间</strong>
                <small>追加时长，让当前聊天室继续开放。</small>
              </button>
              <button v-if="isHost" class="info-action" type="button" @click="emit('resize-room')">
                <strong>扩容房间</strong>
                <small>提高人数上限，让更多成员可以加入。</small>
              </button>
              <button v-if="!isHost" class="info-action" type="button" @click="emit('leave')">
                <strong>离开房间</strong>
                <small>离开后仍可通过房间号或分享链接再次进入。</small>
              </button>
              <button v-if="isHost" class="info-action danger" type="button" @click="emit('close-room')">
                <strong>关闭房间</strong>
                <small>立即结束这次会话，所有成员都会同步收到关闭状态。</small>
              </button>
            </section>
          </div>
        </aside>
      </div>
    </transition>
  </Teleport>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { getShareUrl } from '@/api/room'
import { uploadFile } from '@/api/file'
import { formatCountdown, calcProgress, getCountdownColor } from '@/utils/formatter'
import { generateQRCodeDataUrl } from '@/utils/qrcode'
import { getRoomDisplayName, getRoomVisualUrl } from '@/utils/roomVisual'

const props = defineProps({
  visible: { type: Boolean, default: false },
  room: { type: Object, default: null },
  members: { type: Array, default: () => [] },
  isHost: { type: Boolean, default: false },
  currentAccountId: { type: [String, Number], default: '' },
  roomState: { type: Object, default: () => ({}) },
  showGameCreate: { type: Boolean, default: false },
  gameActionPending: { type: Boolean, default: false }
})

const emit = defineEmits(['close', 'leave', 'close-room', 'extend-room', 'resize-room', 'member-action', 'create-game', 'update-avatar'])

const now = ref(Date.now())
const resolvedShareUrl = ref('')
const qrDataUrl = ref('')
const copied = ref(false)
const gameAdvancedOpen = ref(false)
const avatarUploading = ref(false)
const avatarUploadError = ref('')
const avatarInputRef = ref(null)
const gameCreateForm = reactive({ minPlayers: 4, maxPlayers: 8, describeTimeout: 60, voteTimeout: 30, maxAiPlayers: 4 })

function handleQuickCreate() {
  emit('create-game', { ...gameCreateForm })
}

let timer = null

onMounted(() => {
  timer = window.setInterval(() => {
    now.value = Date.now()
  }, 10000)
})

onUnmounted(() => {
  if (timer) window.clearInterval(timer)
})

watch(
  () => [props.visible, props.room?.roomId, props.room?.shareUrl, props.room?._raw?.shareUrl],
  async ([visible]) => {
    if (!visible) return
    await ensureShareUrl()
  },
  { immediate: true }
)

watch(resolvedShareUrl, async (url) => {
  if (!url) {
    qrDataUrl.value = ''
    return
  }
  qrDataUrl.value = (await generateQRCodeDataUrl(url, 200)) || ''
})

watch(
  () => props.visible,
  (visible) => {
    if (visible) return
    avatarUploadError.value = ''
    avatarUploading.value = false
    if (avatarInputRef.value) avatarInputRef.value.value = ''
  }
)

const roomName = computed(() => getRoomDisplayName(props.room))
const roomVisualUrl = computed(() => getRoomVisualUrl(props.room))
const heroCoverStyle = computed(() => ({
  backgroundImage: `linear-gradient(180deg, rgba(33, 23, 13, 0.14), rgba(33, 23, 13, 0.62)), url("${roomVisualUrl.value}")`
}))
const memberCount = computed(() => props.room?.memberCount || props.members.length || 0)
const publicText = computed(() => (props.room?.isPublic === 1 ? '公开房间' : '私密房间'))
const statusText = computed(() => props.room?.statusDesc || '开放中')
const createTimeText = computed(() => formatDateTime(props.room?.createTime))
const onlineCount = computed(() => props.members.filter(member => member.status?.state === 'online').length)
const sortedMembers = computed(() => {
  return [...props.members].sort((left, right) => {
    const leftHost = Boolean(left?._raw?.isHost || left?._raw?.role === 1)
    const rightHost = Boolean(right?._raw?.isHost || right?._raw?.role === 1)
    if (leftHost !== rightHost) return leftHost ? -1 : 1

    const leftSelf = isSelf(left)
    const rightSelf = isSelf(right)
    if (leftSelf !== rightSelf) return leftSelf ? -1 : 1

    const leftOnline = left?.status?.state === 'online'
    const rightOnline = right?.status?.state === 'online'
    if (leftOnline !== rightOnline) return leftOnline ? -1 : 1

    return String(left?.username || '').localeCompare(String(right?.username || ''), 'zh-CN')
  })
})

const countdownText = computed(() => {
  if (props.roomState?.kind === 'closed') return '已关闭'
  if (props.roomState?.kind === 'grace') {
    return props.roomState?.countdownMs > 0
      ? `宽限 ${formatCountdown(props.roomState.countdownMs)}`
      : '宽限期中'
  }
  if (!props.room?.expireTime) return '永久不过期'
  const remain = new Date(props.room.expireTime).getTime() - now.value
  return formatCountdown(remain)
})

const countdownColor = computed(() => {
  if (props.roomState?.kind === 'closed') return '#8b3a35'
  if (props.roomState?.kind === 'grace') return '#b5543f'
  if (props.roomState?.kind === 'muted') return '#7d6c5c'
  if (!props.room?.expireTime) return 'var(--fc-success)'
  return getCountdownColor(new Date(props.room.expireTime).getTime() - now.value)
})

const progressWidth = computed(() => {
  if (props.roomState?.kind === 'closed') return '0%'
  if (props.roomState?.kind === 'grace') return '0%'
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

const showLifecycleCard = computed(() => ['expiring', 'grace', 'muted', 'closed'].includes(props.roomState?.kind))
const lifecycleBadgeText = computed(() => {
  const kind = props.roomState?.kind
  if (kind === 'expiring') return '即将到期'
  if (kind === 'grace') return '宽限期'
  if (kind === 'muted') return '禁言中'
  if (kind === 'closed') return '已关闭'
  return '提醒'
})
const lifecycleBadgeClass = computed(() => {
  const kind = props.roomState?.kind
  if (kind === 'expiring') return 'warning'
  if (kind === 'grace' || kind === 'closed' || kind === 'muted') return 'danger'
  return 'waiting'
})
const lifecycleCountdownText = computed(() => {
  const remain = props.roomState?.countdownMs
  if (remain == null || remain <= 0) return ''
  if (props.roomState?.kind === 'grace') {
    return `正式关闭倒计时：${formatCountdown(remain)}`
  }
  if (props.roomState?.kind === 'expiring') {
    return `到期倒计时：${formatCountdown(remain)}`
  }
  return ''
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

function triggerRoomAvatarUpload() {
  if (avatarUploading.value) return
  avatarUploadError.value = ''
  avatarInputRef.value?.click()
}

async function onRoomAvatarSelected(event) {
  const file = event.target?.files?.[0]
  if (!file) return
  avatarUploadError.value = ''

  if (!file.type?.startsWith('image/')) {
    avatarUploadError.value = '请选择图片文件'
    if (avatarInputRef.value) avatarInputRef.value.value = ''
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    avatarUploadError.value = '图片大小不能超过 5MB'
    if (avatarInputRef.value) avatarInputRef.value.value = ''
    return
  }

  avatarUploading.value = true
  try {
    const result = await uploadFile(file)
    if (!result?.url) throw new Error('上传结果异常')
    emit('update-avatar', result.url)
  } catch (error) {
    avatarUploadError.value = error?.message || '上传失败'
  } finally {
    avatarUploading.value = false
    if (avatarInputRef.value) avatarInputRef.value.value = ''
  }
}

function clearRoomAvatar() {
  avatarUploadError.value = ''
  emit('update-avatar', '')
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

function memberKey(member) {
  return member?._id || member?._raw?.accountId || member?.username || Math.random().toString(36).slice(2, 8)
}

function isSelf(member) {
  return String(member?._id || member?._raw?.accountId || '') === String(props.currentAccountId || '')
}

function isMuted(member) {
  return Boolean(member?._raw?.isMuted)
}

function canOperateMember(member) {
  if (!props.isHost) return false
  if (!member) return false
  if (isSelf(member)) return false
  return !(member._raw?.isHost || member._raw?.role === 1)
}

function emitMemberAction(action, member) {
  if (!canOperateMember(member)) return
  emit('member-action', { action, member })
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
</script>

<style scoped>
.info-overlay {
  position: fixed;
  inset: 0;
  display: flex;
  justify-content: flex-end;
  background: var(--fc-backdrop);
  z-index: 9500;
}

.info-panel {
  width: 484px;
  max-width: 96vw;
  height: 100%;
  display: flex;
  flex-direction: column;
  border-left: 1px solid var(--fc-border-strong);
  background: var(--fc-panel-elevated);
  box-shadow: var(--fc-shadow-panel);
  position: relative;
  overflow: hidden;
}

/* .info-panel ::before overlay removed for fusion design */

.info-head {
  position: sticky;
  top: 0;
  z-index: 3;
  padding: 22px 22px 18px;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  background: linear-gradient(180deg, var(--fc-surface), transparent);
}

.info-kicker,
.info-card-head span:first-child,
.info-share-label,
.info-summary-item span,
.info-meta-item span {
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.info-title {
  margin-top: 8px;
  font-family: var(--fc-font-display);
  font-size: 24px;
  line-height: 1.1;
  font-weight: 600;
  color: var(--fc-text);
}

.info-close {
  width: 38px;
  height: 38px;
  border: 1px solid var(--fc-border);
  border-radius: 50%;
  background: linear-gradient(180deg, #fffdf9 0%, #f8f1e7 100%);
  color: var(--fc-text);
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.86), 0 4px 10px rgba(73, 52, 31, 0.08);
  transition: border-color 0.22s ease, background 0.22s ease, box-shadow 0.22s ease, transform 0.22s ease;
}

.info-close:hover {
  border-color: var(--fc-accent-weak);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.9), 0 8px 16px rgba(73, 52, 31, 0.14);
}

.info-close:active {
  transform: translateY(1px);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.82), 0 4px 10px rgba(73, 52, 31, 0.1);
}

.info-body {
  flex: 1;
  overflow: auto;
  padding: 0 22px 24px;
  position: relative;
  z-index: 1;
}

.info-hero,
.info-card,
.info-action {
  border: 1px solid var(--fc-border);
  border-radius: 28px;
  background: var(--fc-panel);
  box-shadow: var(--fc-shadow-soft);
}

.info-hero {
  overflow: hidden;
  position: relative;
}

/* ::before gradient overlays removed for fusion design */

.info-hero-cover {
  position: relative;
  min-height: 292px;
  padding: 24px 24px 22px;
  background-size: cover;
  background-position: center;
}

/* .info-hero-mist removed for fusion design */
.info-hero-mist {
  display: none;
}

.info-hero-content {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 14px;
}

.info-status-pill,
.info-badge,
.info-members-count,
.info-member-role,
.info-member-tag {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 6px 12px;
  border: 1px solid transparent;
  border-radius: 999px;
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
}

.info-status-pill {
  background: var(--fc-surface);
  color: var(--fc-text);
}

.info-status-pill.active,
.info-badge.active {
  background: linear-gradient(180deg, #F4FAF2 0%, #E4F3DF 100%);
  border-color: rgba(82, 122, 77, 0.34);
  color: #42673f;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.8), 0 2px 8px rgba(82, 122, 77, 0.14);
}

.info-status-pill.warning,
.info-badge.warning {
  background: rgba(255, 242, 224, 0.96);
  color: #8b641c;
}

.info-status-pill.danger,
.info-badge.danger {
  background: rgba(253, 236, 234, 0.96);
  color: #8b3a35;
}

.info-status-pill.muted,
.info-badge.muted {
  background: rgba(233, 225, 217, 0.96);
  color: #7d6c5c;
}

.info-status-pill.waiting,
.info-badge.waiting,
.info-members-count,
.info-member-role,
.info-member-tag {
  background: var(--fc-bg);
  color: var(--fc-accent-strong);
}

.info-member-tag.muted {
  background: rgba(253, 236, 234, 0.96);
  color: #a74f35;
}

.info-avatar-ring {
  width: 96px;
  height: 96px;
  padding: 6px;
  border-radius: 999px;
  background: rgba(255, 250, 243, 0.28);
  box-shadow: inset 0 0 0 1px rgba(255, 250, 243, 0.34);
}

.info-avatar {
  width: 84px;
  height: 84px;
  border-radius: 999px;
  object-fit: cover;
  display: block;
  background: rgba(255, 250, 243, 0.72);
}

.info-name {
  max-width: 100%;
  font-family: var(--fc-font-display);
  font-size: 30px;
  line-height: 1.05;
  font-weight: 600;
  color: #fffaf3;
  text-shadow: 0 8px 22px rgba(33, 23, 13, 0.22);
}

.info-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.info-meta span {
  display: inline-flex;
  align-items: center;
  padding: 7px 12px;
  border-radius: 999px;
  background: rgba(255, 250, 243, 0.16);
  border: 1px solid rgba(255, 250, 243, 0.18);
  color: rgba(255, 250, 243, 0.88);
  font-family: var(--fc-font);
  font-size: 12px;
}

.info-summary {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  gap: 14px;
  align-items: center;
  padding: 18px 24px 20px;
  background: var(--fc-surface);
}

.info-summary-item strong {
  display: block;
  margin-top: 8px;
  font-family: var(--fc-font-display);
  font-size: 20px;
  line-height: 1.1;
  font-weight: 600;
  color: var(--fc-text);
}

.info-summary-divider {
  width: 1px;
  height: 44px;
  background: rgba(77, 52, 31, 0.10);
}

.info-card {
  margin-top: 16px;
  padding: 20px;
  position: relative;
  overflow: hidden;
}

.info-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.info-countdown-row {
  margin-top: 14px;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
}

.info-countdown {
  font-family: var(--fc-font-display);
  font-size: 24px;
  line-height: 1.05;
  font-weight: 600;
  color: var(--fc-text);
}

.info-create-time {
  font-family: var(--fc-font);
  font-size: 13px;
  color: var(--fc-text-sec);
}

.info-bar {
  height: 7px;
  margin-top: 14px;
  border-radius: 999px;
  background: rgba(77, 52, 31, 0.08);
  overflow: hidden;
}

.info-bar-fill {
  height: 100%;
  border-radius: inherit;
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
  background: var(--fc-bg);
  border: 1px solid var(--fc-border);
}

.info-meta-item strong {
  display: block;
  margin-top: 8px;
  font-family: var(--fc-font);
  font-size: 15px;
  color: var(--fc-text);
}

.info-avatar-edit {
  margin-top: 14px;
  display: flex;
  gap: 14px;
  align-items: flex-start;
}

.info-avatar-edit-preview {
  width: 80px;
  height: 80px;
  border-radius: 22px;
  overflow: hidden;
  border: 1px solid var(--fc-border);
  background: var(--fc-surface);
  flex-shrink: 0;
}

.info-avatar-edit-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.info-avatar-edit-actions {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.info-avatar-edit-hint {
  width: 100%;
  font-family: var(--fc-font);
  font-size: 12px;
  color: var(--fc-text-muted);
}

.info-avatar-edit-error {
  width: 100%;
  font-family: var(--fc-font);
  font-size: 12px;
  color: #a74f35;
}

.info-file-hidden {
  display: none;
}

.info-card-alert {
  position: relative;
  overflow: hidden;
}

.info-card-alert.is-expiring {
  background: #fff6e8;
}

.info-card-alert.is-grace,
.info-card-alert.is-closed {
  background: #fff1ee;
}

.info-card-alert.is-muted {
  background: #f4efe9;
}

.info-alert-title {
  position: relative;
  margin-top: 14px;
  font-family: var(--fc-font-display);
  font-size: 20px;
  line-height: 1.15;
  color: var(--fc-text);
}

.info-alert-text,
.info-alert-countdown,
.info-share-hint,
.info-qr-loading,
.info-member-state,
.info-action small,
.info-host-note,
.info-member-footnote {
  font-family: var(--fc-font);
  font-size: 12px;
  line-height: 1.6;
  color: var(--fc-text-sec);
}

.info-alert-text {
  position: relative;
  margin-top: 10px;
}

.info-alert-countdown {
  position: relative;
  margin-top: 12px;
  font-weight: 700;
  color: var(--fc-text);
}

.info-copy {
  padding: 8px 14px;
  border: 1px solid rgba(95, 68, 43, 0.16);
  border-radius: 999px;
  background: linear-gradient(180deg, #fffdf9 0%, #f7f0e5 100%);
  color: var(--fc-accent-strong);
  font-family: var(--fc-font);
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.82), 0 4px 10px rgba(82, 58, 35, 0.08);
  transition: border-color 0.22s ease, background 0.22s ease, color 0.22s ease, box-shadow 0.22s ease, transform 0.22s ease;
}

.info-copy:hover {
  border-color: rgba(160, 122, 71, 0.42);
  background: linear-gradient(180deg, #fffefb 0%, #f3e8d8 100%);
  color: var(--fc-accent);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.86), 0 8px 16px rgba(82, 58, 35, 0.14);
}

.info-copy:active {
  transform: translateY(1px);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.82), 0 4px 10px rgba(82, 58, 35, 0.1);
}

.info-copy.copied {
  background: linear-gradient(180deg, #f4faf2 0%, #e7f3e1 100%);
  border-color: rgba(90, 140, 78, 0.35);
  color: #42673f;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.86), 0 6px 14px rgba(82, 122, 77, 0.14);
}

.info-share-grid {
  margin-top: 14px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 164px;
  gap: 16px;
  align-items: stretch;
}

.info-share-input {
  width: 100%;
  margin-top: 10px;
  padding: 12px 14px;
  border: 1px solid rgba(72, 49, 28, 0.08);
  border-radius: 16px;
  background: rgba(243, 231, 215, 0.92);
  color: var(--fc-text-sec);
  font-family: var(--fc-font-mono);
  font-size: 12px;
}

.info-share-hint {
  margin-top: 10px;
}

.info-qr-shell {
  min-height: 164px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 22px;
  background: var(--fc-bg);
}

.info-qr {
  width: 144px;
  height: 144px;
  border-radius: 20px;
  background: #fffaf3;
  padding: 10px;
}

.info-members {
  margin-top: 14px;
  display: grid;
  gap: 10px;
}

.info-member {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--fc-border);
  border-radius: 20px;
  background: var(--fc-surface);
}

.info-member-avatar-wrap {
  position: relative;
  flex-shrink: 0;
}

.info-member-avatar {
  width: 46px;
  height: 46px;
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

.info-member-name-row,
.info-member-state-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.info-member-name {
  font-family: var(--fc-font);
  font-size: 15px;
  font-weight: 600;
  color: var(--fc-text);
}

.info-member-state-row {
  margin-top: 6px;
}

.info-member-actions {
  display: flex;
  flex-shrink: 0;
  gap: 8px;
}

.info-mini-btn {
  min-height: 34px;
  padding: 8px 14px;
  border: 1px solid rgba(90, 64, 38, 0.16);
  border-radius: 999px;
  background: linear-gradient(180deg, #fffdf9 0%, #f8f1e7 100%);
  color: var(--fc-text);
  font-family: var(--fc-font);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.84), 0 3px 8px rgba(73, 52, 31, 0.06);
  transition: border-color 0.22s ease, background 0.22s ease, color 0.22s ease, box-shadow 0.22s ease, transform 0.22s ease;
}

.info-mini-btn:hover {
  border-color: rgba(160, 122, 71, 0.42);
  background: linear-gradient(180deg, #fffefb 0%, #f4eadb 100%);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.9), 0 8px 16px rgba(73, 52, 31, 0.12);
}

.info-mini-btn:active {
  transform: translateY(1px);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.84), 0 4px 10px rgba(73, 52, 31, 0.09);
}

.info-mini-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  box-shadow: none;
  transform: none;
}

.info-mini-btn.danger {
  background: linear-gradient(180deg, #cc7558 0%, #b75b42 100%);
  border-color: #b65a41;
  color: #fffaf3;
  box-shadow: 0 6px 14px rgba(158, 68, 53, 0.24);
}

.info-mini-btn.danger:hover {
  border-color: #a74f3a;
  background: linear-gradient(180deg, #d27d60 0%, #bd6247 100%);
  box-shadow: 0 8px 18px rgba(158, 68, 53, 0.3);
}

.info-member-footnote {
  margin-top: 12px;
}

.info-actions {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.info-host-note {
  padding: 14px 16px;
  border-radius: 22px;
  background: rgba(253, 236, 234, 0.82);
  border: 1px solid rgba(196, 96, 82, 0.14);
  color: #9f4c41;
}

.info-action {
  position: relative;
  padding: 16px 18px;
  text-align: left;
  cursor: pointer;
  background: linear-gradient(180deg, #fffdf9 0%, #f8f1e7 100%);
  border-color: rgba(95, 67, 41, 0.15);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.86), 0 7px 16px rgba(73, 52, 31, 0.08);
  transition: border-color 0.22s ease, background 0.22s ease, box-shadow 0.22s ease, transform 0.22s ease;
}

.info-action:hover {
  border-color: rgba(160, 122, 71, 0.44);
  background: linear-gradient(180deg, #fffefb 0%, #f4eadb 100%);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.9), 0 12px 24px rgba(73, 52, 31, 0.12);
}

.info-action:active {
  transform: translateY(1px);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.86), 0 6px 14px rgba(73, 52, 31, 0.1);
}

.info-action strong {
  display: block;
  font-family: var(--fc-font-display);
  font-size: 17px;
  line-height: 1.15;
  color: var(--fc-text);
}

.info-action small {
  display: block;
  margin-top: 8px;
}

.info-action.danger strong,
.info-action.danger small {
  color: var(--fc-danger);
}

.info-action.danger {
  background: linear-gradient(180deg, rgba(255, 246, 243, 0.98) 0%, rgba(255, 236, 231, 0.9) 100%);
  border-color: rgba(186, 91, 64, 0.28);
}

.info-action.danger:hover {
  border-color: rgba(176, 73, 46, 0.42);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.84), 0 12px 24px rgba(176, 73, 46, 0.16);
}

.info-game-card {
  background: var(--fc-surface);
}

.info-game-title {
  margin-top: 14px;
  font-family: var(--fc-font-display);
  font-size: 17px;
  font-weight: 600;
  color: var(--fc-text);
}

.info-game-desc {
  margin-top: 6px;
  font-family: var(--fc-font);
  font-size: 13px;
  line-height: 1.6;
  color: var(--fc-text-sec);
}

.info-game-actions {
  margin-top: 16px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.info-game-btn {
  min-height: 44px;
  padding: 11px 20px;
  border: 1px solid transparent;
  border-radius: 999px;
  font-family: var(--fc-font);
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 0.01em;
  cursor: pointer;
  transition: border-color 0.22s ease, background 0.22s ease, color 0.22s ease, box-shadow 0.22s ease, transform 0.22s ease;
}

.info-game-btn:hover {
  border-color: var(--fc-border-strong);
}

.info-game-btn.primary {
  border-color: rgba(175, 128, 40, 0.32);
  background: linear-gradient(180deg, #dcb537 0%, #c79b23 100%);
  color: #fffaf3;
  box-shadow: inset 0 1px 0 rgba(255, 246, 212, 0.62), 0 10px 18px rgba(164, 118, 34, 0.28);
}

.info-game-btn.primary:hover {
  border-color: rgba(162, 116, 29, 0.42);
  background: linear-gradient(180deg, #e1be48 0%, #cd9f29 100%);
  box-shadow: inset 0 1px 0 rgba(255, 248, 222, 0.64), 0 13px 22px rgba(164, 118, 34, 0.34);
}

.info-game-btn.primary:active {
  transform: translateY(1px);
  box-shadow: inset 0 1px 0 rgba(255, 246, 212, 0.56), 0 7px 14px rgba(164, 118, 34, 0.26);
}

.info-game-btn.ghost {
  border-color: rgba(95, 67, 41, 0.18);
  background: linear-gradient(180deg, #fffdf9 0%, #f7f0e5 100%);
  color: var(--fc-text);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.86), 0 6px 14px rgba(73, 52, 31, 0.08);
}

.info-game-btn.ghost:hover {
  border-color: rgba(160, 122, 71, 0.42);
  background: linear-gradient(180deg, #fffefb 0%, #f3e8d8 100%);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.9), 0 10px 18px rgba(73, 52, 31, 0.14);
}

.info-game-btn.ghost.active {
  border-color: rgba(185, 144, 53, 0.38);
  background: linear-gradient(180deg, #f8f1de 0%, #efe2c5 100%);
  color: var(--fc-accent-strong);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.74), 0 9px 18px rgba(169, 131, 52, 0.18);
}

.info-game-btn.ghost:active {
  transform: translateY(1px);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.84), 0 5px 12px rgba(73, 52, 31, 0.1);
}

.info-game-btn:disabled {
  opacity: 0.52;
  cursor: not-allowed;
  box-shadow: none;
  transform: none;
}

.info-close:focus-visible,
.info-copy:focus-visible,
.info-mini-btn:focus-visible,
.info-action:focus-visible,
.info-game-btn:focus-visible {
  outline: none;
  border-color: rgba(183, 142, 52, 0.62);
  box-shadow: 0 0 0 3px rgba(213, 175, 90, 0.24);
}

.info-game-settings {
  margin-top: 16px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.info-game-settings label {
  display: grid;
  gap: 6px;
  font-family: var(--fc-font);
  font-size: 13px;
  color: var(--fc-text-sec);
}

.info-game-settings input {
  width: 100%;
  padding: 10px 12px;
  border-radius: 16px;
  border: 1px solid var(--fc-border);
  background: var(--fc-surface);
  color: var(--fc-text);
  font: inherit;
  font-size: 14px;
}

.info-enter-active,
.info-leave-active {
  transition: opacity 0.24s ease;
}

.info-enter-active .info-panel,
.info-leave-active .info-panel {
  transition: transform 0.24s ease, opacity 0.24s ease;
}

.info-enter-from,
.info-leave-to {
  opacity: 0;
}

.info-enter-from .info-panel,
.info-leave-to .info-panel {
  transform: translateX(16px);
  opacity: 0;
}

@media (prefers-reduced-motion: reduce) {
  .info-close,
  .info-copy,
  .info-mini-btn,
  .info-action,
  .info-game-btn {
    transition: none;
  }
}

@media (max-width: 640px) {
  .info-panel {
    width: 100%;
    max-width: 100%;
  }

  .info-head {
    padding-top: calc(18px + env(safe-area-inset-top));
  }

  .info-body {
    padding: 0 16px calc(20px + env(safe-area-inset-bottom));
  }

  .info-hero-cover {
    min-height: 244px;
    padding: 20px;
  }

  .info-name {
    font-size: 26px;
  }

  .info-summary {
    grid-template-columns: 1fr;
    gap: 10px;
  }

  .info-summary-divider {
    display: none;
  }

  .info-share-grid,
  .info-meta-grid {
    grid-template-columns: 1fr;
  }

  .info-avatar-edit {
    flex-direction: column;
  }

  .info-avatar-edit-actions {
    width: 100%;
  }

  .info-qr-shell {
    min-height: 188px;
  }

  .info-countdown-row,
  .info-member {
    align-items: flex-start;
  }

  .info-member {
    flex-wrap: wrap;
  }

  .info-member-actions {
    width: 100%;
    justify-content: flex-end;
  }
}
</style>
