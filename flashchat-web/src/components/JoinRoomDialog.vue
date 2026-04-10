<template>
  <Teleport to="body">
    <transition name="dlg">
      <div v-if="visible" class="dlg-ov" @click.self="$emit('close')">
        <div class="dlg-card">
          <h3 class="dlg-h">加入房间</h3>
          <div class="dlg-grp"><label>房间 ID</label>
            <input v-model="rid" type="text" placeholder="输入房间 ID..." class="dlg-input" @keyup.enter="ok"/>
          </div>
          <div class="dlg-acts">
            <button class="dlg-btn dlg-cancel" @click="$emit('close')">取消</button>
            <button class="dlg-btn dlg-ok" :disabled="!rid.trim()" @click="ok">加入</button>
          </div>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<script setup>
import { ref } from 'vue'
const emit = defineEmits(['join','close'])
defineProps({ visible: Boolean })
const rid = ref('')
function ok() { if(!rid.value.trim())return; emit('join',rid.value.trim()); rid.value='' }
</script>

<style scoped>
.dlg-ov {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: var(--fc-backdrop);
  z-index: 9999;
}

.dlg-card {
  width: min(396px, 100%);
  max-height: min(90vh, 560px);
  overflow-y: auto;
  background: var(--fc-surface);
  border: 1px solid var(--fc-border);
  border-radius: 28px;
  box-shadow: var(--fc-shadow-panel);
  padding: 30px 30px 28px;
  box-sizing: border-box;
}

.dlg-h {
  font-family: var(--fc-font-display);
  font-size: 20px;
  line-height: 1.15;
  color: var(--fc-text);
  margin: 0 0 24px;
}

.dlg-grp {
  margin-bottom: 16px;
}

.dlg-grp > label {
  display: block;
  margin-bottom: 8px;
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
  color: var(--fc-text-muted);
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.dlg-input {
  width: 100%;
  padding: 12px 16px;
  background: var(--fc-bg);
  border: 1px solid var(--fc-border);
  border-radius: 16px;
  box-shadow: none;
  color: var(--fc-text);
  font-family: var(--fc-font);
  font-size: 14px;
  line-height: 1.4;
  transition: border-color var(--fc-duration-normal) var(--fc-ease-in-out), background var(--fc-duration-normal) var(--fc-ease-in-out), box-shadow var(--fc-duration-normal) var(--fc-ease-in-out);
  box-sizing: border-box;
}

.dlg-input::placeholder {
  color: var(--fc-text-muted);
}

.dlg-input:focus,
.dlg-input:focus-visible {
  outline: none;
  border-color: var(--fc-border-strong);
  background: #fffdf8;
  box-shadow: 0 0 0 3px var(--fc-focus-ring);
}

.dlg-acts {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 24px;
}

.dlg-btn {
  min-width: 104px;
  padding: 11px 22px;
  border: 1px solid var(--fc-border);
  border-radius: 999px;
  font-family: var(--fc-font);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: border-color var(--fc-duration-normal) var(--fc-ease-in-out), background var(--fc-duration-normal) var(--fc-ease-in-out), color var(--fc-duration-normal) var(--fc-ease-in-out), box-shadow var(--fc-duration-normal) var(--fc-ease-in-out);
}

.dlg-cancel {
  background: var(--fc-surface);
  box-shadow: none;
  color: var(--fc-text-sec);
}

.dlg-cancel:hover:not(:disabled) {
  border-color: var(--fc-border-strong);
  background: var(--fc-bg-light);
  box-shadow: 0 0 0 3px rgba(182, 118, 57, 0.08);
}

.dlg-ok {
  background: var(--fc-accent);
  border-color: transparent;
  color: #fffaf3;
  box-shadow: 0 10px 20px rgba(151, 90, 38, 0.16);
}

.dlg-ok:hover:not(:disabled) {
  background: var(--fc-accent-strong);
  box-shadow: 0 14px 24px rgba(151, 90, 38, 0.2);
}

.dlg-ok:disabled,
.dlg-cancel:disabled {
  opacity: 0.52;
  cursor: not-allowed;
  box-shadow: none;
}

.dlg-enter-active,
.dlg-leave-active {
  transition: opacity 0.25s ease;
}

.dlg-enter-active .dlg-card,
.dlg-leave-active .dlg-card {
  transition: transform 0.25s ease, opacity 0.25s ease;
}

.dlg-enter-from,
.dlg-leave-to {
  opacity: 0;
}

.dlg-enter-from .dlg-card,
.dlg-leave-to .dlg-card {
  transform: scale(0.96) translateY(10px);
  opacity: 0;
}

.dlg-btn:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--fc-focus-ring);
}

@media (max-width: 640px) {
  .dlg-ov {
    padding: 16px;
  }

  .dlg-card {
    padding: 26px 20px 22px;
    border-radius: 24px;
  }

  .dlg-acts {
    flex-direction: column-reverse;
  }

  .dlg-btn {
    width: 100%;
  }
}
</style>
