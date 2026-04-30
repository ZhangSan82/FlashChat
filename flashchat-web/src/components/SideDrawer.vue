<template>
  <Teleport to="body">
    <transition name="dr">
      <div v-if="visible" class="dr-overlay" @click.self="$emit('close')">
        <aside class="dr-panel">
          <div class="dr-panel-glow"></div>
          <div class="dr-panel-grid"></div>

          <div class="dr-head">
            <div>
              <div class="fc-kicker">FlashChat</div>
              <div class="dr-sub">Salon Deck</div>
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

          <div class="dr-profile-note">
            从这里发起房间、浏览公开大厅，或者整理你的账户与邀请码。
          </div>

          <div class="dr-pills">
            <span>私人会客厅</span>
            <span>{{ accountId ? '身份已同步' : '游客模式' }}</span>
          </div>

          <div class="fc-section-label">快捷入口</div>
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
              <span class="dr-item-arrow" aria-hidden="true">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M9 6l6 6-6 6" />
                </svg>
              </span>
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
              <span class="dr-item-arrow" aria-hidden="true">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M9 6l6 6-6 6" />
                </svg>
              </span>
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
              <span class="dr-item-arrow" aria-hidden="true">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M9 6l6 6-6 6" />
                </svg>
              </span>
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
              <span class="dr-item-arrow" aria-hidden="true">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M9 6l6 6-6 6" />
                </svg>
              </span>
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
              <span class="dr-item-arrow" aria-hidden="true">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M9 6l6 6-6 6" />
                </svg>
              </span>
            </button>

            <button class="dr-item" type="button" @click="$emit('action', 'feedback')">
              <span class="dr-item-icon">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                  <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
                  <path d="M8 9h8" />
                  <path d="M8 13h5" />
                </svg>
              </span>
              <span class="dr-item-copy">
                <strong>意见反馈</strong>
                <small>提交 Bug、建议和使用体验问题</small>
              </span>
              <span class="dr-item-arrow" aria-hidden="true">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M9 6l6 6-6 6" />
                </svg>
              </span>
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
              <span class="dr-item-arrow" aria-hidden="true">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M9 6l6 6-6 6" />
                </svg>
              </span>
            </button>

            <button v-if="isAdmin" class="dr-item" type="button" @click="$emit('action', 'admin')">
              <span class="dr-item-icon">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                  <path d="M12 3l7 4v5c0 4.3-2.8 8.2-7 9-4.2-.8-7-4.7-7-9V7l7-4z" />
                  <path d="M9.4 12.2l1.8 1.8 3.6-4.1" />
                </svg>
              </span>
              <span class="dr-item-copy">
                <strong>管理员控制台</strong>
                <small>查看账号、房间与操作日志</small>
              </span>
              <span class="dr-item-arrow" aria-hidden="true">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M9 6l6 6-6 6" />
                </svg>
              </span>
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
            <span class="dr-item-arrow" aria-hidden="true">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <path d="M9 6l6 6-6 6" />
              </svg>
            </span>
          </button>

          <div class="dr-foot">
            <span>Private rooms · quiet conversations</span>
            <a
              class="dr-source-link"
              href="https://github.com/ZhangSan82/FlashChat.git"
              target="_blank"
              rel="noopener noreferrer"
            >
              GitHub 源码
            </a>
          </div>
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
  avatarUrl: { type: String, default: '' },
  isAdmin: { type: Boolean, default: false }
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
  z-index: 9000;
}

.dr-panel {
  position: relative;
  width: 400px;
  max-width: 92vw;
  height: 100%;
  padding: 26px 22px 18px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.98) 0%, rgba(252, 247, 239, 0.96) 100%);
  border-left: 1px solid var(--fc-border-strong);
  box-shadow: var(--fc-shadow-panel);
  display: flex;
  flex-direction: column;
  overflow-x: hidden;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
}

.dr-panel::before {
  display: none;
}

.dr-panel-glow {
  display: none;
}

.dr-panel-grid {
  display: none;
}

.dr-head,
.dr-profile,
.dr-profile-note,
.dr-pills,
.dr-nav,
.dr-divider,
.dr-foot,
.dr-item {
  position: relative;
  z-index: 1;
  flex-shrink: 0;
}

.dr-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 18px;
}

/* .dr-kicker → replaced by global .fc-kicker */

.dr-sub {
  margin-top: 6px;
  font-family: var(--fc-font-display);
  font-size: 22px;
  line-height: 1.1;
  font-weight: 600;
  color: var(--fc-text);
}

.dr-close {
  width: 40px;
  height: 40px;
  border: 1px solid var(--fc-border);
  border-radius: 50%;
  background: var(--fc-surface);
  color: var(--fc-text);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: border-color var(--fc-duration-normal) var(--fc-ease-in-out), background var(--fc-duration-normal) var(--fc-ease-in-out), color var(--fc-duration-normal) var(--fc-ease-in-out), box-shadow var(--fc-duration-normal) var(--fc-ease-in-out);
}

.dr-close:hover {
  border-color: var(--fc-border-strong);
  background: var(--fc-bg-light);
  color: var(--fc-accent-strong);
  box-shadow: 0 0 0 3px rgba(182, 118, 57, 0.08);
}

