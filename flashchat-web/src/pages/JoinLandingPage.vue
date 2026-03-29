<template>
  <div class="lp">
    <div class="lp-bg-orb lp-orb-1"></div>
    <div class="lp-bg-orb lp-orb-2"></div>

    <div class="lp-card" :class="{ 'is-visible': cardVisible }">

      <div class="lp-brand">
        <span class="lp-brand-icon">⚡</span>
        <span class="lp-brand-name">FlashChat</span>
      </div>

      <!-- ========== 加载态 ========== -->
      <template v-if="state === 'loading'">
        <div class="lp-loading">
          <div class="lp-pulse"></div>
          <p class="lp-hint">{{ loadingHint }}</p>
        </div>
      </template>

      <!-- ========== 错误态 ========== -->
      <template v-else-if="state === 'error'">
        <div class="lp-error">
          <div class="lp-error-icon">
            <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#D4736C" stroke-width="1.5">
              <circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/>
              <line x1="9" y1="9" x2="15" y2="15"/>
            </svg>
          </div>
          <p class="lp-error-title">{{ errorMsg }}</p>
          <p class="lp-error-hint" v-if="errorHint">{{ errorHint }}</p>
          <div class="lp-error-actions">
            <button class="lp-btn lp-btn-outline" @click="retryLoad">重试</button>
            <button class="lp-btn lp-btn-outline" @click="goHome">返回首页</button>
          </div>
        </div>
      </template>

      <!-- ========== 就绪态 ========== -->
      <template v-else>

        <div class="lp-section lp-identity" style="--delay: 0.08s">
          <div class="lp-avatar" :style="{ background: avatarColor }">
            {{ avatarInitial }}
          </div>
          <div class="lp-greeting">你好，{{ nickname }}！</div>
          <div class="lp-account-id">{{ maskedAccountId }}</div>
        </div>

        <div class="lp-divider" style="--delay: 0.14s"></div>

        <div class="lp-section lp-room" style="--delay: 0.2s">
          <div class="lp-room-label">你即将加入</div>

          <div class="lp-room-title-box">
            <div class="lp-room-title">{{ roomTitle }}</div>
          </div>

          <div class="lp-room-stats" v-if="roomInfo">
            <div class="lp-stat">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none"
                   stroke="currentColor" stroke-width="1.8">
                <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/>
                <circle cx="9" cy="7" r="4"/>
                <path d="M23 21v-2a4 4 0 0 0-3-3.87"/>
                <path d="M16 3.13a4 4 0 0 1 0 7.75"/>
              </svg>
              <span>{{ roomInfo.memberCount || 0 }} / {{ roomInfo.maxMembers || 50 }} 人</span>
            </div>
            <div class="lp-stat" v-if="roomCountdown">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none"
                   stroke="currentColor" stroke-width="1.8">
                <circle cx="12" cy="12" r="10"/>
                <polyline points="12 6 12 12 16 14"/>
              </svg>
              <span>{{ roomCountdown }}</span>
            </div>
            <div class="lp-stat">
              <span class="lp-status-dot" :style="{ background: statusColor }"></span>
              <span>{{ statusText }}</span>
            </div>
          </div>

          <div class="lp-room-minimal" v-else>
            <div class="lp-stat">
              <span class="lp-status-dot" style="background: #C8956C"></span>
              <span>房间 ID：{{ roomId }}</span>
            </div>
          </div>
        </div>

        <!-- 主按钮 -->
        <button
            class="lp-section lp-btn lp-btn-cta"
            :class="{ 'is-joining': joining }"
            :disabled="joining"
            @click="doJoin"
            style="--delay: 0.28s"
        >
          <span v-if="!joining">⚡ 进入房间</span>
          <span v-else class="lp-btn-loading">
            <span class="lp-btn-spinner"></span>
            加入中...
          </span>
        </button>

        <!-- 加入失败提示（不切换到 error 状态，允许直接重试） -->
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
import { useRouter } from 'vue-router'
import { useAuth } from '@/composables/useAuth'
import * as roomApi from '@/api/room'
import { formatCountdown } from '@/utils/formatter'

