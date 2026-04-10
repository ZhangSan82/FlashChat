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
.dlg-ov {
  background: var(--fc-backdrop);
  backdrop-filter: none;
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
}

.dlg-card {
  background: var(--fc-surface);
  border: 1px solid var(--fc-border);
  border-radius: 28px;
  box-shadow: var(--fc-shadow-panel);
  padding: 30px 30px 28px;
  width: 90%;
  max-width: 420px;
  max-height: 90vh;
  overflow-y: auto;
}

.dlg-h {
  font-family: var(--fc-font-display);
  font-size: 20px;
  color: var(--fc-text);
  margin: 0 0 8px;
}

.dlg-desc {
  color: var(--fc-text-sec);
  margin: 0 0 24px;
  line-height: 1.6;
}

.dlg-grp {
  margin-bottom: 16px;
}

.dlg-grp > label {
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
  color: var(--fc-text-muted);
  letter-spacing: 0.12em;
  text-transform: uppercase;
  display: block;
  margin-bottom: 8px;
}

.dlg-opt {
  color: var(--fc-text-muted);
  margin-left: 4px;
  font-weight: 400;
}

.dlg-input {
  width: 100%;
  background: var(--fc-bg);
  border: 1px solid var(--fc-border);
  border-radius: 16px;
  box-shadow: none;
  padding: 12px 16px;
  outline: none;
}

.dlg-input:focus {
  border-color: var(--fc-border-strong);
}

.dlg-input::placeholder {
  color: var(--fc-text-muted);
}

.dlg-error {
  background: rgba(255, 243, 241, 0.88);
  border: 1px solid rgba(187, 106, 94, 0.18);
  font-size: 13px;
  text-align: center;
  margin: 0 0 8px;
  padding: 9px 12px;
  border-radius: 14px;
}

.dlg-err-enter-active {
  transition: all 0.2s ease;
}

.dlg-err-leave-active {
  transition: all 0.15s ease;
}

.dlg-err-enter-from,
.dlg-err-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

.dlg-acts {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 22px;
}

.dlg-btn {
  border: 1px solid var(--fc-border);
  border-radius: 16px;
  padding: 11px 22px;
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

.dlg-cancel:hover {
  border-color: var(--fc-border-strong);
  background: var(--fc-bg-light);
}

.dlg-ok {
  background: var(--fc-accent);
  border-color: transparent;
  color: #fff;
}

.dlg-ok:hover:not(:disabled) {
  background: var(--fc-accent-strong);
  box-shadow: 0 10px 20px rgba(151, 90, 38, 0.18);
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
  transition: transform 0.25s ease;
}

.dlg-enter-from,
.dlg-leave-to {
  opacity: 0;
}

.dlg-enter-from .dlg-card,
.dlg-leave-to .dlg-card {
  transform: scale(0.95) translateY(10px);
}

.dlg-btn:focus-visible,
.dlg-input:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--fc-focus-ring);
}
</style>