.dr-profile {
  padding: 18px;
  border: 1px solid var(--fc-border);
  border-radius: 24px;
  background: linear-gradient(180deg, #fffefb 0%, #fcf7f0 100%);
  margin-bottom: 12px;
  display: grid;
  grid-template-columns: auto 1fr;
  align-items: center;
  gap: 14px;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.86);
}

.dr-avatar-shell {
  width: 72px;
  height: 72px;
  padding: 5px;
  border-radius: 50%;
  background: var(--fc-bg);
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
  font-size: 20px;
  font-weight: 600;
  box-shadow: none;
}

.dr-avatar-img { object-fit: cover; border: none; }

.dr-profile-copy {
  min-width: 0;
}

.dr-name {
  font-family: var(--fc-font-display);
  font-size: 20px;
  line-height: 1.1;
  font-weight: 600;
  color: var(--fc-text);
  word-break: break-word;
}

.dr-id-line {
  margin-top: 8px;
  font-family: var(--fc-font);
  font-size: 13px;
  color: var(--fc-text-sec);
  letter-spacing: 0.04em;
}

.dr-profile-note {
  font-family: var(--fc-font);
  font-size: 13px;
  line-height: 1.6;
  color: var(--fc-text-sec);
}

.dr-pills {
  margin-top: 14px;
  margin-bottom: 18px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.dr-pills span {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 7px 12px;
  border-radius: var(--fc-radius-pill);
  border: 1px solid var(--fc-border);
  background: rgba(255, 255, 255, 0.76);
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
  color: var(--fc-accent-strong);
}

/* .dr-section → replaced by global .fc-section-label */
.dr-nav + .fc-section-label,
.dr-panel .fc-section-label {
  margin-bottom: 10px;
}

.dr-nav {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.dr-item {
  width: 100%;
  min-height: 88px;
  display: flex;
  align-items: flex-start;
  gap: 14px;
  padding: 16px;
  border: 1px solid var(--fc-border);
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.82);
  cursor: pointer;
  text-align: left;
  transition: border-color var(--fc-duration-normal) var(--fc-ease-in-out), background var(--fc-duration-normal) var(--fc-ease-in-out), box-shadow var(--fc-duration-normal) var(--fc-ease-in-out), color var(--fc-duration-normal) var(--fc-ease-in-out);
  position: relative;
  overflow: hidden;
}

.dr-item::before {
  display: none;
}

.dr-item:hover {
  border-color: var(--fc-border-strong);
  background: #fffdf8;
  box-shadow: 0 12px 24px rgba(33, 26, 20, 0.06);
}

.dr-item-icon {
  width: 46px;
  height: 46px;
  border-radius: 18px;
  border: 1px solid var(--fc-border);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--fc-accent-strong);
  background: var(--fc-bg-light);
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
  font-family: var(--fc-font-display);
  font-size: 16px;
  line-height: 1.15;
  font-weight: 600;
  color: var(--fc-text);
}

.dr-item-copy small {
  font-family: var(--fc-font);
  font-size: 12px;
  color: var(--fc-text-sec);
  line-height: 1.5;
}

.dr-item-arrow {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--fc-text-muted);
  padding-top: 4px;
  flex-shrink: 0;
}

.dr-item-arrow svg {
  display: block;
}

.dr-divider {
  height: 1px;
  margin: 16px 2px;
  background: var(--fc-border);
}

.dr-item-danger { color: var(--fc-danger); }
.dr-item-danger .dr-item-icon { color: var(--fc-danger); }
.dr-item-danger .dr-item-copy strong { color: var(--fc-danger); }

.dr-foot {
  margin-top: auto;
  padding-top: 18px;
  padding-bottom: 4px;
  font-family: var(--fc-font);
  font-size: 12px;
  color: var(--fc-text-muted);
  letter-spacing: 0.1em;
}

.dr-source-link {
  display: inline-flex;
  margin-top: 8px;
  color: var(--fc-accent-strong);
  font-weight: 600;
  letter-spacing: 0.06em;
  text-decoration: none;
  transition: color var(--fc-duration-normal) var(--fc-ease-in-out), opacity var(--fc-duration-normal) var(--fc-ease-in-out);
}

.dr-source-link:hover {
  color: var(--fc-accent);
  text-decoration: underline;
}

.dr-close:focus-visible,
.dr-item:focus-visible,
.dr-source-link:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--fc-focus-ring);
}

.dr-enter-active { transition: opacity var(--fc-duration-normal) var(--fc-ease-in-out); }
.dr-leave-active { transition: opacity var(--fc-duration-fast) var(--fc-ease-in-out); }
.dr-enter-active .dr-panel { transition: transform var(--fc-duration-normal) var(--fc-ease-out); }
.dr-leave-active .dr-panel { transition: transform var(--fc-duration-fast) var(--fc-ease-in-out); }
.dr-enter-from, .dr-leave-to { opacity: 0; }
.dr-enter-from .dr-panel, .dr-leave-to .dr-panel { transform: translateX(100%); }

@media (max-width: 640px) {
  .dr-panel {
    width: 100%;
    max-width: 100%;
  }
}
</style>
