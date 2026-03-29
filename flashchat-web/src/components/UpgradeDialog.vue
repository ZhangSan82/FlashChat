<template>
  <Teleport to="body">
    <transition name="dlg">
      <div v-if="visible" class="dlg-ov" @click.self="$emit('close')">
        <div class="dlg-card">
          <h3 class="dlg-h">升级为注册用户</h3>
          <p class="dlg-desc">设置密码和邮箱后即可解锁创建房间、管理成员等功能</p>

          <div class="dlg-grp">
            <label>邮箱</label>
            <input v-model="form.email" type="email" placeholder="your@email.com" class="dlg-input" />
          </div>

          <div class="dlg-grp">
            <label>密码</label>
            <input v-model="form.password" type="password" placeholder="6-32 位密码" class="dlg-input" />
          </div>

          <div class="dlg-grp">
            <label>确认密码</label>
            <input v-model="form.confirmPassword" type="password" placeholder="再次输入密码" class="dlg-input" @keyup.enter="doUpgrade" />
          </div>

          <div class="dlg-grp">
            <label>邀请码<span class="dlg-opt">（选填）</span></label>
            <input v-model="form.inviteCode" type="text" placeholder="输入邀请码可获得额外积分" class="dlg-input" maxlength="16" />
          </div>

          <transition name="dlg-err">
            <p v-if="error" class="dlg-error">{{ error }}</p>
          </transition>

          <div class="dlg-acts">
            <button class="dlg-btn dlg-cancel" @click="$emit('close')">取消</button>
            <button class="dlg-btn dlg-ok" :disabled="submitting" @click="doUpgrade">
              {{ submitting ? '升级中...' : '确认升级' }}
            </button>
          </div>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import { upgradeAccount } from '@/api/account'

const props = defineProps({ visible: Boolean })
const emit = defineEmits(['upgraded', 'close'])

const form = reactive({ email: '', password: '', confirmPassword: '', inviteCode: '' })
const error = ref('')
const submitting = ref(false)

watch(() => props.visible, (val) => {
  if (val) {
    form.email = ''; form.password = ''; form.confirmPassword = ''; form.inviteCode = ''
    error.value = ''
  }
})

async function doUpgrade() {
  error.value = ''

  if (!form.email.trim()) { error.value = '请输入邮箱'; return }
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim())) { error.value = '邮箱格式不正确'; return }
  if (!form.password) { error.value = '请输入密码'; return }
  if (form.password.length < 6) { error.value = '密码至少 6 位'; return }
  if (form.password !== form.confirmPassword) { error.value = '两次密码不一致'; return }

  submitting.value = true
  try {
    const resp = await upgradeAccount({
      email: form.email.trim(),
      password: form.password,
      confirmPassword: form.confirmPassword,
      inviteCode: form.inviteCode.trim() || undefined
    })
    emit('upgraded', resp)
  } catch (e) {
    error.value = e.message || '升级失败，请重试'
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.dlg-ov { position:fixed;inset:0;background:rgba(0,0,0,.2);backdrop-filter:blur(4px);display:flex;align-items:center;justify-content:center;z-index:9999; }
.dlg-card { background:#F5F0E8;border-radius:20px;box-shadow:6px 6px 12px #D1CBC3,-6px -6px 12px #fff;padding:28px 32px;width:90%;max-width:420px;max-height:90vh;overflow-y:auto; }
.dlg-h { font-family:'Poppins',sans-serif;font-size:20px;font-weight:700;color:#2C2825;margin:0 0 6px; }
.dlg-desc { font-family:'Poppins',sans-serif;font-size:13px;color:#8A857E;margin:0 0 24px;line-height:1.5; }
.dlg-grp { margin-bottom:16px; }
.dlg-grp>label { display:block;font-family:'Poppins',sans-serif;font-size:13px;font-weight:500;color:#8A857E;margin-bottom:8px; }
.dlg-opt { font-weight:400;color:#B5B0A8;margin-left:4px; }
.dlg-input { width:100%;padding:12px 16px;background:#F0EBE3;border:none;border-radius:10px;box-shadow:inset 3px 3px 6px #D1CBC3,inset -3px -3px 6px #fff;font-family:'Poppins',sans-serif;font-size:14px;color:#2C2825;outline:none; }
.dlg-input:focus { box-shadow:inset 4px 4px 8px #CBC6BE,inset -4px -4px 8px #fff; }
.dlg-input::placeholder { color:#B5B0A8; }
.dlg-error { font-family:'Poppins',sans-serif;font-size:13px;color:#D4736C;text-align:center;margin:0 0 8px;padding:8px;background:rgba(212,115,108,.08);border-radius:8px; }
.dlg-err-enter-active { transition:all .2s; }
.dlg-err-leave-active { transition:all .15s; }
.dlg-err-enter-from,.dlg-err-leave-to { opacity:0;transform:translateY(-4px); }
.dlg-acts { display:flex;justify-content:flex-end;gap:12px;margin-top:20px; }
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

.dlg-desc {
  color: var(--fc-text-sec);
}

.dlg-grp > label {
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
  color: var(--fc-text-muted);
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.dlg-opt {
  color: var(--fc-text-muted);
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

.dlg-error {
  background: rgba(255, 243, 241, 0.88);
  border: 1px solid rgba(187, 106, 94, 0.18);
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
