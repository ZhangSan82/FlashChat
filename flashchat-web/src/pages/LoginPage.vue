<template>
  <div class="lg">
    <!-- Left: animated characters (desktop only) -->
    <div class="lg-left">
      <div class="lg-left-brand">
        <span class="lg-left-brand-icon">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M13 2L5 14h5l-1 8 8-12h-5l1-8z" />
          </svg>
        </span>
        <span>FlashChat</span>
      </div>

      <div class="lg-left-stage">
        <AnimatedCharacters
          :is-typing="isInputFocused"
          :show-password="showPassword"
          :password-length="form.password.length"
        />
      </div>

      <div class="lg-left-footer">FlashChat v1.0</div>
      <div class="lg-left-blur lg-left-blur-a"></div>
      <div class="lg-left-blur lg-left-blur-b"></div>
    </div>

    <!-- Right: login form -->
    <div class="lg-right">
      <!-- atmospheric backdrop (mobile only) -->
      <div class="lg-atmos" aria-hidden="true">
        <div class="lg-atmos-blur lg-atmos-blur-a"></div>
        <div class="lg-atmos-blur lg-atmos-blur-b"></div>
      </div>

      <!-- mobile header -->
      <div class="lg-mobile-header">
        <button class="lg-mobile-back" @click="goBack" aria-label="返回">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <line x1="19" y1="12" x2="5" y2="12" /><polyline points="12 19 5 12 12 5" />
          </svg>
        </button>
        <div class="lg-mobile-brand">
          <span class="lg-mobile-brand-icon">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <path d="M13 2L5 14h5l-1 8 8-12h-5l1-8z" />
            </svg>
          </span>
          <span>FlashChat</span>
        </div>
        <div class="lg-mobile-back-placeholder"></div>
      </div>

      <div class="lg-form-wrap">
        <div class="lg-form">
          <!-- quick entry invite banner (when arrived from room scan) -->
          <transition name="lg-invite">
            <button
              v-if="quickEntryRoomId"
              class="lg-invite"
              type="button"
              @click="goQuickEntry"
              :aria-label="`检测到房间邀请,无需登录快速进入房间 ${quickEntryRoomId}`"
            >
              <span class="lg-invite-icon" aria-hidden="true">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M13 2L5 14h5l-1 8 8-12h-5l1-8z" />
                </svg>
              </span>
              <span class="lg-invite-body">
                <span class="lg-invite-title">检测到房间邀请</span>
                <span class="lg-invite-sub">无需登录,直接进入房间</span>
              </span>
              <span class="lg-invite-cta">
                快速进入
                <svg class="lg-invite-arrow" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                  <line x1="5" y1="12" x2="19" y2="12" />
                  <polyline points="12 5 19 12 12 19" />
                </svg>
              </span>
            </button>
          </transition>

          <div class="lg-form-head">
            <div class="lg-eyebrow">
              <span class="lg-eyebrow-line"></span>
              <span>账号登录</span>
            </div>
            <h1 class="lg-title">欢迎回来</h1>
            <p class="lg-subtitle">{{ loginMethod === 'email' ? '使用邮箱与密码登录,继续你的对话' : '使用账号 ID 与密码登录,继续你的对话' }}</p>
          </div>

          <div class="lg-method" role="tablist" aria-label="登录方式">
            <button
              type="button"
              role="tab"
              :aria-selected="loginMethod === 'accountId'"
              class="lg-method-tab"
              :class="{ 'is-active': loginMethod === 'accountId' }"
              @click="switchMethod('accountId')"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <rect x="3" y="5" width="18" height="14" rx="2" />
                <path d="M7 10h4M7 14h8" />
              </svg>
              账号 ID
            </button>
            <button
              type="button"
              role="tab"
              :aria-selected="loginMethod === 'email'"
              class="lg-method-tab"
              :class="{ 'is-active': loginMethod === 'email' }"
              @click="switchMethod('email')"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <path d="M4 6h16v12H4z" />
                <path d="m4 7 8 6 8-6" />
              </svg>
              邮箱
            </button>
            <span class="lg-method-glider" :data-method="loginMethod" aria-hidden="true"></span>
          </div>

          <transition name="lg-swap" mode="out-in">
            <div v-if="loginMethod === 'email'" key="email" class="lg-grp">
              <label class="lg-label" for="lg-email">邮箱</label>
              <input
                id="lg-email"
                v-model.trim="form.email"
                type="email"
                placeholder="you@example.com"
                class="lg-input"
                autocomplete="email"
                inputmode="email"
                @focus="isInputFocused = true"
                @blur="isInputFocused = false"
                @keyup.enter="doLogin"
              />
            </div>
            <div v-else key="accountId" class="lg-grp">
              <label class="lg-label" for="lg-account">账号 ID</label>
              <input
                id="lg-account"
                v-model="form.accountId"
                type="text"
                placeholder="如 FC-8A3D7K"
                class="lg-input lg-input-mono"
                autocomplete="username"
                @focus="isInputFocused = true"
                @blur="isInputFocused = false"
              />
            </div>
          </transition>

          <div class="lg-grp">
            <label class="lg-label" for="lg-pw">密码</label>
            <div class="lg-input-wrap">
              <input
                id="lg-pw"
                v-model="form.password"
                :type="showPassword ? 'text' : 'password'"
                placeholder="输入密码"
                class="lg-input lg-input-pw"
                autocomplete="current-password"
                @focus="isInputFocused = true"
                @blur="isInputFocused = false"
                @keyup.enter="doLogin"
              />
              <button
                type="button"
                class="lg-pw-toggle"
                @click="showPassword = !showPassword"
                :aria-label="showPassword ? '隐藏密码' : '显示密码'"
              >
                <svg v-if="!showPassword" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
                  <circle cx="12" cy="12" r="3" />
                </svg>
                <svg v-else width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24" />
                  <line x1="1" y1="1" x2="23" y2="23" />
                </svg>
              </button>
            </div>
          </div>

          <transition name="lg-err">
            <p v-if="error" class="lg-error">{{ error }}</p>
          </transition>

          <button
            class="lg-btn lg-btn-primary"
            :disabled="submitting"
            @click="doLogin"
          >
            <span v-if="!submitting" class="lg-btn-inner">
              登录
              <svg class="lg-btn-arrow" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <line x1="5" y1="12" x2="19" y2="12" />
                <polyline points="12 5 19 12 12 19" />
              </svg>
            </span>
            <span v-else class="lg-btn-inner">
              <span class="lg-spinner"></span>
              登录中...
            </span>
          </button>

          <p class="lg-signup-hint">
            没有账号?
            <a class="lg-signup-link" @click.prevent="goRegister">注册账号</a>
          </p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login, loginByEmail } from '@/api/account'
