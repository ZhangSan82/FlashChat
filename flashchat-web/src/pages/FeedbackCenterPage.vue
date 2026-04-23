<template>
  <div class="fb-page">
    <div class="fb-shell">
      <header class="fb-toolbar">
        <button class="fb-back" type="button" @click="goBack">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M15 6l-6 6 6 6" />
          </svg>
          <span>返回上一页</span>
        </button>

        <transition name="fb-notice-fade">
          <div v-if="notice" class="fb-notice" :class="`is-${notice.type}`">{{ notice.text }}</div>
        </transition>
      </header>

      <section class="fb-hero">
        <article class="fb-main-card">
          <div class="fc-kicker">Feedback Center</div>
          <h1>意见箱与反馈中心</h1>
          <p>
            把问题、建议和体验反馈交给我们。第一版先聚焦“能提交、能定位、能处理”，
            所以每条反馈都会自动带上来源页面与场景上下文。
          </p>

          <div class="fb-meta-chips">
            <span>{{ identityChip }}</span>
            <span>来源页面 {{ form.sourcePage }}</span>
            <span>来源场景 {{ form.sourceScene }}</span>
          </div>
        </article>

        <aside class="fb-side">
          <article class="fb-side-card">
            <span>支持对象</span>
            <strong>游客 / 已注册用户</strong>
          </article>
          <article class="fb-side-card">
            <span>可上传截图</span>
            <strong>1 张，建议 5MB 以内</strong>
          </article>
          <article class="fb-side-card">
            <span>管理员处理</span>
            <strong>支持状态流转与备注沉淀</strong>
          </article>
        </aside>
      </section>

      <section class="fb-grid">
        <article class="fb-card">
          <div class="fb-card-head">
            <div>
              <div class="fc-kicker">Submit</div>
              <h2>提交反馈</h2>
            </div>
          </div>

          <form class="fb-form" @submit.prevent="submitForm">
            <label class="fb-field">
              <span>反馈类型</span>
              <select v-model="form.feedbackType">
                <option v-for="item in feedbackTypeOptions" :key="item.value" :value="item.value">
                  {{ item.label }}
                </option>
              </select>
            </label>

            <label class="fb-field fb-field-full">
              <span>反馈内容</span>
              <textarea
                v-model.trim="form.content"
                rows="7"
                placeholder="请尽量描述清楚你遇到的问题、出现步骤、期望结果，管理员会更容易定位。"
              ></textarea>
            </label>

            <label class="fb-field">
              <span>联系方式</span>
              <input
                v-model.trim="form.contact"
                type="text"
                placeholder="选填：邮箱、QQ、微信号等，便于后续回访"
              />
            </label>

            <label class="fb-field fb-toggle-field">
              <span>是否愿意被联系</span>
              <button class="fb-toggle" :class="{ active: form.willingContact }" type="button" @click="form.willingContact = !form.willingContact">
                <i></i>
                <strong>{{ form.willingContact ? '愿意' : '暂不需要' }}</strong>
              </button>
            </label>

            <div class="fb-field fb-field-full">
              <span>问题截图</span>
              <div class="fb-upload-panel">
                <template v-if="form.screenshotPreviewUrl">
                  <img class="fb-shot-preview" :src="form.screenshotPreviewUrl" alt="feedback screenshot" />
                  <div class="fb-upload-actions">
                    <button class="fb-secondary-btn" type="button" @click="triggerUpload" :disabled="uploading">重新上传</button>
                    <button class="fb-secondary-btn" type="button" @click="clearScreenshot" :disabled="uploading">清除截图</button>
                  </div>
                </template>
                <template v-else>
                  <div class="fb-upload-empty">
                    <strong>还没有上传截图</strong>
                    <p>可选。建议上传报错页、空白页或关键操作步骤截图。</p>
                    <button class="fb-secondary-btn" type="button" @click="triggerUpload" :disabled="uploading">
                      {{ uploading ? '上传中...' : '上传截图' }}
                    </button>
                  </div>
                </template>
                <input ref="fileInput" type="file" accept="image/*" class="fb-file-input" @change="onFileSelected" />
              </div>
            </div>

            <div class="fb-submit-row">
              <button class="fb-primary-btn" type="submit" :disabled="submitting || uploading">
                {{ submitting ? '提交中...' : '提交反馈' }}
              </button>
              <span class="fb-submit-hint">提交后管理员可在后台按类型、状态和场景进行筛选处理。</span>
            </div>
          </form>
        </article>

        <article class="fb-card">
          <div class="fb-card-head">
            <div>
              <div class="fc-kicker">Context</div>
              <h2>本次反馈会携带的信息</h2>
            </div>
          </div>

          <div class="fb-context-list">
            <article class="fb-context-item">
              <span>提交身份</span>
              <strong>{{ identityChip }}</strong>
              <small>{{ identityDetail }}</small>
            </article>
            <article class="fb-context-item">
              <span>来源页面</span>
              <strong>{{ form.sourcePage }}</strong>
              <small>帮助管理员快速定位你是在哪个页面触发反馈的。</small>
            </article>
            <article class="fb-context-item">
              <span>来源场景</span>
              <strong>{{ form.sourceScene }}</strong>
              <small>例如扫码进房、快速进入、手动提交等关键场景。</small>
            </article>
            <article class="fb-context-item">
              <span>处理闭环</span>
              <strong>NEW → PROCESSING → RESOLVED / CLOSED</strong>
              <small>这不是只收集留言的表单，而是可跟踪的反馈闭环。</small>
            </article>
          </div>
        </article>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { uploadFile } from '@/api/file'
