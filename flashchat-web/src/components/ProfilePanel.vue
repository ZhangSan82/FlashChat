<template>
  <Teleport to="body">
    <transition name="pp">
      <div v-if="visible" class="pp-overlay" @click.self="$emit('close')">
        <div class="pp-card">

          <div class="pp-hdr">
            <button class="pp-back" @click="editing ? cancelEdit() : $emit('close')">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#2C2825" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
            <span class="pp-title">{{ editing ? '编辑资料' : '个人资料' }}</span>
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
                  <button v-if="editForm.avatarUrl" class="pp-clear-avatar" @click="editForm.avatarUrl = ''">
                    清除自定义头像（回到颜色方案）
                  </button>

                  <p v-if="editError" class="pp-err">{{ editError }}</p>

                  <div class="pp-edit-acts">
                    <button class="pp-btn pp-btn-ghost" @click="cancelEdit">取消</button>
                    <button class="pp-btn pp-btn-primary" :disabled="saving" @click="saveProfile">
                      {{ saving ? '保存中...' : '保存修改' }}
                    </button>
                  </div>
                </div>
              </template>

              <!-- ========== 查看模式 ========== -->
              <template v-else>
                <div class="pp-profile-top">
                  <!-- ★ FIX: 查看模式也支持显示上传的头像 -->
                  <div class="pp-avatar-lg" v-if="!profile.avatarUrl" :style="{ background: profile.avatarColor }">
                    {{ (profile.nickname || '?')[0].toUpperCase() }}
                  </div>
                  <img v-else class="pp-avatar-lg pp-avatar-img" :src="profile.avatarUrl" alt="avatar" />

                  <div class="pp-name">{{ profile.nickname }}</div>
                  <div class="pp-acid">{{ profile.accountId }}</div>
                  <button class="pp-edit-btn" @click="startEdit">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                      <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
                    </svg>
                    编辑资料
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
                  <button class="pp-act-btn" @click="doCheckIn" :disabled="checkedIn">
                    <span class="pp-act-icon">{{ checkedIn ? '✅' : '📅' }}</span>
                    <span class="pp-act-text">{{ checkedIn ? '今日已签到' : '每日签到' }}</span>
                    <span class="pp-act-badge" v-if="!checkedIn">+10</span>
                  </button>

                  <button class="pp-act-btn" @click="$emit(profile.hasPassword ? 'change-password' : 'set-password')">
                    <span class="pp-act-icon">🔑</span>
                    <span class="pp-act-text">{{ profile.hasPassword ? '修改密码' : '设置密码' }}</span>
                  </button>

                  <button class="pp-act-btn pp-act-highlight" v-if="!profile.isRegistered" @click="$emit('upgrade')">
                    <span class="pp-act-icon">⬆️</span>
                    <span class="pp-act-text">升级为注册用户</span>
                    <span class="pp-act-hint">解锁创建房间等功能</span>
                  </button>

                  <div class="pp-act-divider"></div>
                  <button class="pp-act-btn" @click="$emit('logout')">
                    <span class="pp-act-icon">🚪</span><span class="pp-act-text">退出登录</span>
                  </button>
                  <button class="pp-act-btn pp-act-danger" @click="$emit('delete-account')">
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

const editForm = reactive({ nickname: '', avatarColor: '', avatarUrl: '' })

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
})

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
  editError.value = ''
  editing.value = true
}

