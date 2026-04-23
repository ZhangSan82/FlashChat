<template>
  <Teleport to="body">
    <transition name="pp">
      <div v-if="visible" class="pp-overlay" @click.self="$emit('close')">
        <div class="pp-card">

          <div class="pp-hdr">
            <button class="pp-back" type="button" @click="editing ? cancelEdit() : $emit('close')">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#2C2825" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
            <div class="pp-head-copy">
              <span class="pp-kicker">Account Salon</span>
              <span class="pp-title">{{ editing ? '编辑资料' : '个人资料' }}</span>
            </div>
          </div>

          <div class="pp-body">
            <div v-if="loading" class="pp-loading"><div class="pp-spinner"></div></div>

            <template v-else-if="profile">

              <!-- ========== 编辑模式 ========== -->
              <template v-if="editing">
                <div class="pp-edit">

                  <!-- ★ FIX: 头像区域支持上传图片 -->
                  <div class="pp-avatar-edit-wrap" @click="triggerUpload">
                    <div class="pp-avatar-lg" v-if="!editForm.avatarUrl" :style="{ background: editForm.avatarColor }">
                      {{ (editForm.nickname || '?')[0].toUpperCase() }}
                    </div>
                    <img v-else class="pp-avatar-lg pp-avatar-img" :src="editForm.avatarUrl" alt="avatar" />
                    <div class="pp-avatar-overlay">
                      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2">
                        <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/>
                        <circle cx="12" cy="13" r="4"/>
                      </svg>
                    </div>
                    <input ref="fileInput" type="file" accept="image/*" class="pp-file-hidden" @change="onFileSelected" />
                  </div>
                  <p v-if="uploading" class="pp-upload-hint">上传中...</p>

                  <label class="pp-label">昵称</label>
                  <input v-model="editForm.nickname" class="pp-input" maxlength="20" placeholder="输入新昵称" />

                  <label class="pp-label">头像颜色<span class="pp-label-hint">（无自定义头像时显示）</span></label>
                  <div class="pp-colors">
                    <button v-for="c in avatarColors" :key="c"
                            class="pp-color-btn" :class="{ active: editForm.avatarColor === c }"
                            :style="{ background: c }" @click="editForm.avatarColor = c"
                    ></button>
                  </div>

                  <!-- 清除自定义头像按钮 -->
                  <button v-if="editForm.avatarUrl" class="pp-clear-avatar" type="button" @click="clearCustomAvatar">
                    清除自定义头像（回到颜色方案）
                  </button>

                  <p v-if="editError" class="pp-err">{{ editError }}</p>

                  <div class="pp-edit-acts">
                    <button class="pp-btn pp-btn-ghost" type="button" @click="cancelEdit">取消</button>
                    <button class="pp-btn pp-btn-primary" type="button" :disabled="saving" @click="saveProfile">
                      {{ saving ? '保存中...' : '保存修改' }}
                    </button>
                  </div>
                </div>
              </template>

              <!-- ========== 查看模式 ========== -->
              <template v-else>
                <div class="pp-profile-top">
                  <!-- ★ FIX: 查看模式也支持显示上传的头像 -->
                  <div class="pp-avatar-shell">
                    <div class="pp-avatar-lg" v-if="!profile.avatarUrl" :style="{ background: profile.avatarColor }">
                      {{ (profile.nickname || '?')[0].toUpperCase() }}
                    </div>
                    <img v-else class="pp-avatar-lg pp-avatar-img" :src="profile.avatarUrl" alt="avatar" />
                  </div>

                  <div class="pp-profile-meta">
                    <div class="pp-name">{{ profile.nickname }}</div>
                    <div class="pp-acid">FlashChat ID：{{ profile.accountId }}</div>
                  </div>
                  <button class="pp-edit-btn" type="button" aria-label="edit profile" @click="startEdit">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                      <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
                    </svg>
                  </button>
                </div>

                <div class="pp-info-card">
                  <div class="pp-info-row">
                    <span class="pp-info-label">邮箱</span>
                    <span class="pp-info-value">{{ profile.email || '未绑定' }}</span>
                  </div>
                  <div class="pp-info-row">
                    <span class="pp-info-label">积分</span>
                    <span class="pp-info-value pp-credits">{{ profile.credits ?? 0 }}</span>
                  </div>
                  <div class="pp-info-row">
                    <span class="pp-info-label">身份</span>
                    <span class="pp-info-value">
                      <span v-if="profile.isRegistered" class="pp-badge pp-badge-ok">注册用户</span>
                      <span v-else class="pp-badge pp-badge-anon">匿名成员</span>
                    </span>
                  </div>
                  <div class="pp-info-row" v-if="profile.inviteCode">
                    <span class="pp-info-label">邀请码</span>
                    <span class="pp-info-value pp-mono">{{ profile.inviteCode }}</span>
                  </div>
                  <div class="pp-info-row" v-if="profile.createTime">
                    <span class="pp-info-label">加入时间</span>
                    <span class="pp-info-value">{{ formatDate(profile.createTime) }}</span>
                  </div>
                </div>

                <div class="pp-actions">
                  <!-- ★ FIX: 签到按钮用 checkedIn 控制，不再每次打开重置 -->
                  <button class="pp-act-btn" type="button" @click="doCheckIn" :disabled="checkedIn">
                    <span class="pp-act-icon">{{ checkedIn ? '✅' : '📅' }}</span>
                    <span class="pp-act-text">{{ checkedIn ? '今日已签到' : '每日签到' }}</span>
                    <span class="pp-act-badge" v-if="!checkedIn">+10</span>
                  </button>

                  <button class="pp-act-btn" type="button" @click="$emit(profile.hasPassword ? 'change-password' : 'set-password')">
                    <span class="pp-act-icon">🔑</span>
                    <span class="pp-act-text">{{ profile.hasPassword ? '修改密码' : '设置密码' }}</span>
                  </button>

                  <button class="pp-act-btn pp-act-highlight" type="button" v-if="!profile.isRegistered" @click="$emit('upgrade')">
                    <span class="pp-act-icon">⬆️</span>
                    <span class="pp-act-text">升级为注册用户</span>
                    <span class="pp-act-hint">解锁创建房间等功能</span>
                  </button>

                  <div class="pp-act-divider"></div>
                  <button class="pp-act-btn" type="button" @click="$emit('logout')">
                    <span class="pp-act-icon">🚪</span><span class="pp-act-text">退出登录</span>
                  </button>
                  <button class="pp-act-btn pp-act-danger" type="button" @click="$emit('delete-account')">
                    <span class="pp-act-icon">❌</span><span class="pp-act-text">注销账号</span>
                  </button>
                </div>

                <transition name="pp-toast">
                  <div v-if="toastMsg" class="pp-toast" :class="`pp-toast-${toastType}`">{{ toastMsg }}</div>
                </transition>
              </template>
            </template>
          </div>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<script setup>
