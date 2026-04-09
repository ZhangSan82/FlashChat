<template>
  <div class="lp">
    <div class="lp-card" :class="{ 'is-visible': cardVisible }">
      <button class="lp-close" type="button" aria-label="close preview" @click="closePreview">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="18" y1="6" x2="6" y2="18" />
          <line x1="6" y1="6" x2="18" y2="18" />
        </svg>
      </button>

      <div class="lp-brand">
        <span class="lp-brand-icon">⚡</span>
        <span class="lp-brand-name">FlashChat</span>
      </div>

      <template v-if="state === 'loading'">
        <div class="lp-loading">
          <div class="lp-pulse"></div>
          <p class="lp-hint">{{ loadingHint }}</p>
        </div>
      </template>

      <template v-else-if="state === 'error'">
        <div class="lp-error">
          <div class="lp-error-icon">
            <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#D4736C" stroke-width="1.5">
              <circle cx="12" cy="12" r="10" />
              <line x1="15" y1="9" x2="9" y2="15" />
              <line x1="9" y1="9" x2="15" y2="15" />
            </svg>
          </div>
          <p class="lp-error-title">{{ errorMsg }}</p>
          <p class="lp-error-hint" v-if="errorHint">{{ errorHint }}</p>
          <div class="lp-error-actions">
            <button class="lp-btn lp-btn-outline" @click="retryLoad">重试</button>
            <button class="lp-btn lp-btn-outline" @click="goHome">返回房间列表</button>
          </div>
        </div>
      </template>

      <template v-else>
        <div class="lp-section lp-room-hero" style="--delay: 0.08s">
          <img class="lp-room-cover" :src="roomVisualUrl" :alt="roomTitle" />
          <div class="lp-room-label">房间预览</div>
          <div class="lp-room-hero-title">{{ roomTitle }}</div>
          <div class="lp-room-hero-id">{{ roomId }}</div>
        </div>

        <div class="lp-divider" style="--delay: 0.14s"></div>

        <div class="lp-section lp-room" style="--delay: 0.2s">
          <div class="lp-room-label lp-room-label-soft">房间信息</div>

          <div class="lp-room-stats">
            <div class="lp-stat">
              <span class="lp-status-dot" :style="{ background: statusColor }"></span>
              <span>{{ statusText }}</span>
            </div>
            <div class="lp-stat">
              <span>⏳ {{ roomCountdown }}</span>
            </div>
            <div class="lp-stat">
              <span>{{ roomMembers }}</span>
            </div>
          </div>

          <div class="lp-join-hint">
            <span class="lp-join-hint-label">将以 {{ nickname }} 的身份加入</span>
            <span class="lp-join-hint-id">{{ maskedAccountId }}</span>
          </div>
        </div>

        <button
          class="lp-section lp-btn lp-btn-cta"
          :class="{ 'is-joining': joining }"
          :disabled="joining"
          @click="doJoin"
          style="--delay: 0.28s"
        >
          <span v-if="!joining">进入房间</span>
          <span v-else class="lp-btn-loading">
            <span class="lp-btn-spinner"></span>
            加入中...
          </span>
        </button>

        <transition name="lp-err-fade">
          <p v-if="joinError" class="lp-join-error">{{ joinError }}</p>
        </transition>
      </template>

      <div class="lp-footer">FlashChat v1.0</div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from '@/composables/useAuth'
import * as roomApi from '@/api/room'
import { formatCountdown } from '@/utils/formatter'
import { getRoomDisplayName, getRoomVisualUrl } from '@/utils/roomVisual'

const props = defineProps({
  roomId: { type: String, required: true }
})

const route = useRoute()
const router = useRouter()
const auth = useAuth()

const state = ref('loading')
const loadingHint = ref('正在准备身份...')
const errorMsg = ref('')
const errorHint = ref('')
const cardVisible = ref(false)
const joining = ref(false)
const joinError = ref('')
const roomInfo = ref(null)

const roomId = computed(() => props.roomId)
const nickname = computed(() => auth.identity.value?.nickname || '匿名用户')

const maskedAccountId = computed(() => {
  const id = auth.identity.value?.accountId
  if (!id || id.length < 5) return id || ''
  return id.slice(0, 3) + '••••' + id.slice(-2)
})

const roomTitle = computed(() => getRoomDisplayName(roomInfo.value || { roomId: props.roomId }))

const roomVisualUrl = computed(() => getRoomVisualUrl(roomInfo.value || {
  roomId: props.roomId,
  title: roomTitle.value
}))

