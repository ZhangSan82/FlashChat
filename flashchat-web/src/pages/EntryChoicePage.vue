<template>
  <div class="ec">
    <!-- atmospheric backdrop -->
    <div class="ec-atmos" aria-hidden="true">
      <div class="ec-atmos-grain"></div>
      <div class="ec-atmos-blur ec-atmos-blur-a"></div>
      <div class="ec-atmos-blur ec-atmos-blur-b"></div>
      <div class="ec-atmos-blur ec-atmos-blur-c"></div>
    </div>

    <div class="ec-card" :class="{ 'is-visible': cardVisible }">
      <!-- brand row with scan context -->
      <div class="ec-topbar">
        <div class="ec-brand">
          <span class="ec-brand-icon" aria-hidden="true">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <path d="M13 2L5 14h5l-1 8 8-12h-5l1-8z" />
            </svg>
          </span>
          <span class="ec-brand-name">FlashChat</span>
        </div>

        <span class="ec-scan-pill" aria-label="扫码进入">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <rect x="3" y="3" width="7" height="7" rx="1.2" />
            <rect x="14" y="3" width="7" height="7" rx="1.2" />
            <rect x="3" y="14" width="7" height="7" rx="1.2" />
            <path d="M14 14h3v3M21 14v7M14 21h3" />
          </svg>
          <span>扫码进入</span>
        </span>
      </div>

      <!-- loading -->
      <template v-if="state === 'loading'">
        <div class="ec-loading">
          <div class="ec-pulse"></div>
          <p class="ec-hint">{{ loadingHint }}</p>
        </div>
      </template>

      <!-- error -->
      <template v-else-if="state === 'error'">
        <div class="ec-error">
          <div class="ec-error-icon">
            <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="#D4736C" stroke-width="1.5">
              <circle cx="12" cy="12" r="10" />
              <line x1="15" y1="9" x2="9" y2="15" />
              <line x1="9" y1="9" x2="15" y2="15" />
            </svg>
          </div>
          <p class="ec-error-title">{{ errorMsg }}</p>
          <p class="ec-error-sub" v-if="errorHint">{{ errorHint }}</p>
          <button class="ec-btn ec-btn-outline" @click="retryLoad">重试</button>
        </div>
      </template>

      <!-- ready: room preview + choice -->
      <template v-else>
        <!-- editorial label -->
        <div class="ec-section ec-eyebrow" style="--delay: 0.04s">
          <span class="ec-eyebrow-line"></span>
          <span class="ec-eyebrow-text">房间邀请</span>
        </div>

        <!-- room hero: cover + stacked info -->
        <div class="ec-section ec-room" style="--delay: 0.10s">
          <div class="ec-room-cover-wrap">
            <img class="ec-room-cover" :src="roomVisualUrl" :alt="roomTitle" width="76" height="76" />
            <span class="ec-room-cover-ring" aria-hidden="true"></span>
          </div>
          <div class="ec-room-body">
            <div class="ec-room-title" :title="roomTitle">{{ roomTitle }}</div>
            <div class="ec-room-meta">
              <span class="ec-room-chip">
                <span class="ec-room-dot" :style="{ background: statusColor }"></span>
                {{ statusText }}
              </span>
              <span class="ec-room-chip ec-room-chip-muted">
                <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M17 21v-2a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4v2" />
                  <circle cx="10" cy="7" r="4" />
                  <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
                </svg>
                {{ roomMembers }}
              </span>
            </div>
          </div>
        </div>

        <!-- choice area -->
        <div class="ec-section ec-choice" style="--delay: 0.20s">
          <!-- primary: quick entry -->
          <button
            class="ec-btn ec-btn-primary"
            :disabled="quickEntering"
            @click="doQuickEntry"
          >
            <span v-if="!quickEntering" class="ec-btn-inner">
              <svg class="ec-btn-icon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M13 2L5 14h5l-1 8 8-12h-5l1-8z" />
              </svg>
              快速进入房间
              <svg class="ec-btn-arrow" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <line x1="5" y1="12" x2="19" y2="12" />
                <polyline points="12 5 19 12 12 19" />
              </svg>
            </span>
            <span v-else class="ec-btn-inner">
              <span class="ec-spinner"></span>
              正在进入...
            </span>
          </button>

          <!-- trust chips -->
          <ul class="ec-trust" aria-label="快速进入特点">
            <li>
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                <polyline points="20 6 9 17 4 12" />
              </svg>
              无需注册
            </li>
            <li>
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                <polyline points="20 6 9 17 4 12" />
              </svg>
              立即开聊
            </li>
            <li>
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                <polyline points="20 6 9 17 4 12" />
              </svg>
              随时可升级
            </li>
          </ul>

          <!-- secondary: login link row -->
          <div class="ec-login-row">
            <span class="ec-login-text">已经有账号?</span>
            <button class="ec-login-link" type="button" @click="goLogin">
              登录已有账号
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <line x1="5" y1="12" x2="19" y2="12" />
                <polyline points="12 5 19 12 12 19" />
              </svg>
            </button>
          </div>
        </div>

        <!-- quick entry error -->
        <transition name="ec-err-fade">
          <p v-if="quickError" class="ec-quick-error">{{ quickError }}</p>
        </transition>
      </template>

      <div class="ec-footer">
        <span>FlashChat</span>
        <span class="ec-footer-sep">·</span>
        <span>v1.0</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { autoRegister } from '@/api/account'