import { saveToken } from '@/utils/storage'
import { useAuth } from '@/composables/useAuth'
import AnimatedCharacters from '@/components/AnimatedCharacters.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuth()

const LOGIN_METHOD_KEY = 'flashchat.login.method'
const initialMethod = (() => {
  try {
    const saved = localStorage.getItem(LOGIN_METHOD_KEY)
    return saved === 'email' ? 'email' : 'accountId'
  } catch {
    return 'accountId'
  }
})()

const loginMethod = ref(initialMethod)
const form = reactive({ accountId: '', email: '', password: '' })
const error = ref('')
const submitting = ref(false)
const showPassword = ref(false)
const isInputFocused = ref(false)

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

function switchMethod(method) {
  if (method !== 'email' && method !== 'accountId') return
  if (loginMethod.value === method) return
  loginMethod.value = method
  error.value = ''
  try {
    localStorage.setItem(LOGIN_METHOD_KEY, method)
  } catch {
    // storage unavailable — ignore
  }
}

const quickEntryRoomId = computed(() => {
  const redirect = route.query.redirect
  if (!redirect || typeof redirect !== 'string') return null
  const m = redirect.match(/^\/room\/([^\/\?#]+)/)
  return m ? m[1] : null
})

async function doLogin() {
  error.value = ''

  if (!form.password) { error.value = '请输入密码'; return }

  let request
  if (loginMethod.value === 'email') {
    const email = form.email.trim().toLowerCase()
    if (!email) { error.value = '请输入邮箱'; return }
    if (!EMAIL_PATTERN.test(email)) { error.value = '邮箱格式不正确'; return }
    request = loginByEmail({ email, password: form.password })
  } else {
    const accountId = form.accountId.trim()
    if (!accountId) { error.value = '请输入账号 ID'; return }
    request = login({ accountId, password: form.password })
  }

  submitting.value = true
  try {
    const resp = await request
    saveToken(resp.token)
    auth.onAuthRefreshed(resp)

    const redirect = route.query.redirect
    if (redirect && redirect.startsWith('/')) {
      router.replace(redirect)
    } else {
      router.replace('/')
    }
  } catch (e) {
    const fallback = loginMethod.value === 'email'
      ? '登录失败,请检查邮箱和密码'
      : '登录失败,请检查账号和密码'
    error.value = e.message || fallback
  } finally {
    submitting.value = false
  }
}

function goBack() {
  if (quickEntryRoomId.value) {
    router.push({ name: 'EntryChoice', query: { roomId: quickEntryRoomId.value } })
    return
  }
  router.push({ name: 'Welcome' })
}

function goQuickEntry() {
  if (!quickEntryRoomId.value) return
  router.push({ name: 'EntryChoice', query: { roomId: quickEntryRoomId.value } })
}

function goRegister() {
  router.push({ name: 'Register', query: { redirect: route.query.redirect || '/' } })
}
</script>

<style scoped>
.lg {
  display: grid;
  grid-template-columns: 1fr 1fr;
  min-height: 100vh;
  min-height: 100dvh;
  max-height: 100vh;
  overflow: hidden;
}

/* ── left panel ── */
.lg-left {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 40px 48px;
  background: linear-gradient(135deg, #A0896E, #8B7355, #766045);
  color: #fff;
  overflow: hidden;
}

.lg-left-brand {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 10px;
  font-family: var(--fc-font-display);
  font-size: 18px;
  font-weight: 600;
}

.lg-left-brand-icon {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: rgba(255,255,255,0.12);
  backdrop-filter: blur(4px);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.lg-left-stage {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  flex: 1;
  padding-bottom: 20px;
}

.lg-left-footer {
  position: relative;
  z-index: 2;
  font-size: 12px;
  color: rgba(255,255,255,0.5);
  letter-spacing: 0.08em;
}

.lg-left-blur {
  position: absolute;
  border-radius: 50%;
  pointer-events: none;
}

.lg-left-blur-a {
  width: 260px;
  height: 260px;
  top: 20%;
  right: 20%;
  background: rgba(182, 118, 57, 0.15);
  filter: blur(80px);
}

.lg-left-blur-b {
  width: 380px;
  height: 380px;
  bottom: 10%;
  left: 15%;
  background: rgba(139, 115, 85, 0.12);
  filter: blur(100px);
}

/* ── right panel (desktop) ── */
.lg-right {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
  background: var(--fc-surface);
  overflow: hidden;
}

.lg-atmos { display: none; }

.lg-form-wrap {
  width: 100%;
  display: flex;
  justify-content: center;
}

.lg-form {
  width: 100%;
  max-width: 400px;
}

.lg-form-head {
  margin-bottom: 28px;
}

/* ── editorial eyebrow ── */
.lg-eyebrow {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.2em;
  text-transform: uppercase;
  color: var(--fc-accent-strong);
  margin-bottom: 14px;
}

.lg-eyebrow-line {
  width: 24px;
  height: 1px;
  background: var(--fc-accent-soft);
}

.lg-title {
  font-family: var(--fc-font-display);
  font-size: 32px;
  font-weight: 700;
  color: var(--fc-text);
  text-align: center;
  margin: 0 0 10px;
  letter-spacing: -0.015em;
  line-height: 1.15;
}

.lg-subtitle {
  font-size: 14px;
  color: var(--fc-text-sec);
  text-align: center;
  margin: 0;
  line-height: 1.55;
}

/* ── method toggle (accountId / email) ── */
.lg-method {
  position: relative;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
  margin-bottom: 20px;
  padding: 4px;
  border: 1px solid var(--fc-border);
  border-radius: 12px;
  background: var(--fc-bg);
}

.lg-method-tab {
  position: relative;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 9px 10px;
  border: none;
  border-radius: 9px;
  background: transparent;
  color: var(--fc-text-muted);
  font-family: inherit;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.01em;
  cursor: pointer;
  transition: color var(--fc-duration-normal) var(--fc-ease-in-out);
}

.lg-method-tab svg {
  opacity: 0.7;
  transition: opacity var(--fc-duration-normal) var(--fc-ease-in-out);
}

.lg-method-tab.is-active {
  color: var(--fc-text);
}

.lg-method-tab.is-active svg {
  opacity: 1;
  color: var(--fc-accent-strong);
}

.lg-method-glider {
  position: absolute;
  top: 4px;
  bottom: 4px;
  left: 4px;
  width: calc(50% - 4px);
  border-radius: 9px;
  background: var(--fc-surface);
  box-shadow:
    0 2px 6px rgba(33, 26, 20, 0.06),
    inset 0 1px 0 rgba(255, 255, 255, 0.8);
  transition: transform var(--fc-duration-normal) var(--fc-ease-out);
  pointer-events: none;
  z-index: 0;
}

.lg-method-glider[data-method='email'] {
  transform: translateX(calc(100% + 4px));
}

/* field swap motion */
.lg-swap-enter-active,
.lg-swap-leave-active {
  transition:
    opacity var(--fc-duration-normal) var(--fc-ease-in-out),
    transform var(--fc-duration-normal) var(--fc-ease-in-out);
}

.lg-swap-enter-from {
  opacity: 0;
  transform: translateY(4px);
}

.lg-swap-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

.lg-grp { margin-bottom: 18px; }

.lg-label {
  font-family: var(--fc-font);
  font-size: 12px;
  font-weight: 600;
  color: var(--fc-text-sec);
  display: block;
  margin-bottom: 8px;
  letter-spacing: 0.02em;
  text-transform: uppercase;
}

.lg-input {
  width: 100%;
  background: var(--fc-bg);
  border: 1px solid var(--fc-border);
  border-radius: 14px;
  padding: 14px 16px;
  outline: none;
  font-size: 15px;
  font-family: inherit;
  color: var(--fc-text);
  transition:
    border-color var(--fc-duration-normal) var(--fc-ease-in-out),
    box-shadow var(--fc-duration-normal) var(--fc-ease-in-out),
    background var(--fc-duration-normal) var(--fc-ease-in-out);
}

.lg-input-mono {
  font-family: var(--fc-font-mono);
  letter-spacing: 0.04em;
}

.lg-input:focus {
  border-color: var(--fc-accent);
  background: var(--fc-surface);
  box-shadow: 0 0 0 3px var(--fc-selected-ring);
}

.lg-input::placeholder {
  color: var(--fc-text-muted);
}

.lg-input-wrap { position: relative; }

.lg-input-pw { padding-right: 46px; }

.lg-pw-toggle {
  position: absolute;
  right: 10px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--fc-text-muted);
  cursor: pointer;
  padding: 8px;
  border-radius: 10px;
  background: transparent;
  border: none;
  transition: color var(--fc-duration-fast) ease, background var(--fc-duration-fast) ease;
}

.lg-pw-toggle:hover {
  color: var(--fc-accent-strong);
  background: var(--fc-selected-bg);
}

/* ── error ── */
.lg-error {
  background: rgba(184, 96, 75, 0.08);
  border: 1px solid rgba(184, 96, 75, 0.18);
  font-size: 13px;
  text-align: center;
  margin: 0 0 14px;
  padding: 10px 14px;
  border-radius: 12px;
  color: var(--fc-danger);
  font-weight: 500;
}

.lg-err-enter-active,
.lg-err-leave-active { transition: all var(--fc-duration-normal) var(--fc-ease-in-out); }
.lg-err-enter-from,
.lg-err-leave-to { opacity: 0; transform: translateY(-4px); }

/* ── primary button ── */
.lg-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  padding: 14px;
  border: 1px solid transparent;
  border-radius: 14px;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  font-family: inherit;
  transition:
    background var(--fc-duration-normal) var(--fc-ease-in-out),
    box-shadow var(--fc-duration-normal) var(--fc-ease-in-out),
    transform var(--fc-duration-fast) ease;
}

.lg-btn-inner {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.lg-btn-primary {
  background: linear-gradient(135deg, var(--fc-accent) 0%, var(--fc-accent-strong) 100%);
  color: #fff;
  box-shadow:
    0 10px 24px rgba(151, 90, 38, 0.22),
    inset 0 1px 0 rgba(255, 255, 255, 0.22);
  position: relative;
  overflow: hidden;
}

.lg-btn-primary::before {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(110deg, transparent 30%, rgba(255, 255, 255, 0.22) 50%, transparent 70%);
  transform: translateX(-100%);
  transition: transform 0.6s var(--fc-ease-out);
}

.lg-btn-primary:hover:not(:disabled) {
  box-shadow:
    0 14px 32px rgba(151, 90, 38, 0.28),
    inset 0 1px 0 rgba(255, 255, 255, 0.3);
  transform: translateY(-1px);
}

.lg-btn-primary:hover:not(:disabled)::before {
  transform: translateX(100%);
}

.lg-btn-primary:hover:not(:disabled) .lg-btn-arrow {
  transform: translateX(3px);
}

.lg-btn-primary:active:not(:disabled) {
  transform: scale(0.985);
}

.lg-btn-primary:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.lg-btn-arrow {
  transition: transform var(--fc-duration-normal) var(--fc-ease-out);
  opacity: 0.9;
}

.lg-spinner {
  width: 18px;
  height: 18px;
  border: 2px solid rgba(255,255,255,0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: lg-spin 0.6s linear infinite;
}

@keyframes lg-spin { to { transform: rotate(360deg); } }

/* ── sign up hint ── */
.lg-signup-hint {
  text-align: center;
  font-size: 14px;
  color: var(--fc-text-sec);
  margin-top: 24px;
}

.lg-signup-link {
  color: var(--fc-accent-strong);
  font-weight: 600;
  cursor: pointer;
  text-decoration: none;
  transition: color var(--fc-duration-fast) ease;
}

.lg-signup-link:hover {
  color: var(--fc-accent);
  text-decoration: underline;
}

/* ── quick entry invite banner (inline above form head) ── */
.lg-invite {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  margin: 0 0 22px;
  padding: 12px 12px 12px 14px;
  border-radius: 16px;
  background: linear-gradient(135deg, rgba(182, 118, 57, 0.09) 0%, rgba(151, 90, 38, 0.05) 100%);
  border: 1px solid var(--fc-accent-weak);
  color: var(--fc-text);
  font-family: inherit;
  cursor: pointer;
  text-align: left;
  overflow: hidden;
  transition:
    background var(--fc-duration-normal) var(--fc-ease-in-out),
    border-color var(--fc-duration-normal) var(--fc-ease-in-out),
    box-shadow var(--fc-duration-normal) var(--fc-ease-in-out),
    transform var(--fc-duration-fast) ease;
}

.lg-invite::before {
  content: '';
  position: absolute;
  top: -36px;
  right: -36px;
  width: 120px;
  height: 120px;
  background: radial-gradient(circle, var(--fc-accent-veil) 0%, transparent 70%);
  pointer-events: none;
}

.lg-invite:hover {
  background: linear-gradient(135deg, rgba(182, 118, 57, 0.14) 0%, rgba(151, 90, 38, 0.08) 100%);
  border-color: var(--fc-accent-soft);
  box-shadow: 0 6px 18px rgba(151, 90, 38, 0.14);
  transform: translateY(-1px);
}

.lg-invite:active {
  transform: scale(0.99);
}

.lg-invite-icon {
  flex-shrink: 0;
  width: 34px;
  height: 34px;
  border-radius: 11px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, var(--fc-accent) 0%, var(--fc-accent-strong) 100%);
  color: #fff;
  box-shadow:
    0 6px 14px rgba(151, 90, 38, 0.28),
    inset 0 1px 0 rgba(255, 255, 255, 0.22);
}

.lg-invite-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.lg-invite-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--fc-text);
  letter-spacing: 0.01em;
  line-height: 1.25;
}

.lg-invite-sub {
  font-size: 11.5px;
  color: var(--fc-text-sec);
  line-height: 1.3;
}

.lg-invite-cta {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 6px 10px 6px 11px;
  border-radius: var(--fc-radius-pill);
  background: var(--fc-surface);
  border: 1px solid var(--fc-accent-soft);
  color: var(--fc-accent-strong);
  font-size: 11.5px;
  font-weight: 600;
  letter-spacing: 0.01em;
  transition:
    background var(--fc-duration-fast) ease,
    color var(--fc-duration-fast) ease,
    border-color var(--fc-duration-fast) ease;
}

.lg-invite:hover .lg-invite-cta {
  background: var(--fc-accent-strong);
  color: #fff;
  border-color: var(--fc-accent-strong);
}

.lg-invite-arrow {
  transition: transform var(--fc-duration-normal) var(--fc-ease-out);
}

.lg-invite:hover .lg-invite-arrow {
  transform: translateX(2px);
}

.lg-invite-enter-active,
.lg-invite-leave-active { transition: all var(--fc-duration-normal) var(--fc-ease-out); }
.lg-invite-enter-from,
.lg-invite-leave-to { opacity: 0; transform: translateY(-6px) scale(0.98); }

/* ── mobile ── */
.lg-mobile-header { display: none; }

@media (max-width: 900px) {
  .lg {
    grid-template-columns: 1fr;
    max-height: none;
    overflow: auto;
  }

  .lg-left { display: none; }

  .lg-right {
    min-height: 100vh;
    min-height: 100dvh;
    padding: 0;
    display: flex;
    flex-direction: column;
    background: var(--fc-app-gradient);
    position: relative;
    isolation: isolate;
  }

  /* atmospheric backdrop */
  .lg-atmos {
    display: block;
    position: absolute;
    inset: 0;
    z-index: 0;
    pointer-events: none;
    overflow: hidden;
  }

  .lg-atmos-blur {
    position: absolute;
    border-radius: 50%;
    filter: blur(90px);
  }

  .lg-atmos-blur-a {
    width: 320px;
    height: 320px;
    top: -100px;
    right: -80px;
    background: rgba(214, 176, 132, 0.45);
  }

  .lg-atmos-blur-b {
    width: 360px;
    height: 360px;
    bottom: -140px;
    left: -100px;
    background: rgba(182, 118, 57, 0.18);
  }

  /* mobile header */
  .lg-mobile-header {
    position: relative;
    z-index: 2;
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 18px 20px 6px;
  }

  .lg-mobile-back {
    width: 40px;
    height: 40px;
    border-radius: 50%;
    border: 1px solid var(--fc-border);
    background: var(--fc-surface-strong);
    color: var(--fc-text-sec);
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    box-shadow: var(--fc-shadow-soft);
    backdrop-filter: blur(12px);
    -webkit-backdrop-filter: blur(12px);
    transition: color var(--fc-duration-fast) ease, border-color var(--fc-duration-fast) ease;
  }

  .lg-mobile-back:hover,
  .lg-mobile-back:active {
    color: var(--fc-accent-strong);
    border-color: var(--fc-accent-soft);
  }

  .lg-mobile-back-placeholder {
    width: 40px;
    height: 40px;
  }

  .lg-mobile-brand {
    display: flex;
    align-items: center;
    gap: 8px;
    font-family: var(--fc-font-display);
    font-size: 15px;
    font-weight: 600;
    color: var(--fc-text);
  }

  .lg-mobile-brand-icon {
    width: 28px;
    height: 28px;
    border-radius: 9px;
    border: 1px solid var(--fc-border);
    background: var(--fc-surface);
    color: var(--fc-accent-strong);
    display: inline-flex;
    align-items: center;
    justify-content: center;
    box-shadow: var(--fc-shadow-in);
  }

  /* form wrap + card */
  .lg-form-wrap {
    flex: 1;
    display: flex;
    align-items: flex-start;
    justify-content: center;
    padding: 28px 20px 40px;
    position: relative;
    z-index: 1;
  }

  .lg-form {
    width: 100%;
    max-width: 440px;
    padding: 32px 26px 28px;
    background: var(--fc-surface-strong);
    border: 1px solid var(--fc-border);
    border-radius: var(--fc-radius-xl);
    box-shadow: var(--fc-shadow-panel);
    backdrop-filter: blur(12px);
    -webkit-backdrop-filter: blur(12px);
    position: relative;
  }

  .lg-form::before {
    content: '';
    position: absolute;
    inset: 0;
    border-radius: inherit;
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.8);
    pointer-events: none;
  }

  .lg-form-head { margin-bottom: 24px; }

  .lg-title {
    font-size: 26px;
    margin-bottom: 8px;
  }

  .lg-subtitle {
    font-size: 13.5px;
  }

  .lg-input {
    border-radius: 12px;
    padding: 13px 15px;
    font-size: 15px;
  }

  .lg-btn {
    border-radius: 12px;
    padding: 14px;
  }

  .lg-signup-hint { margin-top: 22px; }
}

@media (max-width: 440px) {
  .lg-invite {
    padding: 10px 10px 10px 12px;
    gap: 10px;
    margin-bottom: 18px;
  }
  .lg-invite-icon { width: 30px; height: 30px; border-radius: 10px; }
  .lg-invite-title { font-size: 12.5px; }
  .lg-invite-sub { font-size: 11px; }
  .lg-invite-cta { padding: 5px 8px 5px 10px; font-size: 11px; }
}

@media (max-width: 380px) {
  .lg-form { padding: 26px 20px 24px; }
  .lg-title { font-size: 24px; }
  .lg-invite-sub { display: none; }
}

@media (prefers-reduced-motion: reduce) {
  .lg-btn-primary::before,
  .lg-btn-arrow,
  .lg-invite-arrow {
    animation: none !important;
    transition: none !important;
  }
}
</style>