const roomCountdown = computed(() => {
  if (!roomInfo.value?.expireTime) return '永久不过期'
  const remain = new Date(roomInfo.value.expireTime).getTime() - Date.now()
  if (remain <= 0) return '已过期'
  return formatCountdown(remain)
})

const roomMembers = computed(() => {
  const current = roomInfo.value?.memberCount || 0
  const max = roomInfo.value?.maxMembers || 50
  return `${current} / ${max} 人`
})

const statusText = computed(() => {
  if (!roomInfo.value) return '房间可用'
  return roomInfo.value.statusDesc || '开放中'
})

const statusColor = computed(() => {
  if (!roomInfo.value) return '#B68450'
  const s = roomInfo.value.status
  if (s === 1) return '#7BAF6E'
  if (s === 2) return '#D4A84C'
  if (s === 3 || s === 4) return '#D4736C'
  return '#B68450'
})

onMounted(async () => {
  setTimeout(() => { cardVisible.value = true }, 50)

  try {
    loadingHint.value = '正在准备身份...'
    await auth.init()

    loadingHint.value = '获取房间信息...'
    await fetchRoomPreview()

    state.value = 'ready'
  } catch (e) {
    console.error('[JoinLanding] 初始化失败', e)
    errorMsg.value = '连接失败'
    errorHint.value = '请确认后端服务已经启动'
    state.value = 'error'
  }
})

async function fetchRoomPreview() {
  try {
    roomInfo.value = await roomApi.previewRoom(props.roomId)
    if (roomInfo.value && roomInfo.value.status === 4) {
      errorMsg.value = '这个房间已经关闭'
      errorHint.value = '房间会话已经结束，暂时无法加入'
      state.value = 'error'
    }
  } catch (e) {
    console.warn('[JoinLanding] 房间预览不可用，使用降级模式', e.message)
    roomInfo.value = null
  }
}

let joinErrorTimer = null
async function doJoin() {
  joining.value = true
  joinError.value = ''
  clearTimeout(joinErrorTimer)

  try {
    await roomApi.joinRoom({ roomId: props.roomId })
    sessionStorage.setItem('fc_last_room', props.roomId)
    router.push('/')
  } catch (e) {
    joinError.value = e.message || '加入房间失败，请重试'
    joinErrorTimer = setTimeout(() => { joinError.value = '' }, 4000)
  } finally {
    joining.value = false
  }
}

async function retryLoad() {
  state.value = 'loading'
  errorMsg.value = ''
  errorHint.value = ''
  try {
    await auth.init()
    await fetchRoomPreview()
    state.value = 'ready'
  } catch (e) {
    errorMsg.value = '连接失败'
    errorHint.value = '请确认后端服务已经启动'
    state.value = 'error'
  }
}

function goHome() {
  closePreview()
}

function closePreview() {
  if (route.query.from === 'public') {
    router.push({ name: 'PublicRooms' })
    return
  }
  router.push({ name: 'Chat', query: { view: 'rooms' } })
}
</script>

<style scoped>
.lp {
  width: 100%;
  min-height: 100vh;
  min-height: 100dvh;
  background: var(--fc-bg);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 16px;
}

.lp-card {
  width: 100%;
  max-width: 400px;
  background: var(--fc-surface);
  border: 1px solid var(--fc-border);
  border-radius: var(--fc-radius-lg);
  box-shadow: var(--fc-shadow-panel);
  padding: 36px 32px 28px;
  position: relative;
  opacity: 0;
  transform: translateY(20px) scale(0.97);
  transition: opacity 0.5s ease, transform 0.5s cubic-bezier(0.34, 1.56, 0.64, 1);
}

.lp-card.is-visible {
  opacity: 1;
  transform: translateY(0) scale(1);
}

.lp-close {
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

.lp-close:hover {
  border-color: var(--fc-border-strong);
  color: var(--fc-text);
}

.lp-section {
  opacity: 0;
  transform: translateY(12px);
}

.lp-card.is-visible .lp-section {
  animation: section-in 0.4s ease forwards;
  animation-delay: var(--delay, 0s);
}

.lp-divider {
  opacity: 0;
}

.lp-card.is-visible .lp-divider {
  animation: section-in 0.4s ease forwards;
  animation-delay: var(--delay, 0s);
}

@keyframes section-in {
  to { opacity: 1; transform: translateY(0); }
}

.lp-brand {
  text-align: center;
  margin-bottom: 28px;
}

.lp-brand-icon {
  font-size: 20px;
  margin-right: 6px;
}

.lp-brand-name {
  font-family: var(--fc-font-display);
  font-size: 20px;
  font-weight: 600;
  line-height: 1.1;
  color: var(--fc-text);
}

.lp-loading {
  text-align: center;
  padding: 40px 0 20px;
}

.lp-pulse {
  width: 48px;
  height: 48px;
  border-radius: 16px;
  border: 1px solid var(--fc-border);
  background: var(--fc-surface);
  margin: 0 auto 20px;
  animation: lp-pulse-anim 1.5s ease-in-out infinite;
  position: relative;
}

.lp-pulse::after {
  content: '\26A1';
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--fc-accent);
  font-size: 18px;
}