import * as roomApi from '@/api/room'
import { saveToken } from '@/utils/storage'
import { useAuth } from '@/composables/useAuth'
import { getRoomDisplayName, getRoomVisualUrl } from '@/utils/roomVisual'

const props = defineProps({
  roomId: { type: String, required: true }
})

const router = useRouter()
const route = useRoute()
const auth = useAuth()

const state = ref('loading')
const loadingHint = ref('获取房间信息...')
const errorMsg = ref('')
const errorHint = ref('')
const cardVisible = ref(false)
const quickEntering = ref(false)
const quickError = ref('')
const roomInfo = ref(null)

const roomTitle = computed(() => getRoomDisplayName(roomInfo.value || { roomId: props.roomId }))

const roomVisualUrl = computed(() => getRoomVisualUrl(roomInfo.value || {
  roomId: props.roomId,
  title: roomTitle.value
}))

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
  await fetchRoomPreview()
})

async function fetchRoomPreview() {
  try {
    roomInfo.value = await roomApi.previewRoom(props.roomId)
    if (roomInfo.value && roomInfo.value.status === 4) {
      errorMsg.value = '这个房间已经关闭'
      errorHint.value = '房间会话已经结束，暂时无法加入'
      state.value = 'error'
      return
    }
    state.value = 'ready'
  } catch (e) {
    roomInfo.value = null
    state.value = 'ready'
  }
}

async function retryLoad() {
  state.value = 'loading'
  loadingHint.value = '获取房间信息...'
  errorMsg.value = ''
  errorHint.value = ''
  await fetchRoomPreview()
}

async function doQuickEntry() {
  quickEntering.value = true
  quickError.value = ''

  try {
    const resp = await autoRegister()
    saveToken(resp.token)
    auth.onAuthRefreshed(resp)
    sessionStorage.setItem('fc_quick_entry', '1')
    router.replace({ name: 'JoinRoom', params: { roomId: props.roomId } })
  } catch (e) {
    quickError.value = e.message || '进入失败，请重试'
    setTimeout(() => { quickError.value = '' }, 4000)
  } finally {
    quickEntering.value = false
  }
}

function goLogin() {
  router.push({
    name: 'Login',
    query: { redirect: `/room/${props.roomId}` }
  })
}
</script>

<style scoped>
.ec {
  position: relative;
  width: 100%;
  min-height: 100vh;
  min-height: 100dvh;
  background: var(--fc-app-gradient);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px 16px;
  overflow: hidden;
  isolation: isolate;
}

/* ── atmospheric backdrop ── */
.ec-atmos {
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
}

.ec-atmos-grain {
  position: absolute;
  inset: 0;
  opacity: 0.5;
  background-image:
    radial-gradient(circle at 20% 20%, rgba(182, 118, 57, 0.06) 0, transparent 40%),
    radial-gradient(circle at 80% 90%, rgba(151, 90, 38, 0.05) 0, transparent 45%);
}

.ec-atmos-blur {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  pointer-events: none;
}