import { ref, reactive, watch } from 'vue'
import { useAuth } from '@/composables/useAuth'
import { getMyAccount, updateProfile, dailyCheckIn } from '@/api/account'
import { uploadFile } from '@/api/file'

const props = defineProps({ visible: Boolean })
const emit = defineEmits([
  'close', 'set-password', 'change-password',
  'upgrade', 'logout', 'delete-account', 'profile-updated'
])

const auth = useAuth()

const loading = ref(false)
const profile = ref(null)
const editing = ref(false)
const saving = ref(false)
const editError = ref('')
const uploading = ref(false)
const toastMsg = ref('')
const toastType = ref('info')
let toastTimer = null

// ★ FIX: 签到状态用 sessionStorage 持久化当天的签到记录
const checkedIn = ref(false)
const fileInput = ref(null)

const editForm = reactive({ nickname: '', avatarColor: '', avatarUrl: '', avatarRef: null })

const avatarColors = [
  '#FF6B6B', '#4ECDC4', '#45B7D1', '#96CEB4',
  '#FFEAA7', '#DDA0DD', '#98D8C8', '#F7DC6F',
  '#BB8FCE', '#85C1E9', '#F0B27A', '#82E0AA',
  '#D35400', '#8E44AD', '#2980B9', '#27AE60'
]

