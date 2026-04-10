<template>
  <Teleport to="body">
    <transition name="dlg">
      <div v-if="visible" class="dlg-ov" @click.self="$emit('close')">
        <div class="dlg-card">
          <h3 class="dlg-h">{{ isChange ? '修改密码' : '设置密码' }}</h3>
          <p class="dlg-desc">{{ isChange ? '输入原密码和新密码' : '设置密码后可通过账号 ID + 密码重新登录' }}</p>

          <template v-if="isChange">
            <div class="dlg-grp">
              <label>原密码</label>
              <input v-model="form.oldPassword" type="password" placeholder="输入当前密码" class="dlg-input" />
            </div>
            <div class="dlg-grp">
              <label>新密码</label>
              <input v-model="form.newPassword" type="password" placeholder="6-32 位新密码" class="dlg-input" />
            </div>
            <div class="dlg-grp">
              <label>确认新密码</label>
              <input v-model="form.confirmNewPassword" type="password" placeholder="再次输入新密码" class="dlg-input" @keyup.enter="doSubmit" />
            </div>
          </template>

          <template v-else>
            <div class="dlg-grp">
              <label>密码</label>
              <input v-model="form.password" type="password" placeholder="6-32 位密码" class="dlg-input" />
            </div>
            <div class="dlg-grp">
              <label>确认密码</label>
              <input v-model="form.confirmPassword" type="password" placeholder="再次输入密码" class="dlg-input" @keyup.enter="doSubmit" />
            </div>
          </template>

          <transition name="dlg-err">
            <p v-if="error" class="dlg-error">{{ error }}</p>
          </transition>

          <div class="dlg-acts">
            <button class="dlg-btn dlg-cancel" @click="$emit('close')">取消</button>
            <button class="dlg-btn dlg-ok" :disabled="submitting" @click="doSubmit">
              {{ submitting ? '提交中...' : '确认' }}
            </button>
          </div>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<script setup>
import { reactive, ref, computed, watch } from 'vue'
import { setPassword, changePassword } from '@/api/account'

const props = defineProps({
  visible: Boolean,
  mode: { type: String, default: 'set' } // 'set' | 'change'
})
const emit = defineEmits(['success', 'close'])

const isChange = computed(() => props.mode === 'change')

const form = reactive({
  password: '', confirmPassword: '',
  oldPassword: '', newPassword: '', confirmNewPassword: ''
})
const error = ref('')
const submitting = ref(false)

watch(() => props.visible, (val) => {
  if (val) {
    form.password = ''; form.confirmPassword = ''
    form.oldPassword = ''; form.newPassword = ''; form.confirmNewPassword = ''
    error.value = ''
  }
})

async function doSubmit() {
  error.value = ''

  if (isChange.value) {
    if (!form.oldPassword) { error.value = '请输入原密码'; return }
    if (!form.newPassword) { error.value = '请输入新密码'; return }
    if (form.newPassword.length < 6) { error.value = '新密码至少 6 位'; return }
    if (form.newPassword !== form.confirmNewPassword) { error.value = '两次新密码不一致'; return }

    submitting.value = true
    try {
      await changePassword({
        oldPassword: form.oldPassword,
        newPassword: form.newPassword,
        confirmNewPassword: form.confirmNewPassword
      })
      emit('success')
    } catch (e) {
      error.value = e.message || '修改失败'
    } finally {
      submitting.value = false
    }
  } else {
    if (!form.password) { error.value = '请输入密码'; return }
    if (form.password.length < 6) { error.value = '密码至少 6 位'; return }
    if (form.password !== form.confirmPassword) { error.value = '两次密码不一致'; return }

    submitting.value = true
    try {
      await setPassword({
        password: form.password,
        confirmPassword: form.confirmPassword
      })
      emit('success')
    } catch (e) {
      error.value = e.message || '设置失败'
    } finally {
      submitting.value = false
    }
  }
}
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
  width: min(420px, 100%);
  max-height: min(90vh, 640px);
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
  margin: 0 0 8px;
}

.dlg-desc {
  margin: 0 0 24px;
  font-family: var(--fc-font);
  font-size: 13px;
  line-height: 1.6;
  color: var(--fc-text-sec);
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

.dlg-error {
  background: rgba(255, 243, 241, 0.88);
  border: 1px solid rgba(187, 106, 94, 0.18);
  font-family: var(--fc-font);
  font-size: 13px;
  line-height: 1.5;
  color: var(--fc-danger);
  text-align: center;
  margin: 0 0 8px;
  padding: 9px 12px;
  border-radius: 14px;
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