import { submitFeedback } from '@/api/feedback'
import { useAuth } from '@/composables/useAuth'

const route = useRoute()
const router = useRouter()
const auth = useAuth()

const fileInput = ref(null)
const uploading = ref(false)
const submitting = ref(false)
const notice = ref(null)

const feedbackTypeOptions = [
  { value: 'SUGGESTION', label: '功能建议' },
  { value: 'BUG', label: 'Bug 反馈' },
  { value: 'EXPERIENCE', label: '体验问题' },
  { value: 'ACCOUNT', label: '账号问题' },
  { value: 'ROOM', label: '房间问题' },
  { value: 'OTHER', label: '其他' }
]

const form = reactive({
  feedbackType: 'BUG',
  content: '',
  contact: '',
  screenshotUrl: '',
  screenshotPreviewUrl: '',
  willingContact: false,
  sourcePage: resolveQueryValue(route.query.sourcePage, 'feedback_center'),
  sourceScene: resolveQueryValue(route.query.sourceScene, 'manual_submit')
})

const identityChip = computed(() => {
  if (!auth.identity.value) {
    return '匿名提交'
  }
  return auth.identity.value.isRegistered
    ? `已注册用户 ${auth.identity.value.accountId}`
    : `游客账号 ${auth.identity.value.accountId}`
})

const identityDetail = computed(() => {
  if (!auth.identity.value) {
    return '没有登录也可以提交，我们会按游客反馈处理。'
  }
  return `${auth.identity.value.nickname || '未命名用户'} · ${auth.identity.value.isRegistered ? '已注册身份' : '游客身份'}`
})

onMounted(async () => {
  if (auth.hasToken()) {
    await auth.checkOnly()
  }
})

onUnmounted(() => {
  window.clearTimeout(showNotice.timer)
})

function goBack() {
  if (window.history.length > 1) {
    router.back()
    return
  }
  router.push({ name: 'Welcome' })
}

function triggerUpload() {
  fileInput.value?.click()
}

async function onFileSelected(event) {
  const file = event.target.files?.[0]
  if (!file) return

  if (!file.type.startsWith('image/')) {
    showNotice('请上传图片格式的截图', 'error')
    resetFileInput()
    return
  }

  if (file.size > 5 * 1024 * 1024) {
    showNotice('截图大小不能超过 5MB', 'error')
    resetFileInput()
    return
  }

  uploading.value = true
  try {
    const result = await uploadFile(file)
    form.screenshotUrl = result.url || ''
    form.screenshotPreviewUrl = result.preview || result.url || ''
    showNotice('截图上传成功', 'success')
  } catch (error) {
    showNotice(error?.message || '截图上传失败', 'error')
  } finally {
    uploading.value = false
    resetFileInput()
  }
}

function clearScreenshot() {
  form.screenshotUrl = ''
  form.screenshotPreviewUrl = ''
}

