<template>
  <Teleport to="body">
    <transition name="dr">
      <div v-if="visible" class="dr-overlay" @click.self="$emit('close')">
        <div class="dr-panel">
          <div class="dr-profile">
            <div class="dr-avatar" :style="{ background: avatarColor }">{{ (nickname||'?')[0].toUpperCase() }}</div>
            <div class="dr-name">{{ nickname }}</div>
            <div class="dr-id">{{ accountId }}</div>
          </div>
          <nav class="dr-nav">
            <div class="dr-item" @click="$emit('action','create')">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
              <span>创建房间</span>
            </div>
            <div class="dr-item" @click="$emit('action','join')">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4"/><polyline points="10 17 15 12 10 7"/><line x1="15" y1="12" x2="3" y2="12"/></svg>
              <span>加入房间</span>
            </div>
            <div class="dr-item" @click="$emit('action','profile')">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
              <span>我的资料</span>
            </div>
            <div class="dr-div"></div>
            <div class="dr-item dr-muted" @click="$emit('action','settings')">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
              <span>设置</span>
            </div>
          </nav>
          <div class="dr-foot">FlashChat v1.0</div>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<script setup>
defineProps({
  visible: Boolean,
  nickname: { type: String, default: '' },
  accountId: { type: String, default: '' },
  avatarColor: { type: String, default: '#C8956C' }
})
defineEmits(['close', 'action'])
</script>

<style scoped>
.dr-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.25); z-index: 9000; }
.dr-panel {
  position: absolute; top: 0; left: 0; bottom: 0; width: 270px; max-width: 80vw;
  background: #F5F0E8; display: flex; flex-direction: column; box-shadow: 4px 0 20px rgba(0,0,0,.12);
}
.dr-profile { padding: 28px 22px 22px; border-bottom: 1px solid #E5E0D8; }
.dr-avatar {
  width: 54px; height: 54px; border-radius: 50%; display: flex; align-items: center; justify-content: center;
  color: #fff; font-family: 'Poppins',sans-serif; font-size: 22px; font-weight: 600;
  box-shadow: 3px 3px 6px #D1CBC3, -3px -3px 6px #fff;
}
.dr-name { margin-top: 14px; font-family: 'Poppins',sans-serif; font-size: 16px; font-weight: 600; color: #2C2825; }
.dr-id { margin-top: 3px; font-family: 'Poppins',sans-serif; font-size: 12px; color: #8A857E; }
.dr-nav { flex: 1; padding: 10px 0; overflow-y: auto; }
.dr-item {
  display: flex; align-items: center; gap: 14px; padding: 13px 22px; cursor: pointer;
  font-family: 'Poppins',sans-serif; font-size: 14px; font-weight: 500; color: #2C2825; transition: background .15s;
}
.dr-item:hover { background: #EDE8DF; }
.dr-muted { color: #8A857E; }
.dr-div { height: 1px; background: #E5E0D8; margin: 8px 22px; }
.dr-foot { padding: 18px 22px; border-top: 1px solid #E5E0D8; font-family: 'Poppins',sans-serif; font-size: 11px; color: #B5B0A8; }

.dr-enter-active { transition: opacity .25s; }
.dr-leave-active { transition: opacity .2s; }
.dr-enter-active .dr-panel { transition: transform .25s cubic-bezier(.4,0,.2,1); }
.dr-leave-active .dr-panel { transition: transform .2s; }
.dr-enter-from, .dr-leave-to { opacity: 0; }
.dr-enter-from .dr-panel, .dr-leave-to .dr-panel { transform: translateX(-100%); }
</style>
