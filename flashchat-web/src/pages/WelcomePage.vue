<template>
  <div class="wp">
    <!-- Left: animated characters -->
    <div class="wp-left">
      <div class="wp-left-brand">
        <span class="wp-left-brand-icon">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M13 2L5 14h5l-1 8 8-12h-5l1-8z" />
          </svg>
        </span>
        <span>FlashChat</span>
      </div>

      <div class="wp-left-stage">
        <AnimatedCharacters />
      </div>

      <div class="wp-left-footer">
        <span>FlashChat v1.0</span>
        <span class="wp-left-footer-dot">·</span>
        <a
          class="wp-source-link"
          href="https://github.com/ZhangSan82/FlashChat.git"
          target="_blank"
          rel="noopener noreferrer"
        >
          GitHub 源码
        </a>
      </div>

      <!-- decorative blurs -->
      <div class="wp-left-blur wp-left-blur-a"></div>
      <div class="wp-left-blur wp-left-blur-b"></div>
    </div>

    <!-- Right: entry options -->
    <div class="wp-right">
      <!-- mobile brand (shown only on small screens) -->
      <div class="wp-mobile-brand">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <path d="M13 2L5 14h5l-1 8 8-12h-5l1-8z" />
        </svg>
        <span>FlashChat</span>
      </div>

      <div class="wp-form">
        <h1 class="wp-title">欢迎进入 FlashChat</h1>
        <p class="wp-subtitle">选择一种方式开始体验</p>

        <!-- primary: quick entry -->
        <button
          class="wp-btn wp-btn-primary"
          :disabled="quickEntering"
          @click="doQuickEntry"
        >
          <span v-if="!quickEntering" class="wp-btn-inner">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M13 2L5 14h5l-1 8 8-12h-5l1-8z" />
            </svg>
            快速进入
          </span>
          <span v-else class="wp-btn-inner">
            <span class="wp-spinner"></span>
            正在进入...
          </span>
        </button>

        <!-- secondary: login -->
        <button class="wp-btn wp-btn-outline" @click="goLogin">
          <span class="wp-btn-inner">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
              <circle cx="12" cy="7" r="4" />
            </svg>
            账号登录
          </span>
        </button>

        <!-- tertiary: register link -->
        <p class="wp-register-hint">
          没有账号？
          <a class="wp-register-link" @click.prevent="goRegister">注册账号</a>
        </p>

        <p class="wp-source-hint">
          <a
            class="wp-source-link"
            href="https://github.com/ZhangSan82/FlashChat.git"
            target="_blank"
            rel="noopener noreferrer"
          >
            查看 GitHub 源码
          </a>
        </p>

        <!-- error -->
        <transition name="wp-err">
          <p v-if="error" class="wp-error">{{ error }}</p>
        </transition>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { autoRegister } from '@/api/account'
import { saveToken } from '@/utils/storage'
import { useAuth } from '@/composables/useAuth'
import AnimatedCharacters from '@/components/AnimatedCharacters.vue'

const router = useRouter()
const route = useRoute()
const auth = useAuth()

const quickEntering = ref(false)
const error = ref('')

async function doQuickEntry() {
  quickEntering.value = true
  error.value = ''
  try {
    const resp = await autoRegister()
    saveToken(resp.token)
    auth.onAuthRefreshed(resp)
    sessionStorage.setItem('fc_quick_entry', '1')

    const redirect = route.query.redirect
    if (redirect && redirect.startsWith('/')) {
      router.replace(redirect)
    } else {
      router.replace('/')
    }
  } catch (e) {
    error.value = e.message || '进入失败，请重试'
    setTimeout(() => { error.value = '' }, 4000)
  } finally {
    quickEntering.value = false
  }
}

function goLogin() {
  const redirect = route.query.redirect || '/'
  router.push({ name: 'Login', query: { redirect } })
}

function goRegister() {
  const redirect = route.query.redirect || '/'
  router.push({ name: 'Register', query: { redirect } })
}
</script>

<style scoped>
.wp {
  display: grid;
  grid-template-columns: 1fr 1fr;
  min-height: 100vh;
  min-height: 100dvh;
  max-height: 100vh;
  overflow: hidden;
}