async function submitForm() {
  if (form.content.trim().length < 5) {
    showNotice('反馈内容至少需要 5 个字符', 'error')
    return
  }

  submitting.value = true
  try {
    await submitFeedback({
      feedbackType: form.feedbackType,
      content: form.content.trim(),
      contact: form.contact.trim(),
      screenshotUrl: form.screenshotUrl || '',
      willingContact: form.willingContact,
      sourcePage: form.sourcePage,
      sourceScene: form.sourceScene
    })

    form.content = ''
    form.contact = ''
    form.screenshotUrl = ''
    form.screenshotPreviewUrl = ''
    form.willingContact = false
    showNotice('反馈已提交，感谢你的建议', 'success')
  } catch (error) {
    showNotice(error?.message || '反馈提交失败', 'error')
  } finally {
    submitting.value = false
  }
}

function resolveQueryValue(value, fallback) {
  if (Array.isArray(value)) {
    return value[0] || fallback
  }
  return value || fallback
}

function resetFileInput() {
  if (fileInput.value) {
    fileInput.value.value = ''
  }
}

function showNotice(text, type = 'info') {
  notice.value = { text, type }
  window.clearTimeout(showNotice.timer)
  showNotice.timer = window.setTimeout(() => {
    notice.value = null
  }, 2400)
}
showNotice.timer = null
</script>

<style scoped>
.fb-page {
  min-height: 100vh;
  padding: 20px 18px 28px;
  background: var(--fc-app-gradient);
}

.fb-shell {
  max-width: 1180px;
  margin: 0 auto;
  padding: 20px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 28px;
  background: rgba(255, 253, 249, 0.92);
  box-shadow: 0 18px 42px rgba(33, 26, 20, 0.08);
}

.fb-toolbar,
.fb-hero,
.fb-grid,
.fb-card-head,
.fb-submit-row {
  display: flex;
}

.fb-toolbar,
.fb-card-head {
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.fb-back,
.fb-primary-btn,
.fb-secondary-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 40px;
  padding: 10px 16px;
  border: 1px solid var(--fc-border);
  border-radius: 999px;
  font-family: var(--fc-font);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: border-color var(--fc-duration-normal) var(--fc-ease-in-out), background var(--fc-duration-normal) var(--fc-ease-in-out), box-shadow var(--fc-duration-normal) var(--fc-ease-in-out), transform var(--fc-duration-normal) var(--fc-ease-in-out);
}

.fb-back,
.fb-secondary-btn {
  background: rgba(255, 255, 255, 0.84);
  color: var(--fc-text);
}

.fb-back svg {
  width: 15px;
  height: 15px;
  stroke: currentColor;
  stroke-width: 1.9;
  fill: none;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.fb-back:hover,
.fb-secondary-btn:hover {
  border-color: var(--fc-border-strong);
  background: var(--fc-bg-light);
  box-shadow: 0 0 0 3px rgba(182, 118, 57, 0.08);
}

.fb-primary-btn {
  background: var(--fc-accent);
  border-color: transparent;
  color: #fffaf3;
  box-shadow: 0 10px 20px rgba(151, 90, 38, 0.16);
}

.fb-primary-btn:hover:not(:disabled) {
  background: var(--fc-accent-strong);
  transform: translateY(-1px);
}

.fb-notice {
  padding: 9px 12px;
  border-radius: 999px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  background: rgba(255, 255, 255, 0.88);
  font-size: 12px;
  font-weight: 600;
  color: var(--fc-text);
}

.fb-notice.is-success { color: var(--fc-success); }
.fb-notice.is-error { color: var(--fc-danger); }
.fb-notice-fade-enter-active,
.fb-notice-fade-leave-active {
  transition: opacity var(--fc-duration-normal) var(--fc-ease-in-out), transform var(--fc-duration-normal) var(--fc-ease-in-out);
}
.fb-notice-fade-enter-from,
.fb-notice-fade-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

.fb-hero {
  margin-top: 18px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 260px;
  gap: 14px;
}

.fb-main-card,
.fb-side-card,
.fb-card,
.fb-upload-panel,
.fb-context-item {
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.84);
}

.fb-main-card {
  padding: 22px;
}

.fb-main-card h1,
.fb-card-head h2 {
  margin: 10px 0 6px;
  font-family: var(--fc-font-display);
  font-size: clamp(30px, 4vw, 40px);
  line-height: 1.06;
  font-weight: 600;
  color: var(--fc-text);
}

.fb-card-head h2 {
  font-size: 24px;
}

.fb-main-card p {
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  color: var(--fc-text-sec);
}

