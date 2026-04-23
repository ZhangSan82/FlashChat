<template>
  <div class="rg">
    <!-- Left: animated characters (desktop only) -->
    <div class="rg-left">
      <div class="rg-left-brand">
        <span class="rg-left-brand-icon">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M13 2L5 14h5l-1 8 8-12h-5l1-8z" />
          </svg>
        </span>
        <span>FlashChat</span>
      </div>

      <div class="rg-left-stage">
        <AnimatedCharacters
          :is-typing="isInputFocused"
          :show-password="showPassword"
          :password-length="form.password.length"
        />
      </div>

      <div class="rg-left-footer">FlashChat v1.0</div>
      <div class="rg-left-blur rg-left-blur-a"></div>
      <div class="rg-left-blur rg-left-blur-b"></div>
    </div>

    <!-- Right: register form -->
    <div class="rg-right">
      <!-- atmospheric backdrop (mobile only) -->
      <div class="rg-atmos" aria-hidden="true">
        <div class="rg-atmos-blur rg-atmos-blur-a"></div>
        <div class="rg-atmos-blur rg-atmos-blur-b"></div>
      </div>

      <!-- mobile header -->
      <div class="rg-mobile-header">
        <button class="rg-mobile-back" @click="goBack" aria-label="返回">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <line x1="19" y1="12" x2="5" y2="12" /><polyline points="12 19 5 12 12 5" />
          </svg>
        </button>
        <div class="rg-mobile-brand">
          <span class="rg-mobile-brand-icon">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <path d="M13 2L5 14h5l-1 8 8-12h-5l1-8z" />
            </svg>
          </span>
          <span>FlashChat</span>
        </div>
        <div class="rg-mobile-back-placeholder"></div>
      </div>

      <div class="rg-form-wrap">
        <div class="rg-form">
          <!-- quick entry invite banner (when arrived from room scan) -->
          <transition name="rg-invite">
            <button
              v-if="quickEntryRoomId"
              class="rg-invite"
              type="button"
              @click="goQuickEntry"
              :aria-label="`检测到房间邀请,无需注册快速进入房间 ${quickEntryRoomId}`"
            >
              <span class="rg-invite-icon" aria-hidden="true">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M13 2L5 14h5l-1 8 8-12h-5l1-8z" />
                </svg>
              </span>
              <span class="rg-invite-body">
                <span class="rg-invite-title">检测到房间邀请</span>
                <span class="rg-invite-sub">无需注册,直接进入房间</span>
              </span>
              <span class="rg-invite-cta">
                快速进入
                <svg class="rg-invite-arrow" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                  <line x1="5" y1="12" x2="19" y2="12" />
                  <polyline points="12 5 19 12 12 19" />
                </svg>
              </span>
            </button>
          </transition>

          <div class="rg-form-head">
            <div class="rg-eyebrow">
              <span class="rg-eyebrow-line"></span>
              <span>创建账号</span>
            </div>
            <h1 class="rg-title">创建账号</h1>
            <p class="rg-subtitle">设置邮箱和密码,获得完整的 FlashChat 账号</p>
          </div>

          <div class="rg-grp">
            <label class="rg-label" for="rg-email">邮箱</label>
            <input
              id="rg-email"
              v-model="form.email"
              type="email"
              placeholder="your@email.com"
              class="rg-input"
              autocomplete="email"
              @focus="isInputFocused = true"
              @blur="isInputFocused = false"
            />
          </div>

          <div class="rg-grp">
            <label class="rg-label" for="rg-pw">密码</label>
            <div class="rg-input-wrap">
              <input
                id="rg-pw"
                v-model="form.password"
                :type="showPassword ? 'text' : 'password'"
                placeholder="至少 6 位密码"
                class="rg-input rg-input-pw"
                autocomplete="new-password"
                @focus="isInputFocused = true"
                @blur="isInputFocused = false"
              />
              <button type="button" class="rg-pw-toggle" @click="showPassword = !showPassword" :aria-label="showPassword ? '隐藏密码' : '显示密码'">
                <svg v-if="!showPassword" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" /><circle cx="12" cy="12" r="3" />
                </svg>
                <svg v-else width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24" />
                  <line x1="1" y1="1" x2="23" y2="23" />
                </svg>
              </button>
            </div>
          </div>

          <div class="rg-grp">
            <label class="rg-label" for="rg-confirm">确认密码</label>
            <input
              id="rg-confirm"
              v-model="form.confirmPassword"
              type="password"
              placeholder="再次输入密码"
              class="rg-input"
              autocomplete="new-password"
              @focus="isInputFocused = true"
              @blur="isInputFocused = false"
              @keyup.enter="doRegister"
            />
          </div>

          <div class="rg-grp">
            <label class="rg-label" for="rg-invite">
              邀请码
              <span class="rg-label-optional">选填</span>
            </label>
            <input
              id="rg-invite"
              v-model="form.inviteCode"
              type="text"
              placeholder="输入邀请码可获得额外积分"
              class="rg-input"
              maxlength="16"
              @focus="isInputFocused = true"
              @blur="isInputFocused = false"
            />
          </div>

          <transition name="rg-err">
            <p v-if="error" class="rg-error">{{ error }}</p>
          </transition>

          <button
            class="rg-btn rg-btn-primary"
            :disabled="submitting"
            @click="doRegister"
          >
            <span v-if="!submitting" class="rg-btn-inner">
              注册账号
              <svg class="rg-btn-arrow" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <line x1="5" y1="12" x2="19" y2="12" />
                <polyline points="12 5 19 12 12 19" />
              </svg>
            </span>
            <span v-else class="rg-btn-inner">
              <span class="rg-spinner"></span>
              注册中...
            </span>
          </button>

          <p class="rg-login-hint">
            已有账号?
            <a class="rg-login-link" @click.prevent="goLogin">登录</a>
          </p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { autoRegister, upgradeAccount } from '@/api/account'