.ec-atmos-blur-a {
  width: 340px;
  height: 340px;
  top: -80px;
  right: -60px;
  background: rgba(214, 176, 132, 0.35);
  animation: ec-drift-a 18s ease-in-out infinite;
}

.ec-atmos-blur-b {
  width: 380px;
  height: 380px;
  bottom: -120px;
  left: -80px;
  background: rgba(182, 118, 57, 0.18);
  animation: ec-drift-b 22s ease-in-out infinite;
}

.ec-atmos-blur-c {
  width: 240px;
  height: 240px;
  top: 40%;
  left: 55%;
  background: rgba(123, 175, 110, 0.08);
  animation: ec-drift-c 26s ease-in-out infinite;
}

@keyframes ec-drift-a {
  0%, 100% { transform: translate(0, 0); }
  50% { transform: translate(-24px, 16px); }
}

@keyframes ec-drift-b {
  0%, 100% { transform: translate(0, 0); }
  50% { transform: translate(18px, -20px); }
}

@keyframes ec-drift-c {
  0%, 100% { transform: translate(0, 0); }
  50% { transform: translate(-30px, 22px); }
}

/* ── card ── */
.ec-card {
  position: relative;
  width: 100%;
  max-width: 420px;
  background: var(--fc-surface-strong);
  border: 1px solid var(--fc-border);
  border-radius: var(--fc-radius-xl);
  box-shadow: var(--fc-shadow-panel);
  padding: 22px 28px 22px;
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  opacity: 0;
  transform: translateY(22px) scale(0.96);
  transition:
    opacity var(--fc-duration-slow) var(--fc-ease-out),
    transform var(--fc-duration-slow) var(--fc-ease-bounce);
}

.ec-card.is-visible {
  opacity: 1;
  transform: translateY(0) scale(1);
}

/* subtle top highlight */
.ec-card::before {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.8);
  pointer-events: none;
}

/* ── topbar (brand + scan pill) ── */
.ec-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 22px;
}

.ec-brand {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.ec-brand-icon {
  width: 30px;
  height: 30px;
  border-radius: 10px;
  border: 1px solid var(--fc-border);
  background: var(--fc-surface);
  color: var(--fc-accent-strong);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  box-shadow: var(--fc-shadow-in);
}

.ec-brand-name {
  font-family: var(--fc-font-display);
  font-size: 17px;
  font-weight: 600;
  color: var(--fc-text);
  letter-spacing: -0.01em;
}

.ec-scan-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px 5px 9px;
  border-radius: var(--fc-radius-pill);
  background: var(--fc-selected-bg-soft);
  color: var(--fc-accent-strong);
  border: 1px solid var(--fc-selected-border);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
  position: relative;
  overflow: hidden;
}

.ec-scan-pill::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(110deg, transparent 30%, rgba(255, 255, 255, 0.55) 50%, transparent 70%);
  transform: translateX(-120%);
  animation: ec-shimmer 3.6s ease-in-out infinite;
}

@keyframes ec-shimmer {
  0%, 60% { transform: translateX(-120%); }
  80%, 100% { transform: translateX(120%); }
}

/* ── staggered section entrance ── */
.ec-section {
  opacity: 0;
  transform: translateY(10px);
}

.ec-card.is-visible .ec-section {
  animation: ec-in 0.45s var(--fc-ease-out) forwards;
  animation-delay: var(--delay, 0s);
}

@keyframes ec-in {
  to { opacity: 1; transform: translateY(0); }
}

/* ── editorial eyebrow ── */
.ec-eyebrow {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}

.ec-eyebrow-line {
  width: 22px;
  height: 1px;
  background: var(--fc-accent-soft);
}

.ec-eyebrow-text {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--fc-accent-strong);
}

/* ── loading ── */
.ec-loading {
  text-align: center;
  padding: 36px 0 24px;
}

.ec-pulse {
  width: 48px;
  height: 48px;
  border-radius: 16px;
  border: 1px solid var(--fc-border);
  background: var(--fc-surface);
  margin: 0 auto 18px;
  animation: ec-pulse-bob 1.5s ease-in-out infinite;
  position: relative;
  box-shadow: var(--fc-shadow-soft);
}

.ec-pulse::after {
  content: '\26A1';
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--fc-accent);
  font-size: 20px;
}