@keyframes lp-pulse-anim {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-2px); }
}

.lp-hint,
.lp-error-hint,
.lp-join-hint-label,
.lp-join-hint-id {
  font-size: 13px;
  color: var(--fc-text-sec);
}

.lp-error {
  text-align: center;
  padding: 24px 0 12px;
}

.lp-error-icon {
  margin-bottom: 16px;
}

.lp-error-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--fc-text);
  margin-bottom: 6px;
}

.lp-error-actions {
  display: flex;
  gap: 12px;
  justify-content: center;
}

.lp-room-hero {
  text-align: center;
}

.lp-room-cover {
  width: 88px;
  height: 88px;
  border-radius: 24px;
  object-fit: cover;
  margin: 0 auto 16px;
  background: linear-gradient(135deg, #E8D5BF, #C9A87C);
}

.lp-room-label {
  font-size: 11px;
  font-weight: 500;
  color: var(--fc-text-muted);
  text-align: center;
  margin-bottom: 10px;
  text-transform: uppercase;
  letter-spacing: 0.12em;
}

.lp-room-label-soft {
  margin-bottom: 14px;
}

.lp-room-hero-title {
  font-family: var(--fc-font-display);
  font-size: 24px;
  font-weight: 600;
  color: var(--fc-text);
  line-height: 1.15;
  word-break: break-word;
}

.lp-room-hero-id {
  margin-top: 6px;
  font-family: var(--fc-font-mono);
  font-size: 12px;
  color: var(--fc-text-muted);
  letter-spacing: 0.1em;
}

.lp-divider {
  height: 1px;
  margin: 20px 0;
  background: var(--fc-border);
}

.lp-room {
  margin-bottom: 4px;
}

.lp-room-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  justify-content: center;
}

.lp-stat {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--fc-text-sec);
}

.lp-status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
  flex-shrink: 0;
}

.lp-join-hint {
  margin-top: 18px;
  padding: 14px 16px;
  border-radius: 14px;
  border: 1px solid var(--fc-border);
  background: var(--fc-bg);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.lp-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 100%;
  padding: 14px;
  border: 1px solid transparent;
  border-radius: 14px;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s ease;
}

.lp-btn-cta {
  margin-top: 24px;
  background: var(--fc-accent);
  color: #fff;
}

.lp-btn-cta:hover:not(:disabled) {
  background: var(--fc-accent-strong);
}

.lp-btn-cta:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.lp-btn-outline {
  border-color: var(--fc-border);
  background: var(--fc-surface);
  color: var(--fc-text);
  padding: 10px 20px;
  width: auto;
  font-size: 14px;
  font-weight: 500;
}

.lp-btn-outline:hover {
  border-color: var(--fc-border-strong);
}

.lp-btn-loading {
  display: flex;
  align-items: center;
  gap: 8px;
}

.lp-btn-spinner {
  width: 18px;
  height: 18px;
  border: 2px solid rgba(255,255,255,0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: lp-spin 0.6s linear infinite;
}

@keyframes lp-spin { to { transform: rotate(360deg); } }

.lp-join-error {
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

.lp-err-fade-enter-active { transition: all 0.3s ease; }
.lp-err-fade-leave-active { transition: all 0.25s ease; }
.lp-err-fade-enter-from { opacity: 0; transform: translateY(-6px); }
.lp-err-fade-leave-to { opacity: 0; transform: translateY(-6px); }

.lp-footer {
  text-align: center;
  margin-top: 28px;
  font-size: 11px;
  color: var(--fc-text-muted);
  letter-spacing: 0.12em;
}

@media (max-width: 440px) {
  .lp { padding: 16px 12px; }
  .lp-card { padding: 28px 20px 20px; }
  .lp-room-cover { width: 72px; height: 72px; border-radius: 20px; }
  .lp-room-hero-title { font-size: 20px; }
}
</style>