const props = defineProps({
  roomId: { type: String, required: true }
})

const router = useRouter()
const auth = useAuth()

const state = ref('loading')
const loadingHint = ref('正在准备...')
const errorMsg = ref('')
const errorHint = ref('')
const cardVisible = ref(false)
const joining = ref(false)
const joinError = ref('')
const roomInfo = ref(null)

const nickname = computed(() => auth.identity.value?.nickname || '匿名用户')
const avatarColor = computed(() => auth.identity.value?.avatarColor || '#C8956C')
const avatarInitial = computed(() => (nickname.value || '?')[0].toUpperCase())

const maskedAccountId = computed(() => {
  const id = auth.identity.value?.accountId
  if (!id || id.length < 5) return id || ''
  return id.slice(0, 3) + '····' + id.slice(-2)
})

const roomTitle = computed(() => {
  if (roomInfo.value?.title) return roomInfo.value.title
  return '房间 ' + props.roomId
})

const roomCountdown = computed(() => {
  if (!roomInfo.value?.expireTime) return null
  const remain = new Date(roomInfo.value.expireTime).getTime() - Date.now()
  if (remain <= 0) return '已过期'
  return formatCountdown(remain)
})

const statusText = computed(() => {
  if (!roomInfo.value) return ''
  return roomInfo.value.statusDesc || '未知'
})

const statusColor = computed(() => {
  if (!roomInfo.value) return '#B5B0A8'
  const s = roomInfo.value.status
  if (s === 1) return '#7BAF6E'
  if (s === 0) return '#D4A84C'
  if (s === 2) return '#D4A84C'
  if (s === 3) return '#D4736C'
  return '#B5B0A8'
})

// ==================== 初始化 ====================
onMounted(async () => {
  setTimeout(() => { cardVisible.value = true }, 50)

  try {
    loadingHint.value = '正在准备身份...'
    await auth.init()

    loadingHint.value = '获取房间信息...'
    await fetchRoomPreview()

    state.value = 'ready'
  } catch (e) {
    console.error('[Landing] 初始化失败', e)
    errorMsg.value = '连接失败'
    errorHint.value = '请确认后端服务已启动'
    state.value = 'error'
  }
})

async function fetchRoomPreview() {
  try {
    roomInfo.value = await roomApi.previewRoom(props.roomId)
    if (roomInfo.value && roomInfo.value.status === 4) {
      errorMsg.value = '该房间已关闭'
      errorHint.value = '房间已结束，无法加入'
      state.value = 'error'
    }
  } catch (e) {
    console.warn('[Landing] 房间预览不可用，使用降级模式', e.message)
    roomInfo.value = null
  }
}

// ==================== 操作 ====================

/**
 * 加入房间
 * 失败时不切换到 error 状态，而是在按钮下方显示内联错误提示
 * 用户可直接再次点击重试（如房间满了等临时问题）
 */
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
    errorHint.value = '请确认后端服务已启动'
    state.value = 'error'
  }
}

function goHome() {
  router.push('/')
}
</script>

<style scoped>
/* ==================== 布局 ==================== */
.lp {
  width: 100%; min-height: 100vh; min-height: 100dvh;
  background: #F5F0E8;
  display: flex; align-items: center; justify-content: center;
  padding: 24px 16px;
  position: relative; overflow: hidden;
}

.lp-bg-orb {
  position: absolute; border-radius: 50%;
  filter: blur(80px); opacity: 0.35;
  pointer-events: none;
}
.lp-orb-1 {
  width: 300px; height: 300px;
  background: #C8956C;
  top: -80px; right: -60px;
}
.lp-orb-2 {
  width: 250px; height: 250px;
  background: #7BAF6E;
  bottom: -60px; left: -40px;
}

