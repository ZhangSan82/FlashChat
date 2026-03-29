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
.dlg-ov { position:fixed;inset:0;background:rgba(0,0,0,.2);backdrop-filter:blur(4px);display:flex;align-items:center;justify-content:center;z-index:9999; }
.dlg-card { background:#F5F0E8;border-radius:20px;box-shadow:6px 6px 12px #D1CBC3,-6px -6px 12px #fff;padding:28px 32px;width:90%;max-width:380px; }
.dlg-h { font-family:'Poppins',sans-serif;font-size:20px;font-weight:700;color:#2C2825;margin:0 0 24px; }
.dlg-grp { margin-bottom:18px; }
.dlg-grp>label { display:block;font-family:'Poppins',sans-serif;font-size:13px;font-weight:500;color:#8A857E;margin-bottom:8px; }
.dlg-input { width:100%;padding:12px 16px;background:#F0EBE3;border:none;border-radius:10px;box-shadow:inset 3px 3px 6px #D1CBC3,inset -3px -3px 6px #fff;font-family:'Poppins',sans-serif;font-size:14px;color:#2C2825;outline:none; }
.dlg-input::placeholder { color:#B5B0A8; }
.dlg-acts { display:flex;justify-content:flex-end;gap:12px;margin-top:24px; }
.dlg-btn { padding:10px 24px;border:none;border-radius:10px;font-family:'Poppins',sans-serif;font-size:14px;font-weight:600;cursor:pointer;transition:all .2s; }
.dlg-cancel { background:#F5F0E8;box-shadow:3px 3px 6px #D1CBC3,-3px -3px 6px #fff;color:#8A857E; }
.dlg-ok { background:#C8956C;box-shadow:3px 3px 6px rgba(200,149,108,.3),-2px -2px 4px rgba(255,255,255,.6);color:#fff; }
.dlg-ok:disabled { opacity:.5;cursor:not-allowed; }

.dlg-enter-active,.dlg-leave-active { transition:opacity .25s; }
.dlg-enter-active .dlg-card,.dlg-leave-active .dlg-card { transition:transform .25s; }
.dlg-enter-from,.dlg-leave-to { opacity:0; }
.dlg-enter-from .dlg-card,.dlg-leave-to .dlg-card { transform:scale(.95) translateY(10px); }
</style>

<style scoped>
.dlg-ov {
  background: var(--fc-backdrop);
  backdrop-filter: blur(18px);
}

.dlg-card {
  background: linear-gradient(180deg, rgba(255, 250, 243, 0.96), rgba(247, 239, 228, 0.98));
  border: 1px solid rgba(77, 52, 31, 0.10);
  border-radius: 28px;
  box-shadow: 0 30px 60px rgba(61, 40, 22, 0.18);
  padding: 30px 30px 28px;
}

.dlg-h {
  font-family: var(--fc-font);
  font-size: 24px;
  color: var(--fc-text);
}

.dlg-grp > label {
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
  color: var(--fc-text-muted);
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.dlg-input {
  background: rgba(243, 231, 215, 0.92);
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 16px;
  box-shadow: none;
}

.dlg-input:focus {
  border-color: rgba(140, 90, 43, 0.24);
  box-shadow: 0 0 0 4px rgba(173, 122, 68, 0.08);
}

.dlg-btn {
  border: 1px solid rgba(77, 52, 31, 0.10);
  border-radius: 16px;
}

.dlg-cancel {
  background: rgba(255, 250, 243, 0.78);
  box-shadow: none;
  color: var(--fc-text-sec);
}

.dlg-ok {
  background: linear-gradient(135deg, #b68450 0%, #8c5a2b 100%);
  box-shadow: 0 16px 28px rgba(140, 90, 43, 0.22);
}
</style>