// ★ FIX: 检查今天是否已签到（用 sessionStorage 记录）
function getTodayKey() {
  const d = new Date()
  return `fc_checkin_${d.getFullYear()}_${d.getMonth() + 1}_${d.getDate()}`
}

function loadCheckedInStatus() {
  try {
    checkedIn.value = sessionStorage.getItem(getTodayKey()) === '1'
  } catch {
    checkedIn.value = false
  }
}

function markCheckedIn() {
  checkedIn.value = true
  try { sessionStorage.setItem(getTodayKey(), '1') } catch {}
}

watch(() => props.visible, async (val) => {
  if (val) {
    editing.value = false
    loadCheckedInStatus() // ★ FIX: 从 sessionStorage 恢复状态
    await fetchProfile()
  }
}, { immediate: true })

async function fetchProfile() {
  loading.value = true
  try {
    profile.value = await getMyAccount()
  } catch (e) {
    console.error('[Profile] 加载失败', e)
    showToast('加载失败', 'error')
  } finally {
    loading.value = false
  }
}

// ==================== 编辑 ====================
function startEdit() {
  editForm.nickname = profile.value.nickname || ''
  editForm.avatarColor = profile.value.avatarColor || '#C8956C'
  editForm.avatarUrl = profile.value.avatarUrl || ''
  editForm.avatarRef = null
  editError.value = ''
  editing.value = true
}

function cancelEdit() {
  editing.value = false
  editError.value = ''
}

function clearCustomAvatar() {
  editForm.avatarUrl = ''
  editForm.avatarRef = ''
}

// ★ FIX: 头像上传
function triggerUpload() {
  fileInput.value?.click()
}

async function onFileSelected(e) {
  const file = e.target.files?.[0]
  if (!file) return

  // 前端校验
  if (!file.type.startsWith('image/')) {
    editError.value = '请选择图片文件'
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    editError.value = '图片大小不能超过 5MB'
    return
  }

  uploading.value = true
  editError.value = ''
  try {
    const result = await uploadFile(file)
    editForm.avatarRef = result?.url || ''
    editForm.avatarUrl = result?.preview || result?.url || ''
  } catch (err) {
    editError.value = err.message || '上传失败'
  } finally {
    uploading.value = false
    // 清空 input，允许重复选择同一文件
    if (fileInput.value) fileInput.value.value = ''
  }
}

async function saveProfile() {
  const nick = editForm.nickname.trim()
  if (!nick) { editError.value = '昵称不能为空'; return }
  if (nick.length > 20) { editError.value = '昵称不能超过 20 个字符'; return }

  saving.value = true
  editError.value = ''

  try {
    const body = {}
    if (nick !== profile.value.nickname) body.nickname = nick
    if (editForm.avatarColor !== profile.value.avatarColor) body.avatarColor = editForm.avatarColor
    if (editForm.avatarRef !== null) body.avatarUrl = editForm.avatarRef

    if (Object.keys(body).length === 0) {
      editing.value = false
      return
    }

    await updateProfile(body)
    await fetchProfile()
    auth.updateLocalIdentity({
      nickname: profile.value?.nickname || nick,
      avatarColor: profile.value?.avatarColor || editForm.avatarColor,
      avatarUrl: profile.value?.avatarUrl || ''
    })
    editing.value = false
    showToast('修改成功', 'success')
    emit('profile-updated')
  } catch (e) {
    editError.value = e.message || '保存失败'
  } finally {
    saving.value = false
  }
}

// ==================== 签到 ====================
async function doCheckIn() {
  if (checkedIn.value) return
  try {
    const result = await dailyCheckIn()
    if (result === true) {
      markCheckedIn() // ★ FIX: 持久化到 sessionStorage
      showToast('签到成功 +10 积分', 'success')
      await fetchProfile()
    } else {
      markCheckedIn()
      showToast('今日已签到', 'info')
    }
  } catch (e) {
    showToast(e.message || '签到失败', 'error')
  }
}