/* ==================== 卡片 ==================== */
.lp-card {
  width: 100%; max-width: 400px;
  background: #F5F0E8;
  border-radius: 24px;
  box-shadow:
      8px 8px 20px #D1CBC3,
      -8px -8px 20px #FFFFFF;
  padding: 36px 28px 24px;
  position: relative; z-index: 1;

  opacity: 0;
  transform: translateY(20px) scale(0.97);
  transition: opacity 0.5s ease, transform 0.5s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.lp-card.is-visible {
  opacity: 1;
  transform: translateY(0) scale(1);
}

/* ★ 修复：动画仅在卡片可见后触发，时序完全可控 */
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

/* ==================== 品牌 ==================== */
.lp-brand {
  text-align: center; margin-bottom: 28px;
  font-family: 'Poppins', sans-serif;
}
.lp-brand-icon { font-size: 20px; margin-right: 6px; }
.lp-brand-name {
  font-size: 22px; font-weight: 700;
  color: #2C2825; letter-spacing: -0.5px;
}

/* ==================== 加载 ==================== */
.lp-loading { text-align: center; padding: 40px 0 20px; }
.lp-pulse {
  width: 44px; height: 44px; border-radius: 50%;
  background: #F5F0E8;
  box-shadow: 4px 4px 10px #D1CBC3, -4px -4px 10px #fff;
  margin: 0 auto 20px;
  animation: lp-pulse-anim 1.5s ease-in-out infinite;
}
@keyframes lp-pulse-anim {
  0%, 100% { box-shadow: 3px 3px 6px #D1CBC3, -3px -3px 6px #fff; }
  50% { box-shadow: 6px 6px 14px #D1CBC3, -6px -6px 14px #fff; }
}
.lp-hint {
  font-family: 'Poppins', sans-serif;
  font-size: 13px; color: #8A857E;
}

/* ==================== 错误 ==================== */
.lp-error { text-align: center; padding: 24px 0 12px; }
.lp-error-icon { margin-bottom: 16px; }
.lp-error-title {
  font-family: 'Poppins', sans-serif;
  font-size: 16px; font-weight: 600;
  color: #2C2825; margin-bottom: 6px;
}
.lp-error-hint {
  font-family: 'Poppins', sans-serif;
  font-size: 13px; color: #8A857E; margin-bottom: 20px;
}
.lp-error-actions { display: flex; gap: 12px; justify-content: center; }

/* ==================== 身份 ==================== */
.lp-identity { text-align: center; margin-bottom: 4px; }

.lp-avatar {
  width: 64px; height: 64px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  margin: 0 auto 14px; color: #fff;
  font-family: 'Poppins', sans-serif;
  font-size: 26px; font-weight: 600;
  box-shadow: 4px 4px 10px #D1CBC3, -4px -4px 10px #FFFFFF;
}
.lp-greeting {
  font-family: 'Poppins', sans-serif;
  font-size: 18px; font-weight: 600;
  color: #2C2825; margin-bottom: 4px;
}
.lp-account-id {
  font-family: 'Poppins', sans-serif;
  font-size: 12px; color: #B5B0A8; letter-spacing: 0.5px;
}

/* ==================== 分隔线 ==================== */
.lp-divider {
  height: 1px; margin: 20px 24px;
  background: linear-gradient(90deg, transparent 0%, #D1CBC3 30%, #D1CBC3 70%, transparent 100%);
}

/* ==================== 房间信息 ==================== */
.lp-room { margin-bottom: 4px; }
.lp-room-label {
  font-family: 'Poppins', sans-serif;
  font-size: 12px; font-weight: 500; color: #8A857E;
  text-align: center; margin-bottom: 12px;
  text-transform: uppercase; letter-spacing: 1.5px;
}
.lp-room-title-box {
  background: #EDE8DF; border-radius: 14px;
  box-shadow: inset 3px 3px 6px #D1CBC3, inset -3px -3px 6px #FFFFFF;
  padding: 16px 20px; margin-bottom: 16px; text-align: center;
}
.lp-room-title {
  font-family: 'Poppins', sans-serif;
  font-size: 20px; font-weight: 700; color: #2C2825;
  line-height: 1.3; word-break: break-word;
}
.lp-room-stats, .lp-room-minimal {
  display: flex; flex-wrap: wrap; gap: 16px; justify-content: center;
}
.lp-stat {
  display: flex; align-items: center; gap: 6px;
  font-family: 'Poppins', sans-serif;
  font-size: 13px; color: #8A857E;
}
.lp-status-dot {
  width: 8px; height: 8px; border-radius: 50%;
  display: inline-block; flex-shrink: 0;
}

/* ==================== 按钮 ==================== */
.lp-btn {
  display: flex; align-items: center; justify-content: center; gap: 6px;
  width: 100%; padding: 16px; border: none; border-radius: 14px;
  font-family: 'Poppins', sans-serif;
  font-size: 16px; font-weight: 600; cursor: pointer;
  transition: all 0.25s ease;
}
.lp-btn-cta {
  margin-top: 24px; background: #C8956C; color: #FFFFFF;
  box-shadow: 4px 4px 10px rgba(200,149,108,0.35), -2px -2px 6px rgba(255,255,255,0.5);
}
.lp-btn-cta:hover:not(:disabled) {
  box-shadow: 6px 6px 14px rgba(200,149,108,0.4), -3px -3px 8px rgba(255,255,255,0.6);
  transform: translateY(-1px);
}
.lp-btn-cta:active:not(:disabled) {
  transform: translateY(0);
  box-shadow: 2px 2px 6px rgba(200,149,108,0.3), -1px -1px 3px rgba(255,255,255,0.4);
}
.lp-btn-cta:disabled { opacity: 0.7; cursor: not-allowed; }

.lp-btn-outline {
  background: #F5F0E8; color: #8A857E;
  box-shadow: 3px 3px 6px #D1CBC3, -3px -3px 6px #FFFFFF;
  padding: 10px 20px; width: auto; font-size: 14px;
}
.lp-btn-outline:hover {
  box-shadow: inset 2px 2px 4px #D1CBC3, inset -2px -2px 4px #FFFFFF;
}

.lp-btn-loading { display: flex; align-items: center; gap: 8px; }
.lp-btn-spinner {
  width: 18px; height: 18px;
  border: 2px solid rgba(255,255,255,0.3);
  border-top-color: #fff; border-radius: 50%;
  animation: lp-spin 0.6s linear infinite;
}
@keyframes lp-spin { to { transform: rotate(360deg); } }

/* ==================== 加入失败内联提示 ==================== */
.lp-join-error {
  text-align: center; margin-top: 12px;
  font-family: 'Poppins', sans-serif;
  font-size: 13px; font-weight: 500;
  color: #D4736C;
  background: rgba(212, 115, 108, 0.08);
  border-radius: 10px;
  padding: 10px 16px;
}
.lp-err-fade-enter-active { transition: all 0.3s ease; }
.lp-err-fade-leave-active { transition: all 0.25s ease; }
.lp-err-fade-enter-from { opacity: 0; transform: translateY(-6px); }
.lp-err-fade-leave-to { opacity: 0; transform: translateY(-6px); }

/* ==================== 底部 ==================== */
.lp-footer {
  text-align: center; margin-top: 28px;
  font-family: 'Poppins', sans-serif;
  font-size: 11px; color: #C8C3BB;
}

/* ==================== 响应式 ==================== */
@media (max-width: 440px) {
  .lp { padding: 16px 12px; }
  .lp-card { padding: 28px 20px 20px; border-radius: 20px; }
  .lp-room-title { font-size: 18px; }
  .lp-avatar { width: 56px; height: 56px; font-size: 22px; }
}
</style>