/* ── left panel ── */
.wp-left {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 40px 48px;
  background: linear-gradient(135deg, #A0896E, #8B7355, #766045);
  color: #fff;
  overflow: hidden;
}

.wp-left-brand {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 10px;
  font-family: var(--fc-font-display);
  font-size: 18px;
  font-weight: 600;
}

.wp-left-brand-icon {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: rgba(255,255,255,0.12);
  backdrop-filter: blur(4px);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.wp-left-stage {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  flex: 1;
  padding-bottom: 20px;
}

.wp-left-footer {
  position: relative;
  z-index: 2;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: rgba(255,255,255,0.5);
  letter-spacing: 0.08em;
}

.wp-left-footer-dot {
  color: rgba(255,255,255,0.32);
}

.wp-source-link {
  color: inherit;
  text-decoration: none;
  transition: color var(--fc-duration-normal, 250ms) ease, opacity var(--fc-duration-normal, 250ms) ease;
}

.wp-source-link:hover {
  color: rgba(255,255,255,0.86);
}

.wp-left-blur {
  position: absolute;
  border-radius: 50%;
  pointer-events: none;
}

.wp-left-blur-a {
  width: 260px;
  height: 260px;
  top: 20%;
  right: 20%;
  background: rgba(182, 118, 57, 0.15);
  filter: blur(80px);
}

.wp-left-blur-b {
  width: 380px;
  height: 380px;
  bottom: 10%;
  left: 15%;
  background: rgba(139, 115, 85, 0.12);
  filter: blur(100px);
}

/* ── right panel ── */
.wp-right {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
  background: var(--fc-surface, #fff);
}

.wp-form {
  width: 100%;
  max-width: 400px;
}

.wp-title {
  font-family: var(--fc-font-display);
  font-size: 28px;
  font-weight: 700;
  color: var(--fc-text);
  text-align: center;
  margin: 0 0 8px;
  letter-spacing: -0.01em;
}

.wp-subtitle {
  font-size: 14px;
  color: var(--fc-text-sec);
  text-align: center;
  margin: 0 0 36px;
}

/* ── buttons ── */
.wp-btn {
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
  transition:
    background var(--fc-duration-normal, 250ms) ease,
    border-color var(--fc-duration-normal, 250ms) ease,
    box-shadow var(--fc-duration-normal, 250ms) ease,
    transform 150ms ease;
}

.wp-btn:active:not(:disabled) {
  transform: scale(0.98);
}

.wp-btn + .wp-btn {
  margin-top: 12px;
}

.wp-btn-inner {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.wp-btn-primary {
  background: var(--fc-accent, #B67639);
  color: #fff;
}

.wp-btn-primary:hover:not(:disabled) {
  background: var(--fc-accent-strong, #975A26);
  box-shadow: 0 8px 20px rgba(151, 90, 38, 0.22);
}

.wp-btn-primary:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.wp-btn-outline {
  border-color: var(--fc-border-strong, rgba(33,26,20,0.14));
  background: var(--fc-surface, #fff);
  color: var(--fc-text, #211A14);
}

.wp-btn-outline:hover {
  border-color: var(--fc-accent-soft, #D6B084);
  background: var(--fc-selected-bg, #FFF8F0);
}

/* ── register link ── */
.wp-register-hint {
  text-align: center;
  font-size: 14px;
  color: var(--fc-text-sec);
  margin-top: 24px;
}

.wp-register-link {
  color: var(--fc-text);
  font-weight: 600;
  cursor: pointer;
  text-decoration: none;
  transition: color var(--fc-duration-normal, 250ms) ease;
}

.wp-register-link:hover {
  color: var(--fc-accent);
  text-decoration: underline;
}

.wp-source-hint {
  display: none;
  text-align: center;
  margin-top: 14px;
  font-size: 12px;
  color: var(--fc-text-muted, #A89A8B);
}

.wp-source-hint .wp-source-link:hover {
  color: var(--fc-accent);
  text-decoration: underline;
}

/* ── spinner ── */
.wp-spinner {
  width: 18px;
  height: 18px;
  border: 2px solid rgba(255,255,255,0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: wp-spin 0.6s linear infinite;
}

@keyframes wp-spin { to { transform: rotate(360deg); } }

/* ── error ── */
.wp-error {
  text-align: center;
  margin-top: 16px;
  font-size: 13px;
  color: var(--fc-danger, #B8604B);
  background: rgba(184, 96, 75, 0.06);
  border: 1px solid rgba(184, 96, 75, 0.12);
  border-radius: 12px;
  padding: 10px 16px;
}

.wp-err-enter-active { transition: all 0.2s ease; }
.wp-err-leave-active { transition: all 0.15s ease; }
.wp-err-enter-from,
.wp-err-leave-to { opacity: 0; transform: translateY(-6px); }

/* ── mobile brand (hidden on desktop) ── */
.wp-mobile-brand {
  display: none;
}

/* ── responsive ── */
@media (max-width: 900px) {
  .wp {
    grid-template-columns: 1fr;
    max-height: none;
  }

  .wp-left { display: none; }

  .wp-right {
    min-height: 100vh;
    min-height: 100dvh;
    padding: 0;
    flex-direction: column;
    align-items: stretch;
    justify-content: flex-start;
    background: var(--fc-app-gradient, linear-gradient(180deg, #FCF8F2, #F7F2EB, #F2EADF));
  }

  .wp-mobile-brand {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    font-family: var(--fc-font-display);
    font-size: 16px;
    font-weight: 600;
    color: var(--fc-text);
    padding: 20px 20px 0;
  }

  .wp-mobile-brand svg {
    color: var(--fc-accent-strong);
  }

  .wp-form {
    padding: 0 24px 40px;
    margin-top: auto;
    margin-bottom: auto;
    max-width: 100%;
  }

  .wp-title {
    font-size: 24px;
    margin-bottom: 6px;
  }

  .wp-subtitle { margin-bottom: 32px; }

  .wp-btn {
    border-radius: 10px;
    padding: 13px;
  }

  .wp-register-hint { margin-top: 20px; }

  .wp-source-hint { display: block; }
}
</style>