function showToast(msg, type = 'info') {
  toastMsg.value = msg; toastType.value = type
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toastMsg.value = '' }, 2500)
}

function formatDate(dt) {
  if (!dt) return ''
  return new Date(dt).toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' })
}
</script>

<style scoped>
.pp-overlay {
  position: fixed;
  inset: 0;
  background: var(--fc-backdrop);
  z-index: 9500;
  display: flex;
  justify-content: flex-end;
}

.pp-card {
  width: 410px;
  max-width: 94vw;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--fc-panel-elevated);
  border-left: 1px solid var(--fc-border-strong);
  box-shadow: var(--fc-shadow-panel);
  position: relative;
  overflow: hidden;
}

.pp-card::before {
  display: none;
}

.pp-hdr {
  height: 72px;
  padding: 0 22px;
  border-bottom: 1px solid var(--fc-border);
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
  position: relative;
  z-index: 1;
}

.pp-head-copy {
  display: grid;
  gap: 4px;
}

.pp-kicker,
.pp-label,
.pp-info-label {
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.pp-back {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  border: 1px solid var(--fc-border);
  background: var(--fc-surface);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: transform var(--fc-duration-normal) var(--fc-ease-in-out), background var(--fc-duration-normal) var(--fc-ease-in-out);
}

.pp-back:hover {
  background: #fffaf3;
  transform: rotate(90deg);
}

.pp-title {
  font-family: var(--fc-font-display);
  font-size: 20px;
  line-height: 1.1;
  font-weight: 600;
  color: var(--fc-text);
}

.pp-body {
  flex: 1;
  overflow-y: auto;
  padding: 22px;
  position: relative;
  z-index: 1;
}

.pp-loading {
  display: flex;
  justify-content: center;
  padding: 72px 0;
}

.pp-spinner {
  width: 38px;
  height: 38px;
  border: 3px solid var(--fc-border);
  border-top-color: var(--fc-accent);
  border-radius: 50%;
  animation: pp-spin 0.7s linear infinite;
}

@keyframes pp-spin {
  to {
    transform: rotate(360deg);
  }
}

.pp-avatar-lg {
  width: 84px;
  height: 84px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto 14px;
  color: #fff;
  font-family: var(--fc-font);
  font-size: 22px;
  font-weight: 600;
  box-shadow: none;
}

.pp-avatar-img {
  object-fit: cover;
}

.pp-avatar-edit-wrap {
  position: relative;
  width: 84px;
  height: 84px;
  margin: 0 auto 14px;
  cursor: pointer;
  border-radius: 50%;
}

.pp-avatar-edit-wrap .pp-avatar-lg {
  margin: 0;
}

.pp-avatar-overlay {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  background: rgba(24, 15, 10, 0.36);
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0;
  transition: opacity var(--fc-duration-normal) var(--fc-ease-in-out);
}

.pp-avatar-edit-wrap:hover .pp-avatar-overlay {
  opacity: 1;
}

.pp-file-hidden {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
  width: 100%;
  height: 100%;
}

.pp-upload-hint,
.pp-act-hint {
  font-family: var(--fc-font);
  font-size: 12px;
  color: var(--fc-text-sec);
}

.pp-upload-hint {
  text-align: center;
  margin-bottom: 8px;
}

.pp-clear-avatar {
  display: block;
  margin: 12px auto 0;
  padding: 6px 14px;
  border: 0;
  background: transparent;
  font-family: var(--fc-font);
  font-size: 12px;
  color: var(--fc-danger);
  cursor: pointer;
  text-decoration: underline;
}

.pp-profile-top {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 14px;
  padding: 18px 20px;
  text-align: left;
  border: 1px solid var(--fc-border);
  border-radius: 26px;
  background: var(--fc-surface);
  margin-bottom: 18px;
  position: relative;
  overflow: hidden;
}

.pp-profile-top::before,
.pp-info-card::before,
.pp-act-btn::before {
  display: none;
}

.pp-avatar-shell {
  width: 68px;
  height: 68px;
  padding: 4px;
  border-radius: 50%;
  background: var(--fc-bg);
}

.pp-profile-top .pp-avatar-lg {
  width: 100%;
  height: 100%;
  margin: 0;
  border-radius: 50%;
  font-size: 20px;
  box-shadow: none;
}

.pp-profile-top .pp-avatar-img {
  border-radius: 50%;
}

.pp-profile-meta {
  min-width: 0;
}

.pp-name {
  margin: 0;
  font-family: var(--fc-font-display);
  font-size: 20px;
  line-height: 1.1;
  color: var(--fc-text);
  word-break: break-word;
}

.pp-acid {
  margin-top: 8px;
  margin-bottom: 0;
  font-size: 13px;
  color: var(--fc-text-sec);
  letter-spacing: 0.03em;
}

.pp-edit-btn {
  width: 42px;
  height: 42px;
  padding: 0;
  border: 1px solid var(--fc-border);
  border-radius: 50%;
  background: var(--fc-bg);
  box-shadow: none;
  color: var(--fc-accent-strong);
  justify-content: center;
  flex-shrink: 0;
}

.pp-edit-btn:hover {
  background: var(--fc-surface);
  border-color: var(--fc-border-strong);
}

.pp-info-card {
  background: var(--fc-surface);
  border: 1px solid var(--fc-border);
  border-radius: 24px;
  padding: 6px 0;
  margin-bottom: 18px;
  position: relative;
  overflow: hidden;
}

.pp-info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 18px;
  border-bottom: 1px solid var(--fc-border);
}

