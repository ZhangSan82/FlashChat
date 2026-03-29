<template>
  <Teleport to="body">
    <transition name="dlg">
      <div v-if="visible" class="dlg-ov" @click.self="$emit('close')">
        <div class="dlg-card">
          <h3 class="dlg-h">创建新房间</h3>
          <div class="dlg-grp"><label>房间名称</label>
            <input v-model="f.title" type="text" placeholder="给房间起个名字..." maxlength="30" class="dlg-input"/>
          </div>
          <div class="dlg-grp"><label>房间时长</label>
            <div class="dlg-durs">
              <button v-for="o in opts" :key="o.v" :class="['dlg-dur', {active:f.dur===o.v}]" @click="f.dur=o.v">{{o.l}}</button>
            </div>
          </div>
          <div class="dlg-grp"><label>最大人数</label>
            <input v-model.number="f.max" type="number" min="2" max="200" class="dlg-input"/>
          </div>
          <div class="dlg-grp">
            <label class="dlg-chk"><input v-model="pub" type="checkbox"/><span>公开房间（在房间列表中展示）</span></label>
          </div>
          <div class="dlg-acts">
            <button class="dlg-btn dlg-cancel" @click="$emit('close')">取消</button>
            <button class="dlg-btn dlg-ok" :disabled="!f.title.trim()" @click="ok">创建</button>
          </div>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<script setup>
import { reactive, ref } from 'vue'

const emit = defineEmits(['create', 'close'])
defineProps({ visible: Boolean })

const opts = [
  {l:'10 分钟',v:'MIN_10'},{l:'30 分钟',v:'MIN_30'},{l:'1 小时',v:'HOUR_1'},{l:'2 小时',v:'HOUR_2'},
  {l:'6 小时',v:'HOUR_6'},{l:'12 小时',v:'HOUR_12'},{l:'24 小时',v:'HOUR_24'},{l:'3 天',v:'DAY_3'},{l:'7 天',v:'DAY_7'}
]
const f = reactive({ title: '', dur: 'MIN_30', max: 50 })
const pub = ref(false)

function ok() {
  if (!f.title.trim()) return
  // ★ 只传后端 RoomCreateReqDTO 需要的字段，不传 accountId
  emit('create', {
    title: f.title.trim(),
    duration: f.dur,
    maxMembers: f.max,
    isPublic: pub.value ? 1 : 0
  })
  f.title = ''; f.dur = 'MIN_30'; f.max = 50; pub.value = false
}
</script>

<style scoped>
.dlg-ov { position:fixed;inset:0;background:rgba(0,0,0,.2);backdrop-filter:blur(4px);display:flex;align-items:center;justify-content:center;z-index:9999; }
.dlg-card { background:#F5F0E8;border-radius:20px;box-shadow:6px 6px 12px #D1CBC3,-6px -6px 12px #fff;padding:28px 32px;width:90%;max-width:440px;max-height:90vh;overflow-y:auto; }
.dlg-h { font-family:'Poppins',sans-serif;font-size:20px;font-weight:700;color:#2C2825;margin:0 0 24px; }
.dlg-grp { margin-bottom:18px; }
.dlg-grp>label { display:block;font-family:'Poppins',sans-serif;font-size:13px;font-weight:500;color:#8A857E;margin-bottom:8px; }
.dlg-input { width:100%;padding:12px 16px;background:#F0EBE3;border:none;border-radius:10px;box-shadow:inset 3px 3px 6px #D1CBC3,inset -3px -3px 6px #fff;font-family:'Poppins',sans-serif;font-size:14px;color:#2C2825;outline:none; }
.dlg-input:focus { box-shadow:inset 4px 4px 8px #CBC6BE,inset -4px -4px 8px #fff; }
.dlg-input::placeholder { color:#B5B0A8; }
.dlg-durs { display:flex;flex-wrap:wrap;gap:8px; }
.dlg-dur { padding:8px 14px;background:#F5F0E8;border:none;border-radius:10px;box-shadow:3px 3px 6px #D1CBC3,-3px -3px 6px #fff;font-family:'Poppins',sans-serif;font-size:13px;color:#8A857E;cursor:pointer;transition:all .2s; }
.dlg-dur:hover { box-shadow:4px 4px 8px #D1CBC3,-4px -4px 8px #fff; }
.dlg-dur.active { background:#EDE8DF;box-shadow:inset 3px 3px 6px #D1CBC3,inset -3px -3px 6px #fff;color:#C8956C;font-weight:600; }
.dlg-chk { display:flex!important;align-items:center;gap:8px;cursor:pointer; }
.dlg-chk input { width:16px;height:16px;accent-color:#C8956C; }
.dlg-chk span { font-size:14px;color:#2C2825; }
.dlg-acts { display:flex;justify-content:flex-end;gap:12px;margin-top:24px; }
.dlg-btn { padding:10px 24px;border:none;border-radius:10px;font-family:'Poppins',sans-serif;font-size:14px;font-weight:600;cursor:pointer;transition:all .2s; }
.dlg-cancel { background:#F5F0E8;box-shadow:3px 3px 6px #D1CBC3,-3px -3px 6px #fff;color:#8A857E; }
.dlg-cancel:hover { box-shadow:inset 3px 3px 6px #D1CBC3,inset -3px -3px 6px #fff; }
.dlg-ok { background:#C8956C;box-shadow:3px 3px 6px rgba(200,149,108,.3),-2px -2px 4px rgba(255,255,255,.6);color:#fff; }
.dlg-ok:hover { filter:brightness(1.05); }
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

.dlg-durs { gap: 10px; }

.dlg-dur {
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 999px;
  background: rgba(255, 250, 243, 0.72);
  box-shadow: none;
  color: var(--fc-text-sec);
}

.dlg-dur:hover {
  background: #fffaf3;
  box-shadow: var(--fc-shadow-soft);
}

.dlg-dur.active {
  background: linear-gradient(135deg, #b68450 0%, #8c5a2b 100%);
  border-color: transparent;
  box-shadow: 0 14px 24px rgba(140, 90, 43, 0.18);
  color: #fffaf3;
}

.dlg-chk span { color: var(--fc-text); }

.dlg-btn {
  border: 1px solid rgba(77, 52, 31, 0.10);
  border-radius: 16px;
}

.dlg-cancel {
  background: rgba(255, 250, 243, 0.78);
  box-shadow: none;
  color: var(--fc-text-sec);
}

.dlg-cancel:hover {
  background: #fffaf3;
  box-shadow: var(--fc-shadow-soft);
}

.dlg-ok {
  background: linear-gradient(135deg, #b68450 0%, #8c5a2b 100%);
  box-shadow: 0 16px 28px rgba(140, 90, 43, 0.22);
}
</style>
