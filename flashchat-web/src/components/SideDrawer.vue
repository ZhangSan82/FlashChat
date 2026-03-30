<template>
  <Teleport to="body">
    <transition name="dr">
      <div v-if="visible" class="dr-overlay" @click.self="$emit('close')">
        <aside class="dr-panel">
          <div class="dr-panel-glow"></div>

          <div class="dr-head">
            <div>
              <div class="dr-kicker">FlashChat</div>
              <div class="dr-sub">Command deck</div>
            </div>
            <button class="dr-close" type="button" aria-label="关闭" @click="$emit('close')">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18" />
                <line x1="6" y1="6" x2="18" y2="18" />
              </svg>
            </button>
          </div>

          <section class="dr-profile">
            <div class="dr-avatar-shell">
              <img v-if="avatarUrl" class="dr-avatar dr-avatar-img" :src="avatarUrl" alt="" />
              <div v-else class="dr-avatar" :style="{ background: avatarColor }">
                {{ (nickname || '?')[0].toUpperCase() }}
              </div>
            </div>
            <div class="dr-profile-copy">
              <div class="dr-name">{{ nickname }}</div>
              <div class="dr-id-line">FlashChat ID：{{ accountId }}</div>
            </div>
          </section>

          <div class="dr-section">快捷入口</div>
          <nav class="dr-nav">
            <button class="dr-item" type="button" @click="$emit('action', 'create')">
              <span class="dr-item-icon">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                  <line x1="12" y1="5" x2="12" y2="19" />
                  <line x1="5" y1="12" x2="19" y2="12" />
                </svg>
              </span>
              <span class="dr-item-copy">
                <strong>创建房间</strong>
                <small>发起一场新的短时对话</small>
              </span>
              <span class="dr-item-arrow">↗</span>
            </button>

            <button class="dr-item" type="button" @click="$emit('action', 'join')">
              <span class="dr-item-icon">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                  <path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4" />
                  <polyline points="10 17 15 12 10 7" />
                  <line x1="15" y1="12" x2="3" y2="12" />
                </svg>
              </span>
              <span class="dr-item-copy">
                <strong>加入房间</strong>
                <small>通过邀请码或房间 ID 进入</small>
              </span>
              <span class="dr-item-arrow">↗</span>
            </button>

            <button class="dr-item" type="button" @click="$emit('action', 'public')">
              <span class="dr-item-icon">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                  <circle cx="12" cy="12" r="9" />
                  <path d="M3 12h18" />
                  <path d="M12 3a14 14 0 0 1 0 18" />
                  <path d="M12 3a14 14 0 0 0 0 18" />
                </svg>
              </span>
              <span class="dr-item-copy">
                <strong>公开大厅</strong>
                <small>浏览正在开放中的公开房间</small>
              </span>
              <span class="dr-item-arrow">→</span>
            </button>

            <button class="dr-item" type="button" @click="$emit('action', 'credits')">
              <span class="dr-item-icon">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                  <circle cx="12" cy="12" r="9" />
                  <path d="M8 10.5c0-1.4 1.5-2.5 3.3-2.5 1.8 0 3.2.9 3.2 2.4 0 1.3-.8 1.9-2.2 2.3l-1.6.5c-1.5.4-2.4 1-2.4 2.4 0 1.6 1.5 2.5 3.5 2.5 1.8 0 3.4-.8 3.7-2.6" />
                </svg>
              </span>
              <span class="dr-item-copy">
                <strong>积分中心</strong>
                <small>查看余额、签到和积分流水</small>
              </span>
              <span class="dr-item-arrow">→</span>
            </button>

            <button class="dr-item" type="button" @click="$emit('action', 'invites')">
              <span class="dr-item-icon">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                  <path d="M8 12h8" />
                  <path d="M12 8v8" />
                  <rect x="3" y="5" width="18" height="14" rx="3" />
                </svg>
              </span>
              <span class="dr-item-copy">
                <strong>邀请码</strong>
                <small>查看和复制你的邀请码库存</small>
              </span>
              <span class="dr-item-arrow">→</span>
            </button>

            <button class="dr-item" type="button" @click="$emit('action', 'profile')">
              <span class="dr-item-icon">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                  <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                  <circle cx="12" cy="7" r="4" />
                </svg>
              </span>
              <span class="dr-item-copy">
                <strong>我的资料</strong>
                <small>头像、昵称和账号设置</small>
              </span>
              <span class="dr-item-arrow">↗</span>
            </button>
          </nav>

          <div class="dr-divider"></div>

          <button class="dr-item dr-item-danger" type="button" @click="$emit('action', 'logout')">
            <span class="dr-item-icon">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
                <polyline points="16 17 21 12 16 7" />
                <line x1="21" y1="12" x2="9" y2="12" />
              </svg>
            </span>
            <span class="dr-item-copy">
              <strong>退出登录</strong>
              <small>结束当前会话并返回入口</small>
            </span>
            <span class="dr-item-arrow">↗</span>
          </button>

          <div class="dr-foot">Private rooms · quiet conversations</div>
        </aside>
      </div>
    </transition>
  </Teleport>
</template>

<script setup>
defineProps({
  visible: Boolean,
  nickname: { type: String, default: '' },
  accountId: { type: String, default: '' },
  avatarColor: { type: String, default: '#C8956C' },
  avatarUrl: { type: String, default: '' }
})

defineEmits(['close', 'action'])
</script>