.pp-info-row:last-child {
  border-bottom: none;
}

.pp-info-value {
  font-family: var(--fc-font);
  font-size: 13px;
  font-weight: 500;
  color: var(--fc-text);
  text-align: right;
}

.pp-credits {
  color: var(--fc-accent-strong);
  font-weight: 600;
  font-size: 15px;
}

.pp-mono {
  font-family: var(--fc-font-mono);
  letter-spacing: 0.08em;
}

.pp-badge {
  padding: 4px 10px;
  border-radius: var(--fc-radius-pill);
  font-size: 11px;
  font-weight: 600;
}

.pp-badge-ok {
  background: rgba(235, 245, 230, 0.96);
  color: #42673f;
}

.pp-badge-anon {
  background: rgba(255, 242, 224, 0.96);
  color: #8b641c;
}

.pp-actions {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.pp-act-btn {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 15px 16px;
  border: 1px solid var(--fc-border);
  border-radius: 22px;
  background: var(--fc-surface);
  color: var(--fc-text);
  cursor: pointer;
  text-align: left;
  transition: background var(--fc-duration-normal) var(--fc-ease-in-out), box-shadow var(--fc-duration-normal) var(--fc-ease-in-out), transform var(--fc-duration-normal) var(--fc-ease-in-out);
}

.pp-act-btn:hover {
  background: var(--fc-bg);
  border-color: var(--fc-border-strong);
}

.pp-act-btn:disabled {
  opacity: 0.6;
  cursor: default;
  transform: none;
}

.pp-act-icon {
  width: 34px;
  height: 34px;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 14px;
  background: var(--fc-bg);
  font-size: 17px;
}

.pp-act-text {
  flex: 1;
  font-family: var(--fc-font);
  font-size: 14px;
  font-weight: 600;
}

.pp-act-badge {
  background: var(--fc-accent-strong);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  padding: 4px 8px;
  border-radius: var(--fc-radius-pill);
}

.pp-act-highlight {
  border-left: none;
  background: var(--fc-bg);
}

.pp-act-divider {
  margin: 8px 0;
  background: var(--fc-border);
  height: 1px;
}

.pp-act-danger {
  color: var(--fc-danger);
}

.pp-act-danger .pp-act-icon {
  background: rgba(253, 236, 234, 0.96);
}

.pp-input {
  background: var(--fc-bg);
  border: 1px solid var(--fc-border);
  border-radius: 16px;
  box-shadow: none;
  width: 100%;
  padding: 13px 16px;
  font-family: var(--fc-font);
  font-size: 14px;
  color: var(--fc-text);
  outline: none;
}

.pp-input:focus {
  border-color: var(--fc-border-strong);
  box-shadow: none;
}

.pp-edit {
  padding-top: 8px;
}

.pp-label {
  display: block;
  margin: 20px 0 8px;
}

.pp-label-hint {
  font-weight: 400;
  color: var(--fc-text-muted);
  font-size: 11px;
  margin-left: 4px;
  letter-spacing: 0;
  text-transform: none;
}

.pp-colors {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.pp-color-btn {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  border: 3px solid transparent;
  cursor: pointer;
  transition: transform var(--fc-duration-normal) var(--fc-ease-in-out), box-shadow var(--fc-duration-normal) var(--fc-ease-in-out), border-color var(--fc-duration-normal) var(--fc-ease-in-out);
  box-shadow: none;
}

.pp-color-btn.active {
  border-color: var(--fc-text);
  transform: scale(1.12);
}

.pp-color-btn:hover:not(.active) {
  transform: scale(1.06);
}

.pp-err {
  margin-top: 12px;
  text-align: center;
  font-family: var(--fc-font);
  font-size: 13px;
  color: var(--fc-danger);
}

.pp-edit-acts {
  display: flex;
  gap: 12px;
  margin-top: 24px;
}

.pp-btn {
  flex: 1;
  padding: 13px 16px;
  border-radius: 16px;
  font-family: var(--fc-font);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: transform var(--fc-duration-normal) var(--fc-ease-in-out), box-shadow var(--fc-duration-normal) var(--fc-ease-in-out), filter var(--fc-duration-normal) var(--fc-ease-in-out), opacity var(--fc-duration-normal) var(--fc-ease-in-out);
}

.pp-btn-ghost {
  border: 1px solid var(--fc-border);
  background: var(--fc-surface);
  box-shadow: none;
  color: var(--fc-text-sec);
}

.pp-btn-ghost:hover {
  background: var(--fc-bg);
  border-color: var(--fc-border-strong);
}

.pp-btn-primary {
  border: 1px solid transparent;
  background: var(--fc-accent);
  color: #fffaf3;
  box-shadow: none;
}

.pp-btn-primary:hover:not(:disabled) {
  background: var(--fc-accent-strong);
}

.pp-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.pp-toast {
  position: absolute;
  bottom: 84px;
  left: 50%;
  transform: translateX(-50%);
  padding: 10px 20px;
  background: var(--fc-surface);
  border: 1px solid var(--fc-border);
  box-shadow: none;
  border-radius: var(--fc-radius-pill);
  font-family: var(--fc-font);
  font-size: 13px;
  font-weight: 500;
  z-index: 10;
  white-space: nowrap;
}

.pp-toast-info { color: var(--fc-text); }
.pp-toast-success { background: rgba(235, 245, 230, 0.96); color: #42673f; }
.pp-toast-error { background: rgba(253, 236, 234, 0.96); color: #8b3a35; }

.pp-toast-enter-active { transition: all var(--fc-duration-normal) var(--fc-ease-in-out); }
.pp-toast-leave-active { transition: all var(--fc-duration-normal) var(--fc-ease-in-out); }
.pp-toast-enter-from,
.pp-toast-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(8px);
}

.pp-enter-active { transition: opacity var(--fc-duration-normal) var(--fc-ease-in-out); }
.pp-leave-active { transition: opacity var(--fc-duration-fast) var(--fc-ease-in-out); }
.pp-enter-active .pp-card { transition: transform var(--fc-duration-normal) var(--fc-ease-out); }
.pp-leave-active .pp-card { transition: transform var(--fc-duration-fast) var(--fc-ease-in-out); }
.pp-enter-from, .pp-leave-to { opacity: 0; }
.pp-enter-from .pp-card, .pp-leave-to .pp-card { transform: translateX(100%); }

@media (max-width: 640px) {
  .pp-card {
    width: 100%;
    max-width: 100%;
  }

  .pp-body {
    padding: 18px 16px calc(22px + env(safe-area-inset-bottom));
  }

  .pp-title {
    font-size: 18px;
  }

  .pp-edit-acts {
    flex-direction: column-reverse;
  }

  .pp-btn {
    width: 100%;
  }
}
</style>