@keyframes ec-pulse-bob {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-3px); }
}

.ec-hint {
  font-size: 13px;
  color: var(--fc-text-sec);
}

/* ── error ── */
.ec-error {
  text-align: center;
  padding: 20px 0 4px;
}

.ec-error-icon { margin-bottom: 12px; }

.ec-error-title {
  font-family: var(--fc-font-display);
  font-size: 17px;
  font-weight: 600;
  color: var(--fc-text);
  margin: 0 0 6px;
}

.ec-error-sub {
  font-size: 13px;
  color: var(--fc-text-sec);
  margin: 0 0 20px;
}

/* ── room hero: horizontal layout ── */
.ec-room {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px;
  margin-bottom: 20px;
  border-radius: var(--fc-radius);
  background: linear-gradient(135deg, var(--fc-bg-light), var(--fc-surface-muted));
  border: 1px solid var(--fc-border);
  position: relative;
  overflow: hidden;
}

.ec-room::before {
  content: '';
  position: absolute;
  top: -40px;
  right: -40px;
  width: 140px;
  height: 140px;
  background: radial-gradient(circle, var(--fc-accent-veil) 0%, transparent 70%);
  pointer-events: none;
}

.ec-room-cover-wrap {
  position: relative;
  flex-shrink: 0;
}

.ec-room-cover {
  width: 64px;
  height: 64px;
  border-radius: 18px;
  object-fit: cover;
  background: linear-gradient(135deg, #E8D5BF, #C9A87C);
  box-shadow: var(--fc-shadow-elevated);
  display: block;
}

.ec-room-cover-ring {
  position: absolute;
  inset: -4px;
  border-radius: 22px;
  border: 1px dashed var(--fc-accent-weak);
  pointer-events: none;
  animation: ec-ring-spin 20s linear infinite;
}

@keyframes ec-ring-spin {
  to { transform: rotate(360deg); }
}

.ec-room-body {
  flex: 1;
  min-width: 0;
  position: relative;
  z-index: 1;
}

.ec-room-title {
  font-family: var(--fc-font-display);
  font-size: 19px;
  font-weight: 600;
  color: var(--fc-text);
  line-height: 1.25;
  letter-spacing: -0.01em;
  margin-bottom: 8px;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  word-break: break-word;
}

.ec-room-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.ec-room-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 9px;
  border-radius: var(--fc-radius-pill);
  background: var(--fc-surface-strong);
  border: 1px solid var(--fc-border);
  font-size: 11.5px;
  font-weight: 500;
  color: var(--fc-text-sec);
}

.ec-room-chip-muted {
  color: var(--fc-text-muted);
}

.ec-room-chip svg {
  flex-shrink: 0;
}

.ec-room-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}

/* ── choice area ── */
.ec-choice {
  display: flex;
  flex-direction: column;
}

/* ── buttons ── */
.ec-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  padding: 14px 16px;
  border: 1px solid transparent;
  border-radius: 14px;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  transition:
    background var(--fc-duration-normal) var(--fc-ease-in-out),
    border-color var(--fc-duration-normal) var(--fc-ease-in-out),
    box-shadow var(--fc-duration-normal) var(--fc-ease-in-out),
    transform var(--fc-duration-fast) ease;
  font-family: inherit;
}

.ec-btn:active:not(:disabled) {
  transform: scale(0.985);
}