<style scoped>
.dr-overlay {
  position: fixed;
  inset: 0;
  display: flex;
  justify-content: flex-end;
  background: var(--fc-backdrop);
  backdrop-filter: blur(16px);
  z-index: 9000;
}

.dr-panel {
  position: relative;
  width: 380px;
  max-width: 92vw;
  height: 100%;
  padding: 24px 22px 18px;
  background: linear-gradient(180deg, rgba(255, 250, 243, 0.96), rgba(247, 239, 228, 0.98));
  border-left: 1px solid rgba(77, 52, 31, 0.12);
  box-shadow: -20px 0 50px rgba(61, 40, 22, 0.18);
  display: flex;
  flex-direction: column;
  overflow-x: hidden;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
}

.dr-panel-glow {
  position: absolute;
  width: 220px;
  height: 220px;
  top: -90px;
  right: -60px;
  border-radius: 50%;
  background: rgba(173, 122, 68, 0.12);
  filter: blur(20px);
  pointer-events: none;
}

.dr-head,
.dr-profile,
.dr-nav,
.dr-divider,
.dr-foot,
.dr-item {
  position: relative;
  z-index: 1;
}

.dr-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 22px;
}

.dr-kicker {
  font-family: var(--fc-font);
  font-size: 11px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.dr-sub {
  margin-top: 6px;
  font-family: var(--fc-font);
  font-size: 22px;
  font-weight: 700;
  color: var(--fc-text);
}

.dr-close {
  width: 40px;
  height: 40px;
  border: 1px solid var(--fc-border);
  border-radius: 50%;
  background: rgba(255, 250, 243, 0.82);
  color: var(--fc-text);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: transform .2s ease, background .2s ease;
}

.dr-close:hover {
  background: #fffaf3;
  transform: rotate(90deg);
}

.dr-profile {
  padding: 18px;
  border: 1px solid var(--fc-border);
  border-radius: 22px;
  background: rgba(255, 250, 243, 0.72);
  box-shadow: var(--fc-shadow-soft);
  margin-bottom: 20px;
  display: grid;
  grid-template-columns: auto 1fr;
  align-items: center;
  gap: 14px;
}

.dr-avatar-shell {
  width: 68px;
  height: 68px;
  padding: 4px;
  border-radius: 50%;
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.9), rgba(221, 193, 163, 0.55));
}

.dr-avatar {
  width: 100%;
  height: 100%;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-family: var(--fc-font);
  font-size: 24px;
  font-weight: 600;
  box-shadow: 0 16px 32px rgba(61, 40, 22, 0.14);
}

.dr-avatar-img { object-fit: cover; border: none; }

.dr-profile-copy {
  min-width: 0;
}

.dr-name {
  font-family: var(--fc-font);
  font-size: 24px;
  font-weight: 700;
  color: var(--fc-text);
  line-height: 1.1;
  word-break: break-word;
}

.dr-id-line {
  margin-top: 8px;
  font-family: var(--fc-font);
  font-size: 13px;
  color: var(--fc-text-sec);
  letter-spacing: 0.04em;
}

.dr-section {
  margin-bottom: 10px;
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.dr-nav {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.dr-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 22px;
  background: rgba(255, 250, 243, 0.72);
  cursor: pointer;
  text-align: left;
  transition: transform .2s ease, border-color .2s ease, background .2s ease, box-shadow .2s ease;
}

.dr-item:hover {
  transform: translateX(-2px);
  border-color: rgba(77, 52, 31, 0.16);
  background: #fffaf3;
  box-shadow: var(--fc-shadow-soft);
}

.dr-item-icon {
  width: 42px;
  height: 42px;
  border-radius: 16px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--fc-accent-strong);
  background: rgba(243, 231, 215, 0.9);
  flex-shrink: 0;
}

.dr-item-copy {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
  flex: 1;
}

.dr-item-copy strong {
  font-family: var(--fc-font);
  font-size: 15px;
  font-weight: 600;
  color: var(--fc-text);
}

.dr-item-copy small {
  font-family: var(--fc-font);
  font-size: 12px;
  color: var(--fc-text-sec);
  line-height: 1.4;
}

.dr-item-arrow {
  font-size: 18px;
  color: var(--fc-text-muted);
}

.dr-divider {
  height: 1px;
  margin: 16px 2px;
  background: linear-gradient(90deg, transparent, rgba(77, 52, 31, 0.14), transparent);
}

.dr-item-danger { color: var(--fc-danger); }
.dr-item-danger .dr-item-icon { color: var(--fc-danger); }
.dr-item-danger .dr-item-copy strong { color: var(--fc-danger); }

.dr-foot {
  margin-top: auto;
  padding-top: 18px;
  font-family: var(--fc-font);
  font-size: 12px;
  color: var(--fc-text-muted);
  letter-spacing: 0.06em;
}

.dr-enter-active { transition: opacity .28s ease; }
.dr-leave-active { transition: opacity .22s ease; }
.dr-enter-active .dr-panel { transition: transform .28s cubic-bezier(.2,.8,.2,1); }
.dr-leave-active .dr-panel { transition: transform .2s ease; }
.dr-enter-from, .dr-leave-to { opacity: 0; }
.dr-enter-from .dr-panel, .dr-leave-to .dr-panel { transform: translateX(100%); }

@media (max-width: 640px) {
  .dr-panel {
    width: 100%;
    max-width: 100%;
  }
}
</style>
