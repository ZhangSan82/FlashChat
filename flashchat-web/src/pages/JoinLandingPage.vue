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
  background: #F5F0E8;
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
  opacity: 0.35;
  pointer-events: none;
}

.lp-orb-1 {
  width: 300px;
  height: 300px;
  background: #C8956C;
  top: -80px;
  right: -60px;
}

.lp-orb-2 {
  width: 250px;
  height: 250px;
  background: #DCC1A3;
  bottom: -60px;
  left: -40px;
}

.lp-card {
  width: 100%;
  max-width: 400px;
  background: #F5F0E8;
  border-radius: 24px;
  box-shadow:
    8px 8px 20px #D1CBC3,
    -8px -8px 20px #FFFFFF;
  padding: 36px 28px 24px;
  position: relative;
  z-index: 1;
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
  width: 40px;
  height: 40px;
  border: none;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.68);
  color: #8A857E;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: 3px 3px 10px rgba(209, 203, 195, 0.76);
  transition: transform 0.22s ease, background 0.22s ease, color 0.22s ease;
  z-index: 2;
}

.lp-close:hover {
  background: #FFFFFF;
  color: #2C2825;
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
}

.lp-brand-icon {
  font-size: 20px;
  margin-right: 6px;
}

.lp-brand-name {
  font-size: 22px;
  font-weight: 700;
  color: #2C2825;
  letter-spacing: -0.5px;
}

.lp-loading {
  text-align: center;
  padding: 40px 0 20px;
}

.lp-pulse {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: #F5F0E8;
  box-shadow: 4px 4px 10px #D1CBC3, -4px -4px 10px #fff;
  margin: 0 auto 20px;
  animation: lp-pulse-anim 1.5s ease-in-out infinite;
}

@keyframes lp-pulse-anim {
  0%, 100% { box-shadow: 3px 3px 6px #D1CBC3, -3px -3px 6px #fff; }
  50% { box-shadow: 6px 6px 14px #D1CBC3, -6px -6px 14px #fff; }
}

.lp-hint,
.lp-error-hint,
.lp-join-hint-label,
.lp-join-hint-id {
  font-family: var(--fc-font);
  font-size: 13px;
  color: #8A857E;
}

.lp-error {
  text-align: center;
  padding: 24px 0 12px;
}

.lp-error-icon {
  margin-bottom: 16px;
}

.lp-error-title {
  font-family: var(--fc-font);
  font-size: 16px;
  font-weight: 600;
  color: #2C2825;
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
  width: 96px;
  height: 96px;
  border-radius: 28px;
  object-fit: cover;
  margin: 0 auto 18px;
  box-shadow: 0 20px 36px rgba(61, 40, 22, 0.16);
  background: #EDE8DF;
}

.lp-room-label {
  font-family: var(--fc-font);
  font-size: 12px;
  font-weight: 500;
  color: #8A857E;
  text-align: center;
  margin-bottom: 12px;
  text-transform: uppercase;
  letter-spacing: 1.5px;
}

.lp-room-label-soft {
  margin-bottom: 16px;
}

.lp-room-hero-title {
  font-family: var(--fc-font);
  font-size: 30px;
  font-weight: 700;
  color: #2C2825;
  line-height: 1.1;
  word-break: break-word;
}

.lp-room-hero-id {
  margin-top: 8px;
  font-family: var(--fc-font);
  font-size: 12px;
  color: #B5B0A8;
  letter-spacing: 0.14em;
}

.lp-divider {
  height: 1px;
  margin: 20px 24px;
  background: linear-gradient(90deg, transparent 0%, #D1CBC3 30%, #D1CBC3 70%, transparent 100%);
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
  font-family: var(--fc-font);
  font-size: 13px;
  color: #8A857E;
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
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.44);
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
  border: none;
  border-radius: 14px;
  font-family: var(--fc-font);
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.25s ease;
}

.lp-btn-cta {
  margin-top: 24px;
  background: #C8956C;
  color: #FFFFFF;
  box-shadow: 4px 4px 10px rgba(200,149,108,0.35), -2px -2px 6px rgba(255,255,255,0.5);
}

.lp-btn-cta:hover:not(:disabled) {
  box-shadow: 6px 6px 14px rgba(200,149,108,0.4), -3px -3px 8px rgba(255,255,255,0.6);
  transform: translateY(-1px);
}

.lp-btn-cta:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.lp-btn-outline {
  background: #F5F0E8;
  color: #8A857E;
  box-shadow: 3px 3px 6px #D1CBC3, -3px -3px 6px #FFFFFF;
  padding: 10px 20px;
  width: auto;
  font-size: 14px;
}

.lp-btn-outline:hover {
  box-shadow: inset 2px 2px 4px #D1CBC3, inset -2px -2px 4px #FFFFFF;
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
  color: #D4736C;
  background: rgba(212, 115, 108, 0.08);
  border-radius: 10px;
  padding: 10px 16px;
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
  color: #C8C3BB;
}

@media (max-width: 440px) {
  .lp { padding: 16px 12px; }
  .lp-card { padding: 28px 20px 20px; border-radius: 20px; }
  .lp-room-cover { width: 82px; height: 82px; border-radius: 24px; }
  .lp-room-hero-title { font-size: 24px; }
}
</style>
