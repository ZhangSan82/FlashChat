<template>
  <div class="lp">
    <div class="lp-bg-orb lp-orb-1"></div>
    <div class="lp-bg-orb lp-orb-2"></div>

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
  background: var(--fc-app-gradient);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 16px;
  position: relative;
  overflow-x: hidden;
}

.lp-bg-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.34;
  pointer-events: none;
}

.lp-orb-1 {
  width: 300px;
  height: 300px;
  background: rgba(182, 118, 57, 0.72);
  top: -80px;
  right: -60px;
}

.lp-orb-2 {
  width: 250px;
  height: 250px;
  background: rgba(224, 194, 161, 0.82);
  bottom: -60px;
  left: -40px;
}

.lp-card {
  width: 100%;
  max-width: 430px;
  background: var(--fc-panel-elevated);
  border: 1px solid var(--fc-border);
  border-radius: var(--fc-radius-xl);
  box-shadow: var(--fc-shadow-panel);
  padding: 36px 28px 24px;
  position: relative;
  z-index: 1;
  overflow: hidden;
  opacity: 0;
  transform: translateY(20px) scale(0.97);
  transition: opacity 0.5s ease, transform 0.5s cubic-bezier(0.34, 1.56, 0.64, 1);
}

.lp-card::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    linear-gradient(135deg, rgba(255,255,255,0.18), transparent 42%),
    radial-gradient(circle at top right, rgba(182,118,57,0.08), transparent 36%);
  pointer-events: none;
}

.lp-card.is-visible {
  opacity: 1;
  transform: translateY(0) scale(1);
}

.lp-close {
  position: absolute;
  top: 16px;
  right: 16px;
  width: 40px;
  height: 40px;
  border: 1px solid var(--fc-border);
  border-radius: 50%;
  background: rgba(255, 250, 243, 0.88);
  color: var(--fc-text-sec);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: var(--fc-shadow-soft);
  transition: transform 0.22s ease, background 0.22s ease, color 0.22s ease;
  z-index: 2;
}

.lp-close:hover {
  background: #FFFFFF;
  color: var(--fc-text);
  transform: rotate(90deg);
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
  font-family: var(--fc-font);
  position: relative;
  z-index: 1;
}

.lp-brand-icon {
  font-size: 20px;
  margin-right: 6px;
}

.lp-brand-name {
  font-family: var(--fc-font-display);
  font-size: 28px;
  font-weight: 700;
  line-height: 0.98;
  color: var(--fc-text);
}

.lp-loading {
  text-align: center;
  padding: 40px 0 20px;
  position: relative;
  z-index: 1;
}

.lp-pulse {
  width: 56px;
  height: 56px;
  border-radius: 20px;
  border: 1px solid rgba(72, 49, 28, 0.12);
  background: rgba(255, 250, 243, 0.84);
  box-shadow: var(--fc-shadow-soft);
  margin: 0 auto 20px;
  animation: lp-pulse-anim 1.5s ease-in-out infinite;
  position: relative;
}

.lp-pulse::after {
  content: '⚡';
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--fc-accent-strong);
  font-size: 24px;
}

@keyframes lp-pulse-anim {
  0%, 100% { transform: translateY(0); box-shadow: 0 14px 26px rgba(61, 40, 22, 0.1); }
  50% { transform: translateY(-2px); box-shadow: 0 18px 30px rgba(61, 40, 22, 0.14); }
}

.lp-hint,
.lp-error-hint,
.lp-join-hint-label,
.lp-join-hint-id {
  font-family: var(--fc-font);
  font-size: 13px;
  color: var(--fc-text-sec);
}

.lp-error {
  text-align: center;
  padding: 24px 0 12px;
  position: relative;
  z-index: 1;
}

.lp-error-icon {
  margin-bottom: 16px;
}

.lp-error-title {
  font-family: var(--fc-font);
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
  position: relative;
  z-index: 1;
}

.lp-room-cover {
  width: 108px;
  height: 108px;
  border-radius: 32px;
  object-fit: cover;
  margin: 0 auto 18px;
  box-shadow: 0 20px 36px rgba(61, 40, 22, 0.16);
  background: rgba(243, 231, 215, 0.92);
}

.lp-room-label {
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
  color: var(--fc-text-muted);
  text-align: center;
  margin-bottom: 12px;
  text-transform: uppercase;
  letter-spacing: 0.18em;
}

.lp-room-label-soft {
  margin-bottom: 16px;
}

.lp-room-hero-title {
  font-family: var(--fc-font-display);
  font-size: 38px;
  font-weight: 700;
  color: var(--fc-text);
  line-height: 0.96;
  word-break: break-word;
}

.lp-room-hero-id {
  margin-top: 8px;
  font-family: var(--fc-font-mono);
  font-size: 12px;
  color: var(--fc-text-muted);
  letter-spacing: 0.14em;
}

.lp-divider {
  height: 1px;
  margin: 20px 24px;
  background: linear-gradient(90deg, transparent 0%, rgba(72, 49, 28, 0.16) 30%, rgba(72, 49, 28, 0.16) 70%, transparent 100%);
}

.lp-room {
  margin-bottom: 4px;
  position: relative;
  z-index: 1;
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
  font-family: var(--fc-font);
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
  border-radius: 18px;
  border: 1px solid rgba(72, 49, 28, 0.08);
  background: rgba(255, 250, 243, 0.76);
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
  padding: 16px;
  border: 1px solid transparent;
  border-radius: 18px;
  font-family: var(--fc-font);
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.25s ease;
  position: relative;
  z-index: 1;
}

.lp-btn-cta {
  margin-top: 24px;
  background: linear-gradient(135deg, #bd7b3c 0%, #8a4e22 100%);
  color: #FFFFFF;
  box-shadow: 0 18px 30px rgba(138, 78, 34, 0.22);
}

.lp-btn-cta:hover:not(:disabled) {
  box-shadow: 0 22px 34px rgba(138, 78, 34, 0.24);
  transform: translateY(-1px);
}

.lp-btn-cta:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.lp-btn-outline {
  border-color: var(--fc-border);
  background: rgba(255, 250, 243, 0.82);
  color: var(--fc-text-sec);
  padding: 10px 20px;
  width: auto;
  font-size: 14px;
}

.lp-btn-outline:hover {
  box-shadow: var(--fc-shadow-soft);
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
  font-family: var(--fc-font);
  font-size: 13px;
  font-weight: 500;
  color: var(--fc-danger);
  background: rgba(253, 236, 234, 0.86);
  border: 1px solid rgba(184, 96, 75, 0.14);
  border-radius: 14px;
  padding: 10px 16px;
  position: relative;
  z-index: 1;
}

.lp-err-fade-enter-active { transition: all 0.3s ease; }
.lp-err-fade-leave-active { transition: all 0.25s ease; }
.lp-err-fade-enter-from { opacity: 0; transform: translateY(-6px); }
.lp-err-fade-leave-to { opacity: 0; transform: translateY(-6px); }

.lp-footer {
  text-align: center;
  margin-top: 28px;
  font-family: var(--fc-font);
  font-size: 11px;
  color: var(--fc-text-muted);
  letter-spacing: 0.12em;
  position: relative;
  z-index: 1;
}

@media (max-width: 440px) {
  .lp { padding: 16px 12px; }
  .lp-card { padding: 28px 20px 20px; border-radius: 24px; }
  .lp-room-cover { width: 88px; height: 88px; border-radius: 26px; }
  .lp-room-hero-title { font-size: 30px; }
}
</style>