.fb-meta-chips {
  margin-top: 16px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.fb-meta-chips span {
  padding: 6px 10px;
  border-radius: 999px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  background: rgba(255, 255, 255, 0.88);
  font-size: 12px;
  color: var(--fc-text-sec);
}

.fb-side {
  display: grid;
  gap: 12px;
}

.fb-side-card {
  padding: 18px;
}

.fb-side-card span,
.fb-context-item span,
.fb-field span {
  display: block;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.fb-side-card strong,
.fb-context-item strong {
  display: block;
  margin-top: 10px;
  font-family: var(--fc-font-display);
  font-size: 26px;
  line-height: 1.08;
  font-weight: 600;
  color: var(--fc-text);
}

.fb-grid {
  margin-top: 16px;
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(320px, 0.95fr);
  gap: 14px;
}

.fb-card {
  padding: 22px;
}

.fb-form {
  margin-top: 16px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.fb-field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.fb-field-full {
  grid-column: 1 / -1;
}

.fb-field select,
.fb-field input,
.fb-field textarea {
  width: 100%;
  border: 1px solid rgba(77, 52, 31, 0.1);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.94);
  color: var(--fc-text);
  padding: 12px 14px;
  transition: border-color var(--fc-duration-normal) var(--fc-ease-in-out), box-shadow var(--fc-duration-normal) var(--fc-ease-in-out);
}

.fb-field textarea {
  min-height: 160px;
  resize: vertical;
  line-height: 1.7;
}

.fb-toggle-field {
  justify-content: flex-end;
}

.fb-toggle {
  width: 100%;
  min-height: 48px;
  padding: 6px 10px;
  border: 1px solid rgba(77, 52, 31, 0.1);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.94);
  display: flex;
  align-items: center;
  gap: 10px;
}

.fb-toggle i {
  width: 42px;
  height: 24px;
  border-radius: 999px;
  background: rgba(77, 52, 31, 0.14);
  position: relative;
  transition: background var(--fc-duration-normal) var(--fc-ease-in-out);
}

.fb-toggle i::after {
  content: '';
  position: absolute;
  top: 3px;
  left: 3px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #fff;
  transition: transform var(--fc-duration-normal) var(--fc-ease-in-out);
}

.fb-toggle.active i {
  background: rgba(182, 118, 57, 0.45);
}

.fb-toggle.active i::after {
  transform: translateX(18px);
}

.fb-toggle strong {
  font-size: 14px;
  color: var(--fc-text);
}

.fb-upload-panel {
  padding: 16px;
  min-height: 220px;
}

.fb-shot-preview {
  width: 100%;
  max-height: 260px;
  border-radius: 18px;
  object-fit: cover;
}

.fb-upload-actions,
.fb-submit-row {
  margin-top: 14px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.fb-upload-empty {
  height: 100%;
  min-height: 186px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  gap: 10px;
}

.fb-upload-empty strong {
  font-family: var(--fc-font-display);
  font-size: 24px;
  color: var(--fc-text);
}

.fb-upload-empty p,
.fb-submit-hint,
.fb-context-item small {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--fc-text-sec);
}

.fb-file-input {
  display: none;
}

.fb-context-list {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.fb-context-item {
  padding: 16px;
}

.fb-context-item small {
  display: block;
  margin-top: 8px;
}

.fb-back:disabled,
.fb-primary-btn:disabled,
.fb-secondary-btn:disabled {
  opacity: 0.58;
  cursor: not-allowed;
  transform: none;
  box-shadow: none;
}

.fb-back:focus-visible,
.fb-primary-btn:focus-visible,
.fb-secondary-btn:focus-visible,
.fb-field input:focus-visible,
.fb-field select:focus-visible,
.fb-field textarea:focus-visible,
.fb-toggle:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--fc-focus-ring);
}

@media (max-width: 960px) {
  .fb-hero,
  .fb-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .fb-page {
    padding: 14px 12px 22px;
  }

  .fb-shell {
    padding: 16px;
    border-radius: 22px;
  }

  .fb-toolbar,
  .fb-card-head,
  .fb-submit-row,
  .fb-upload-actions {
    flex-direction: column;
    align-items: stretch;
  }

  .fb-form {
    grid-template-columns: 1fr;
  }

  .fb-field-full {
    grid-column: auto;
  }
}
</style>