import { saveToken } from '@/utils/storage'
import { useAuth } from '@/composables/useAuth'
import AnimatedCharacters from '@/components/AnimatedCharacters.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuth()

const form = reactive({ email: '', password: '', confirmPassword: '', inviteCode: '' })
const error = ref('')
const submitting = ref(false)
const showPassword = ref(false)
const isInputFocused = ref(false)

const quickEntryRoomId = computed(() => {
  const redirect = route.query.redirect
  if (!redirect || typeof redirect !== 'string') return null
  const m = redirect.match(/^\/room\/([^\/\?#]+)/)
  return m ? m[1] : null
})

async function doRegister() {
  error.value = ''

  if (!form.email.trim()) { error.value = '请输入邮箱'; return }
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim())) { error.value = '邮箱格式不正确'; return }
  if (!form.password) { error.value = '请输入密码'; return }
  if (form.password.length < 6) { error.value = '密码至少 6 位'; return }
  if (form.password !== form.confirmPassword) { error.value = '两次密码不一致'; return }

  submitting.value = true
  try {
    const autoResp = await autoRegister()
    saveToken(autoResp.token)
    auth.onAuthRefreshed(autoResp)

    const upgradeResp = await upgradeAccount({
      email: form.email.trim(),
      password: form.password,
      confirmPassword: form.confirmPassword,
      inviteCode: form.inviteCode.trim() || undefined
    })
    saveToken(upgradeResp.token)
    auth.onAuthRefreshed(upgradeResp)

    const redirect = route.query.redirect
    if (redirect && redirect.startsWith('/')) {
      router.replace(redirect)
    } else {
      router.replace('/')
    }
  } catch (e) {
    error.value = e.message || '注册失败,请重试'
  } finally {
    submitting.value = false
  }
}

function goBack() {
  if (quickEntryRoomId.value) {
    router.push({ name: 'EntryChoice', query: { roomId: quickEntryRoomId.value } })
    return
  }
  router.push({ name: 'Login', query: { redirect: route.query.redirect || '/' } })
}

function goQuickEntry() {
  if (!quickEntryRoomId.value) return
  router.push({ name: 'EntryChoice', query: { roomId: quickEntryRoomId.value } })
}

function goLogin() {
  router.push({ name: 'Login', query: { redirect: route.query.redirect || '/' } })
}
</script>

<style scoped>
.rg {
  display: grid;
  grid-template-columns: 1fr 1fr;
  min-height: 100vh;
  min-height: 100dvh;
  max-height: 100vh;
  overflow: hidden;
}

/* ── left panel (desktop) ── */
.rg-left {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 40px 48px;
  background: linear-gradient(135deg, #A0896E, #8B7355, #766045);
  color: #fff;
  overflow: hidden;
}

.rg-left-brand {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 10px;
  font-family: var(--fc-font-display);
  font-size: 18px;
  font-weight: 600;
}

.rg-left-brand-icon {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: rgba(255,255,255,0.12);
  backdrop-filter: blur(4px);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.rg-left-stage {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  flex: 1;
  padding-bottom: 20px;
}

.rg-left-footer {
  position: relative;
  z-index: 2;
  font-size: 12px;
  color: rgba(255,255,255,0.5);
  letter-spacing: 0.08em;
}

.rg-left-blur {
  position: absolute;
  border-radius: 50%;
  pointer-events: none;
}

.rg-left-blur-a {
  width: 260px;
  height: 260px;
  top: 20%;
  right: 20%;
  background: rgba(182, 118, 57, 0.15);
  filter: blur(80px);
}

.rg-left-blur-b {
  width: 380px;
  height: 380px;
  bottom: 10%;
  left: 15%;
  background: rgba(139, 115, 85, 0.12);
  filter: blur(100px);
}

/* ── right panel (desktop) ── */
.rg-right {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
  background: var(--fc-surface);
  overflow-y: auto;
}

.rg-atmos { display: none; }

.rg-form-wrap {
  width: 100%;
  display: flex;
  justify-content: center;
}

.rg-form {
  width: 100%;
  max-width: 400px;
}

.rg-form-head { margin-bottom: 26px; }

/* ── editorial eyebrow ── */
.rg-eyebrow {
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

.rg-eyebrow-line {
  width: 24px;
  height: 1px;
  background: var(--fc-accent-soft);
}

.rg-title {
  font-family: var(--fc-font-display);
  font-size: 30px;
  font-weight: 700;
  color: var(--fc-text);
  text-align: center;
  margin: 0 0 10px;
  letter-spacing: -0.015em;
  line-height: 1.15;
}

.rg-subtitle {
  font-size: 14px;
  color: var(--fc-text-sec);
  text-align: center;
  margin: 0;
  line-height: 1.55;
}

.rg-grp { margin-bottom: 16px; }

.rg-label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-family: var(--fc-font);
  font-size: 12px;
  font-weight: 600;
  color: var(--fc-text-sec);
  margin-bottom: 8px;
  letter-spacing: 0.02em;
  text-transform: uppercase;
}

.rg-label-optional {
  font-size: 10px;
  font-weight: 500;
  color: var(--fc-text-muted);
  padding: 2px 7px;
  border-radius: var(--fc-radius-pill);
  background: var(--fc-surface-muted);
  border: 1px solid var(--fc-border);
  letter-spacing: 0.04em;
  text-transform: none;
}

.rg-input {
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

.rg-input:focus {
  border-color: var(--fc-accent);
  background: var(--fc-surface);
  box-shadow: 0 0 0 3px var(--fc-selected-ring);
}

.rg-input::placeholder { color: var(--fc-text-muted); }

.rg-input-wrap { position: relative; }
.rg-input-pw { padding-right: 46px; }

.rg-pw-toggle {
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

.rg-pw-toggle:hover {
  color: var(--fc-accent-strong);
  background: var(--fc-selected-bg);
}

/* ── error ── */
.rg-error {
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

.rg-err-enter-active,
.rg-err-leave-active { transition: all var(--fc-duration-normal) var(--fc-ease-in-out); }
.rg-err-enter-from,
.rg-err-leave-to { opacity: 0; transform: translateY(-4px); }

/* ── primary button ── */
.rg-btn {
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

.rg-btn-inner {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.rg-btn-primary {
  background: linear-gradient(135deg, var(--fc-accent) 0%, var(--fc-accent-strong) 100%);
  color: #fff;
  box-shadow:
    0 10px 24px rgba(151, 90, 38, 0.22),
    inset 0 1px 0 rgba(255, 255, 255, 0.22);
  position: relative;
  overflow: hidden;
}

.rg-btn-primary::before {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(110deg, transparent 30%, rgba(255, 255, 255, 0.22) 50%, transparent 70%);
  transform: translateX(-100%);
  transition: transform 0.6s var(--fc-ease-out);
}

.rg-btn-primary:hover:not(:disabled) {
  box-shadow:
    0 14px 32px rgba(151, 90, 38, 0.28),
    inset 0 1px 0 rgba(255, 255, 255, 0.3);
  transform: translateY(-1px);
}

.rg-btn-primary:hover:not(:disabled)::before {
  transform: translateX(100%);
}

.rg-btn-primary:hover:not(:disabled) .rg-btn-arrow {
  transform: translateX(3px);
}

.rg-btn-primary:active:not(:disabled) {
  transform: scale(0.985);
}

.rg-btn-primary:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.rg-btn-arrow {
  transition: transform var(--fc-duration-normal) var(--fc-ease-out);
  opacity: 0.9;
}

.rg-spinner {
  width: 18px;
  height: 18px;
  border: 2px solid rgba(255,255,255,0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: rg-spin 0.6s linear infinite;
}

@keyframes rg-spin { to { transform: rotate(360deg); } }

/* ── login hint ── */
.rg-login-hint {
  text-align: center;
  font-size: 14px;
  color: var(--fc-text-sec);
  margin-top: 24px;
}

.rg-login-link {
  color: var(--fc-accent-strong);
  font-weight: 600;
  cursor: pointer;
  text-decoration: none;
  transition: color var(--fc-duration-fast) ease;
}

.rg-login-link:hover {
  color: var(--fc-accent);
  text-decoration: underline;
}

/* ── quick entry invite banner (inline above form head) ── */
.rg-invite {
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

.rg-invite::before {
  content: '';
  position: absolute;
  top: -36px;
  right: -36px;
  width: 120px;
  height: 120px;
  background: radial-gradient(circle, var(--fc-accent-veil) 0%, transparent 70%);
  pointer-events: none;
}

.rg-invite:hover {
  background: linear-gradient(135deg, rgba(182, 118, 57, 0.14) 0%, rgba(151, 90, 38, 0.08) 100%);
  border-color: var(--fc-accent-soft);
  box-shadow: 0 6px 18px rgba(151, 90, 38, 0.14);
  transform: translateY(-1px);
}

.rg-invite:active {
  transform: scale(0.99);
}

.rg-invite-icon {
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

.rg-invite-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.rg-invite-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--fc-text);
  letter-spacing: 0.01em;
  line-height: 1.25;
}

.rg-invite-sub {
  font-size: 11.5px;
  color: var(--fc-text-sec);
  line-height: 1.3;
}

.rg-invite-cta {
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

.rg-invite:hover .rg-invite-cta {
  background: var(--fc-accent-strong);
  color: #fff;
  border-color: var(--fc-accent-strong);
}

.rg-invite-arrow {
  transition: transform var(--fc-duration-normal) var(--fc-ease-out);
}

.rg-invite:hover .rg-invite-arrow {
  transform: translateX(2px);
}

.rg-invite-enter-active,
.rg-invite-leave-active { transition: all var(--fc-duration-normal) var(--fc-ease-out); }
.rg-invite-enter-from,
.rg-invite-leave-to { opacity: 0; transform: translateY(-6px) scale(0.98); }

/* ── mobile ── */
.rg-mobile-header { display: none; }

@media (max-width: 900px) {
  .rg {
    grid-template-columns: 1fr;
    max-height: none;
    overflow: auto;
  }

  .rg-left { display: none; }

  .rg-right {
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
  .rg-atmos {
    display: block;
    position: absolute;
    inset: 0;
    z-index: 0;
    pointer-events: none;
    overflow: hidden;
  }

  .rg-atmos-blur {
    position: absolute;
    border-radius: 50%;
    filter: blur(90px);
  }

  .rg-atmos-blur-a {
    width: 320px;
    height: 320px;
    top: -100px;
    right: -80px;
    background: rgba(214, 176, 132, 0.45);
  }

  .rg-atmos-blur-b {
    width: 360px;
    height: 360px;
    bottom: -140px;
    left: -100px;
    background: rgba(182, 118, 57, 0.18);
  }

  /* mobile header */
  .rg-mobile-header {
    position: relative;
    z-index: 2;
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 18px 20px 6px;
  }

  .rg-mobile-back {
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

  .rg-mobile-back:hover,
  .rg-mobile-back:active {
    color: var(--fc-accent-strong);
    border-color: var(--fc-accent-soft);
  }

  .rg-mobile-back-placeholder {
    width: 40px;
    height: 40px;
  }

  .rg-mobile-brand {
    display: flex;
    align-items: center;
    gap: 8px;
    font-family: var(--fc-font-display);
    font-size: 15px;
    font-weight: 600;
    color: var(--fc-text);
  }

  .rg-mobile-brand-icon {
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
  .rg-form-wrap {
    flex: 1;
    display: flex;
    align-items: flex-start;
    justify-content: center;
    padding: 24px 20px 40px;
    position: relative;
    z-index: 1;
  }

  .rg-form {
    width: 100%;
    max-width: 440px;
    padding: 30px 26px 26px;
    background: var(--fc-surface-strong);
    border: 1px solid var(--fc-border);
    border-radius: var(--fc-radius-xl);
    box-shadow: var(--fc-shadow-panel);
    backdrop-filter: blur(12px);
    -webkit-backdrop-filter: blur(12px);
    position: relative;
  }

  .rg-form::before {
    content: '';
    position: absolute;
    inset: 0;
    border-radius: inherit;
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.8);
    pointer-events: none;
  }

  .rg-form-head { margin-bottom: 22px; }

  .rg-title {
    font-size: 24px;
    margin-bottom: 8px;
  }

  .rg-subtitle {
    font-size: 13px;
  }

  .rg-grp { margin-bottom: 14px; }

  .rg-input {
    border-radius: 12px;
    padding: 13px 15px;
    font-size: 15px;
  }

  .rg-btn {
    border-radius: 12px;
    padding: 14px;
  }

  .rg-login-hint { margin-top: 20px; }
}

@media (max-width: 440px) {
  .rg-invite {
    padding: 10px 10px 10px 12px;
    gap: 10px;
    margin-bottom: 18px;
  }
  .rg-invite-icon { width: 30px; height: 30px; border-radius: 10px; }
  .rg-invite-title { font-size: 12.5px; }
  .rg-invite-sub { font-size: 11px; }
  .rg-invite-cta { padding: 5px 8px 5px 10px; font-size: 11px; }
}

@media (max-width: 380px) {
  .rg-form { padding: 24px 20px 22px; }
  .rg-title { font-size: 22px; }
  .rg-invite-sub { display: none; }
}

@media (prefers-reduced-motion: reduce) {
  .rg-btn-primary::before,
  .rg-btn-arrow,
  .rg-invite-arrow {
    animation: none !important;
    transition: none !important;
  }
}
</style>