function cancelEdit() {
  editing.value = false
  editError.value = ''
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
    editForm.avatarUrl = result.url
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
    // ★ FIX: 头像 URL 变更检测（包括清除头像的情况）
    if (editForm.avatarUrl !== (profile.value.avatarUrl || '')) body.avatarUrl = editForm.avatarUrl

    if (Object.keys(body).length === 0) {
      editing.value = false
      return
    }

    await updateProfile(body)
    auth.updateLocalIdentity(body)
    await fetchProfile()
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
.pp-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.25); z-index: 9500; display: flex; justify-content: flex-end; }
.pp-card { width: 340px; max-width: 88vw; height: 100%; background: #F5F0E8; display: flex; flex-direction: column; box-shadow: -4px 0 20px rgba(0,0,0,.12); }
.pp-hdr { height: 52px; display: flex; align-items: center; gap: 12px; padding: 0 16px; border-bottom: 1px solid #E5E0D8; flex-shrink: 0; }
.pp-back { width: 32px; height: 32px; border: none; border-radius: 50%; background: transparent; cursor: pointer; display: flex; align-items: center; justify-content: center; }
.pp-back:hover { background: #EDE8DF; }
.pp-title { font-family: 'Poppins', sans-serif; font-size: 16px; font-weight: 600; color: #2C2825; }
.pp-body { flex: 1; overflow-y: auto; padding: 24px 18px; }
.pp-loading { display: flex; justify-content: center; padding: 60px 0; }
.pp-spinner { width: 36px; height: 36px; border: 3px solid #E5E0D8; border-top-color: #C8956C; border-radius: 50%; animation: pp-spin 0.7s linear infinite; }
@keyframes pp-spin { to { transform: rotate(360deg); } }

/* 头像 */
.pp-avatar-lg { width: 72px; height: 72px; border-radius: 50%; display: flex; align-items: center; justify-content: center; margin: 0 auto 14px; color: #fff; font-family: 'Poppins', sans-serif; font-size: 28px; font-weight: 600; box-shadow: 4px 4px 10px #D1CBC3, -4px -4px 10px #fff; }
.pp-avatar-img { object-fit: cover; }

/* ★ 头像上传区域 */
.pp-avatar-edit-wrap { position: relative; width: 72px; height: 72px; margin: 0 auto 14px; cursor: pointer; border-radius: 50%; }
.pp-avatar-edit-wrap .pp-avatar-lg { margin: 0; }
.pp-avatar-overlay { position: absolute; inset: 0; border-radius: 50%; background: rgba(0,0,0,.35); display: flex; align-items: center; justify-content: center; opacity: 0; transition: opacity .2s; }
.pp-avatar-edit-wrap:hover .pp-avatar-overlay { opacity: 1; }
.pp-file-hidden { position: absolute; inset: 0; opacity: 0; cursor: pointer; width: 100%; height: 100%; }
.pp-upload-hint { text-align: center; font-family: 'Poppins', sans-serif; font-size: 12px; color: #C8956C; margin-bottom: 8px; }
.pp-clear-avatar { display: block; margin: 12px auto 0; padding: 6px 14px; border: none; border-radius: 8px; background: transparent; font-family: 'Poppins', sans-serif; font-size: 12px; color: #D4736C; cursor: pointer; text-decoration: underline; }

.pp-profile-top { text-align: center; margin-bottom: 24px; }
.pp-name { font-family: 'Poppins', sans-serif; font-size: 20px; font-weight: 700; color: #2C2825; margin-bottom: 4px; }
.pp-acid { font-family: 'Poppins', sans-serif; font-size: 12px; color: #B5B0A8; letter-spacing: 0.5px; margin-bottom: 14px; }
.pp-edit-btn { display: inline-flex; align-items: center; gap: 6px; padding: 8px 18px; border: none; border-radius: 20px; background: #F5F0E8; box-shadow: 3px 3px 6px #D1CBC3, -3px -3px 6px #fff; font-family: 'Poppins', sans-serif; font-size: 13px; font-weight: 500; color: #C8956C; cursor: pointer; transition: all .2s; }
.pp-edit-btn:hover { box-shadow: inset 2px 2px 4px #D1CBC3, inset -2px -2px 4px #fff; }
.pp-info-card { background: #EDE8DF; border-radius: 14px; box-shadow: inset 2px 2px 5px #D1CBC3, inset -2px -2px 5px #fff; padding: 4px 0; margin-bottom: 20px; }
.pp-info-row { display: flex; justify-content: space-between; align-items: center; padding: 12px 18px; border-bottom: 1px solid rgba(0,0,0,.04); }
.pp-info-row:last-child { border-bottom: none; }
.pp-info-label { font-family: 'Poppins', sans-serif; font-size: 13px; color: #8A857E; }
.pp-info-value { font-family: 'Poppins', sans-serif; font-size: 13px; font-weight: 500; color: #2C2825; }
.pp-credits { color: #C8956C; font-weight: 700; font-size: 15px; }
.pp-mono { font-family: 'SF Mono', 'Fira Code', monospace; letter-spacing: 1px; }
.pp-badge { padding: 2px 10px; border-radius: 20px; font-size: 11px; font-weight: 600; }
.pp-badge-ok { background: #E8F5E3; color: #3D6B35; }
.pp-badge-anon { background: #FFF3E0; color: #8B6914; }
.pp-actions { display: flex; flex-direction: column; gap: 8px; }
.pp-act-btn { display: flex; align-items: center; gap: 12px; padding: 14px 16px; border: none; border-radius: 12px; background: #F5F0E8; box-shadow: 3px 3px 6px #D1CBC3, -3px -3px 6px #fff; font-family: 'Poppins', sans-serif; font-size: 14px; color: #2C2825; cursor: pointer; transition: all .2s; text-align: left; }
.pp-act-btn:hover { box-shadow: inset 2px 2px 4px #D1CBC3, inset -2px -2px 4px #fff; }
.pp-act-btn:disabled { opacity: .6; cursor: default; }
.pp-act-icon { font-size: 18px; flex-shrink: 0; width: 24px; text-align: center; }
.pp-act-text { flex: 1; }
.pp-act-badge { background: #C8956C; color: #fff; font-size: 11px; font-weight: 600; padding: 2px 8px; border-radius: 10px; }
.pp-act-hint { font-size: 11px; color: #B5B0A8; margin-top: 2px; }
.pp-act-highlight { border-left: 3px solid #C8956C; }
.pp-act-danger { color: #D4736C; }
.pp-act-divider { height: 1px; background: #E5E0D8; margin: 4px 0; }
.pp-edit { padding-top: 8px; }
.pp-label { display: block; font-family: 'Poppins', sans-serif; font-size: 13px; font-weight: 500; color: #8A857E; margin: 20px 0 8px; }
.pp-label-hint { font-weight: 400; color: #B5B0A8; font-size: 11px; margin-left: 4px; }
.pp-input { width: 100%; padding: 12px 16px; background: #F0EBE3; border: none; border-radius: 10px; box-shadow: inset 3px 3px 6px #D1CBC3, inset -3px -3px 6px #fff; font-family: 'Poppins', sans-serif; font-size: 14px; color: #2C2825; outline: none; }
.pp-input:focus { box-shadow: inset 4px 4px 8px #CBC6BE, inset -4px -4px 8px #fff; }
.pp-colors { display: flex; flex-wrap: wrap; gap: 10px; }
.pp-color-btn { width: 32px; height: 32px; border-radius: 50%; border: 3px solid transparent; cursor: pointer; transition: all .2s; box-shadow: 2px 2px 4px #D1CBC3, -2px -2px 4px #fff; }
.pp-color-btn.active { border-color: #2C2825; transform: scale(1.15); }
.pp-color-btn:hover:not(.active) { transform: scale(1.1); }
.pp-err { font-family: 'Poppins', sans-serif; font-size: 13px; color: #D4736C; margin-top: 12px; text-align: center; }
.pp-edit-acts { display: flex; gap: 12px; margin-top: 24px; }
.pp-btn { flex: 1; padding: 12px; border: none; border-radius: 12px; font-family: 'Poppins', sans-serif; font-size: 14px; font-weight: 600; cursor: pointer; transition: all .2s; }
.pp-btn-ghost { background: #F5F0E8; box-shadow: 3px 3px 6px #D1CBC3, -3px -3px 6px #fff; color: #8A857E; }
.pp-btn-ghost:hover { box-shadow: inset 2px 2px 4px #D1CBC3, inset -2px -2px 4px #fff; }
.pp-btn-primary { background: #C8956C; box-shadow: 3px 3px 6px rgba(200,149,108,.3), -2px -2px 4px rgba(255,255,255,.6); color: #fff; }
.pp-btn-primary:hover { filter: brightness(1.05); }
.pp-btn-primary:disabled { opacity: .5; cursor: not-allowed; }
.pp-toast { position: absolute; bottom: 80px; left: 50%; transform: translateX(-50%); padding: 8px 20px; border-radius: 20px; font-family: 'Poppins', sans-serif; font-size: 13px; font-weight: 500; z-index: 10; white-space: nowrap; }
.pp-toast-info { background: #EDE8DF; color: #2C2825; }
.pp-toast-success { background: #E8F5E3; color: #3D6B35; }
.pp-toast-error { background: #FDECEA; color: #8B3A35; }
.pp-toast-enter-active { transition: all .3s ease; }
.pp-toast-leave-active { transition: all .2s ease; }
.pp-toast-enter-from, .pp-toast-leave-to { opacity: 0; transform: translateX(-50%) translateY(8px); }
.pp-enter-active { transition: opacity .25s; }
.pp-leave-active { transition: opacity .2s; }
.pp-enter-active .pp-card { transition: transform .25s cubic-bezier(.4,0,.2,1); }
.pp-leave-active .pp-card { transition: transform .2s; }
.pp-enter-from, .pp-leave-to { opacity: 0; }
.pp-enter-from .pp-card, .pp-leave-to .pp-card { transform: translateX(100%); }
</style>

<style scoped>
.pp-overlay {
  background: var(--fc-backdrop);
  backdrop-filter: blur(16px);
}

.pp-card {
  width: 410px;
  max-width: 94vw;
  background: linear-gradient(180deg, rgba(255, 250, 243, 0.96), rgba(247, 239, 228, 0.98));
  border-left: 1px solid rgba(77, 52, 31, 0.12);
  box-shadow: -20px 0 50px rgba(61, 40, 22, 0.18);
}

.pp-hdr {
  height: 72px;
  padding: 0 22px;
  border-bottom: 1px solid rgba(77, 52, 31, 0.08);
}

.pp-back {
  width: 40px;
  height: 40px;
  border: 1px solid var(--fc-border);
  background: rgba(255, 250, 243, 0.82);
  transition: transform .2s ease, background .2s ease;
}

.pp-back:hover {
  background: #fffaf3;
  transform: rotate(90deg);
}

.pp-title {
  font-family: var(--fc-font);
  font-size: 20px;
  font-weight: 700;
  color: var(--fc-text);
}

.pp-body { padding: 22px; }

.pp-avatar-lg {
  width: 84px;
  height: 84px;
  box-shadow: 0 18px 30px rgba(61, 40, 22, 0.14);
}

.pp-profile-top {
  padding: 24px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 26px;
  background: rgba(255, 250, 243, 0.74);
  box-shadow: var(--fc-shadow-soft);
  margin-bottom: 18px;
}

.pp-name {
  font-size: 24px;
  line-height: 1.08;
  color: var(--fc-text);
}

.pp-acid {
  margin-bottom: 18px;
  font-size: 13px;
  color: var(--fc-text-sec);
}

.pp-edit-btn {
  padding: 10px 18px;
  border: 1px solid rgba(77, 52, 31, 0.10);
  background: rgba(243, 231, 215, 0.9);
  box-shadow: none;
  color: var(--fc-accent-strong);
}

.pp-edit-btn:hover {
  background: #fffaf3;
  box-shadow: var(--fc-shadow-soft);
}

.pp-info-card {
  background: rgba(255, 250, 243, 0.74);
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 24px;
  box-shadow: var(--fc-shadow-soft);
  padding: 6px 0;
}

.pp-info-row {
  padding: 14px 18px;
  border-bottom: 1px solid rgba(77, 52, 31, 0.06);
}

.pp-info-label { color: var(--fc-text-muted); }
.pp-info-value { color: var(--fc-text); }

.pp-actions { gap: 10px; }

.pp-act-btn {
  padding: 15px 16px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 20px;
  background: rgba(255, 250, 243, 0.76);
  box-shadow: var(--fc-shadow-soft);
  color: var(--fc-text);
}

.pp-act-btn:hover {
  background: #fffaf3;
  box-shadow: 0 14px 26px rgba(61, 40, 22, 0.10);
}

.pp-act-highlight {
  border-left: none;
  background: linear-gradient(180deg, rgba(248, 232, 211, 0.95), rgba(239, 218, 191, 0.95));
}

.pp-act-divider {
  margin: 8px 0;
  background: linear-gradient(90deg, transparent, rgba(77, 52, 31, 0.14), transparent);
}

.pp-input {
  background: rgba(243, 231, 215, 0.92);
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 16px;
  box-shadow: none;
}

.pp-input:focus {
  border-color: rgba(140, 90, 43, 0.26);
  box-shadow: 0 0 0 4px rgba(173, 122, 68, 0.08);
}

.pp-color-btn {
  box-shadow: 0 10px 18px rgba(61, 40, 22, 0.10);
}

.pp-btn {
  border-radius: 16px;
}

.pp-btn-ghost {
  border: 1px solid rgba(77, 52, 31, 0.10);
  background: rgba(255, 250, 243, 0.78);
  box-shadow: none;
}

.pp-btn-ghost:hover {
  background: #fffaf3;
  box-shadow: var(--fc-shadow-soft);
}

.pp-btn-primary {
  background: linear-gradient(135deg, #b68450 0%, #8c5a2b 100%);
  box-shadow: 0 16px 28px rgba(140, 90, 43, 0.22);
}

.pp-toast {
  background: rgba(255, 250, 243, 0.94);
  border: 1px solid rgba(77, 52, 31, 0.08);
  box-shadow: 0 14px 24px rgba(61, 40, 22, 0.12);
}

.pp-enter-active { transition: opacity .28s ease; }
.pp-leave-active { transition: opacity .22s ease; }
.pp-enter-active .pp-card { transition: transform .28s cubic-bezier(.2,.8,.2,1); }
.pp-leave-active .pp-card { transition: transform .2s ease; }
.pp-enter-from, .pp-leave-to { opacity: 0; }
.pp-enter-from .pp-card, .pp-leave-to .pp-card { transform: translateX(100%); }

@media (max-width: 640px) {
  .pp-card {
    width: 100%;
    max-width: 100%;
  }
}
</style>