.ec-btn-inner {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.ec-btn-icon {
  flex-shrink: 0;
}

.ec-btn-primary {
  background: linear-gradient(135deg, var(--fc-accent) 0%, var(--fc-accent-strong) 100%);
  color: #fff;
  box-shadow:
    0 10px 24px rgba(151, 90, 38, 0.24),
    inset 0 1px 0 rgba(255, 255, 255, 0.22);
  position: relative;
  overflow: hidden;
}

.ec-btn-primary::before {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(110deg, transparent 30%, rgba(255, 255, 255, 0.22) 50%, transparent 70%);
  transform: translateX(-100%);
  transition: transform 0.6s var(--fc-ease-out);
}

.ec-btn-primary:hover:not(:disabled) {
  box-shadow:
    0 14px 32px rgba(151, 90, 38, 0.3),
    inset 0 1px 0 rgba(255, 255, 255, 0.3);
  transform: translateY(-1px);
}

.ec-btn-primary:hover:not(:disabled)::before {
  transform: translateX(100%);
}

.ec-btn-primary:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.ec-btn-arrow {
  opacity: 0.85;
  transition: transform var(--fc-duration-normal) var(--fc-ease-out);
}

.ec-btn-primary:hover:not(:disabled) .ec-btn-arrow {
  transform: translateX(3px);
}

.ec-btn-outline {
  border-color: var(--fc-border-strong);
  background: var(--fc-surface);
  color: var(--fc-text);
}

.ec-btn-outline:hover {
  border-color: var(--fc-accent-soft);
  background: var(--fc-selected-bg);
}

/* ── trust chips ── */
.ec-trust {
  list-style: none;
  padding: 0;
  margin: 12px 0 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  flex-wrap: wrap;
}

.ec-trust li {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border-radius: var(--fc-radius-pill);
  background: rgba(123, 175, 110, 0.1);
  color: #4f7a45;
  border: 1px solid rgba(123, 175, 110, 0.18);
  font-size: 11.5px;
  font-weight: 600;
  letter-spacing: 0.01em;
}

.ec-trust li svg {
  color: #7BAF6E;
  flex-shrink: 0;
}

/* ── login row ── */
.ec-login-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding-top: 18px;
  border-top: 1px solid var(--fc-border);
  font-size: 13.5px;
}

.ec-login-text {
  color: var(--fc-text-sec);
}

.ec-login-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: transparent;
  border: none;
  color: var(--fc-accent-strong);
  font-weight: 600;
  font-size: 13.5px;
  cursor: pointer;
  padding: 2px 0;
  transition: color var(--fc-duration-fast) ease, transform var(--fc-duration-fast) ease;
  font-family: inherit;
}

.ec-login-link:hover {
  color: var(--fc-accent);
}

.ec-login-link:hover svg {
  transform: translateX(2px);
}

.ec-login-link svg {
  transition: transform var(--fc-duration-normal) var(--fc-ease-out);
}

/* ── spinner ── */
.ec-spinner {
  width: 18px;
  height: 18px;
  border: 2px solid rgba(255,255,255,0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: ec-spin 0.6s linear infinite;
}

@keyframes ec-spin { to { transform: rotate(360deg); } }

/* ── quick entry error ── */
.ec-quick-error {
  text-align: center;
  margin: 14px 0 0;
  font-size: 13px;
  font-weight: 500;
  color: var(--fc-danger);
  background: rgba(184, 96, 75, 0.06);
  border: 1px solid rgba(184, 96, 75, 0.14);
  border-radius: 12px;
  padding: 10px 16px;
}

.ec-err-fade-enter-active,
.ec-err-fade-leave-active { transition: all var(--fc-duration-normal) var(--fc-ease-in-out); }
.ec-err-fade-enter-from,
.ec-err-fade-leave-to { opacity: 0; transform: translateY(-6px); }

/* ── footer ── */
.ec-footer {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin-top: 22px;
  font-family: var(--fc-font-mono);
  font-size: 10.5px;
  color: var(--fc-text-muted);
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.ec-footer-sep {
  opacity: 0.6;
}

/* ── mobile ── */
@media (max-width: 440px) {
  .ec { padding: 20px 14px; }
  .ec-card {
    padding: 20px 22px;
    border-radius: var(--fc-radius-lg);
  }
  .ec-topbar { margin-bottom: 20px; }
  .ec-room { padding: 14px; gap: 14px; }
  .ec-room-cover { width: 56px; height: 56px; border-radius: 16px; }
  .ec-room-title { font-size: 17px; }
  .ec-btn { padding: 13px 16px; font-size: 14.5px; }
  .ec-atmos-blur-a { width: 260px; height: 260px; }
  .ec-atmos-blur-b { width: 280px; height: 280px; }
}

@media (prefers-reduced-motion: reduce) {
  .ec-card,
  .ec-section,
  .ec-atmos-blur,
  .ec-scan-pill::after,
  .ec-btn-primary::before,
  .ec-room-cover-ring,
  .ec-pulse {
    animation: none !important;
    transition: none !important;
  }

  .ec-card,
  .ec-section {
    opacity: 1 !important;
    transform: none !important;
  }
}
</style>
