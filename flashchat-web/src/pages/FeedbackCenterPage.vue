<template>
  <div class="fc-workspace fb-page">
    <PageNoticeToast :notice="notice" />

    <div class="fc-shell fc-shell-sm fb-shell">
      <header class="fc-toolbar">
        <button class="fc-btn fc-btn-back" type="button" @click="goBack">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M15 6l-6 6 6 6" />
          </svg>
          <span>返回上一页</span>
        </button>
        <span class="fb-toolbar-hint">你的反馈将直达团队工作台</span>
      </header>

      <section class="fc-hero">
        <div class="fc-hero-kicker">Feedback Center</div>
        <h1>把体验告诉我们</h1>
        <p class="fc-hero-lede">
          问题、建议、使用感受 —— 任何一句留言都会被认真读过。描述越具体，我们越能尽快定位并处理。
        </p>
        <div class="fc-identity">
          <span>快速回复</span>
          <span>可附截图</span>
          <span>可匿名提交</span>
        </div>
      </section>

      <section class="fb-form-stack">
        <article class="fc-panel fc-panel-featured">
          <div class="fc-panel-head">
            <div class="fc-panel-head-copy">
              <span class="fc-section-label">Submit</span>
              <h2>提交反馈</h2>
            </div>
            <span class="fb-step">第 1 步 · 填写信息</span>
          </div>

          <form class="fb-form" @submit.prevent="submitForm">
            <label class="fc-field">
              <span>反馈类型</span>
              <select v-model="form.feedbackType">
                <option v-for="item in feedbackTypeOptions" :key="item.value" :value="item.value">
                  {{ item.label }}
                </option>
              </select>
            </label>

            <label class="fc-field fb-toggle-field">
              <span>是否愿意联系</span>
              <button
                class="fb-toggle"
                :class="{ 'is-on': form.willingContact }"
                type="button"
                @click="form.willingContact = !form.willingContact"
              >
                <i></i>
                <strong>{{ form.willingContact ? '愿意，方便时请联系我' : '暂不需要联系' }}</strong>
              </button>
            </label>

            <label class="fc-field fc-field-full">
              <span>反馈内容</span>
              <textarea
                v-model.trim="form.content"
                rows="7"
                placeholder="请尽量描述清楚你遇到的问题、出现步骤和期望结果。"
              ></textarea>
              <small class="fb-hint">建议 ≥ 30 字，覆盖场景、现象与期望结果。</small>
            </label>

            <label class="fc-field fc-field-full">
              <span>联系方式（选填）</span>
              <input
                v-model.trim="form.contact"
                type="text"
                placeholder="邮箱、QQ、微信号等，便于后续联系"
              />
            </label>

            <div class="fc-field fc-field-full">
              <span>问题截图</span>
              <small class="fb-hint">可选，建议上传 1 张截图，大小不超过 5 MB。</small>
              <div class="fb-upload" :class="{ 'is-filled': form.screenshotPreviewUrl }">
                <template v-if="form.screenshotPreviewUrl">
                  <img class="fb-shot" :src="form.screenshotPreviewUrl" alt="feedback screenshot" />
                  <div class="fb-upload-actions">
                    <button class="fc-btn" type="button" @click="triggerUpload" :disabled="uploading">
                      {{ uploading ? '上传中...' : '重新上传' }}
                    </button>
                    <button class="fc-btn" type="button" @click="clearScreenshot" :disabled="uploading">清除截图</button>
                  </div>
                </template>
                <template v-else>
                  <div class="fb-upload-empty">
                    <div class="fb-upload-icon" aria-hidden="true">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
                        <path d="M4 16v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2" stroke-linecap="round" />
                        <path d="M12 4v12" stroke-linecap="round" />
                        <path d="m7 9 5-5 5 5" stroke-linecap="round" stroke-linejoin="round" />
                      </svg>
                    </div>
                    <strong>拖放图片或点击上传</strong>
                    <p>报错页、空白页或关键操作步骤都有助我们复现问题。</p>
                    <button class="fc-btn" type="button" @click="triggerUpload" :disabled="uploading">
                      {{ uploading ? '上传中...' : '选择截图' }}
                    </button>
                  </div>
                </template>
                <input ref="fileInput" type="file" :accept="IMAGE_FILE_ACCEPT" class="fb-file-input" @change="onFileSelected" />
              </div>
            </div>

            <div class="fc-field fc-field-full fb-submit">
              <button class="fc-btn fc-btn-primary fb-submit-btn" type="submit" :disabled="submitting || uploading">
                {{ submitting ? '提交中...' : '提交反馈' }}
              </button>
              <span class="fb-submit-hint">提交后我们会尽快查看并处理 · 感谢你的耐心</span>
            </div>
          </form>
        </article>
      </section>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { uploadFile } from '@/api/file'
import { submitFeedback } from '@/api/feedback'
import PageNoticeToast from '@/components/PageNoticeToast.vue'
import { useAuth } from '@/composables/useAuth'
import { usePageNotice } from '@/composables/usePageNotice'
import { IMAGE_FILE_ACCEPT, validateImageFile } from '@/utils/fileUpload'

const route = useRoute()
const router = useRouter()
const auth = useAuth()

const fileInput = ref(null)
const uploading = ref(false)
const submitting = ref(false)
const { notice, showNotice } = usePageNotice()

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

onMounted(async () => {
  window.scrollTo({ top: 0, left: 0, behavior: 'auto' })
  if (auth.hasToken()) {
    await auth.checkOnly()
  }
})

function goBack() {
  if (window.history.length > 1) {
    router.back()
    return
  }
  router.push({ name: 'Welcome' })
}

function triggerUpload() {
  if (fileInput.value) fileInput.value.value = ''
  fileInput.value?.click()
}

async function onFileSelected(event) {
  const file = event.target.files?.[0]
  if (!file) return

  const validationError = validateImageFile(file)
  if (validationError) {
    showNotice(validationError, 'error')
    resetFileInput()
    return
  }
  if (false && !file.type.startsWith('image/')) {
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

</script>

<style scoped>
.fb-toolbar-hint,
.fb-step,
.fb-hint,
.fb-submit-hint {
  font-size: 12px;
  color: var(--fc-text-muted);
  letter-spacing: 0.02em;
}

.fb-toolbar-hint {
  padding: 6px 12px;
  border-radius: 999px;
  border: 1px dashed var(--fc-rule-soft);
  background: rgba(255, 253, 249, 0.6);
}

.fb-step {
  padding: 5px 11px;
  border-radius: 999px;
  background: var(--fc-accent-veil);
  color: var(--fc-accent-strong);
  font-weight: 600;
  letter-spacing: 0.08em;
}

.fb-form-stack {
  margin-top: 22px;
}

.fb-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.fb-hint {
  display: block;
  margin: -2px 0 0;
  line-height: 1.6;
}

/* Willing-to-contact toggle */
.fb-toggle-field > span {
  color: var(--fc-text-muted);
}

.fb-toggle {
  width: 100%;
  min-height: 44px;
  padding: 6px 12px 6px 8px;
  border: 1px solid rgba(77, 52, 31, 0.1);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.92);
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
  transition:
    border-color var(--fc-duration-normal) var(--fc-ease-in-out),
    background var(--fc-duration-normal) var(--fc-ease-in-out);
}

.fb-toggle:hover {
  border-color: var(--fc-border-strong);
  background: #fff;
}

.fb-toggle i {
  position: relative;
  width: 40px;
  height: 22px;
  border-radius: 999px;
  background: rgba(77, 52, 31, 0.16);
  flex-shrink: 0;
  transition: background var(--fc-duration-normal) var(--fc-ease-in-out);
}

.fb-toggle i::after {
  content: '';
  position: absolute;
  top: 2px;
  left: 2px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 2px 4px rgba(33, 26, 20, 0.18);
  transition: transform var(--fc-duration-normal) var(--fc-ease-bounce);
}

.fb-toggle.is-on i {
  background: linear-gradient(90deg, var(--fc-accent) 0%, var(--fc-accent-soft) 100%);
}

.fb-toggle.is-on i::after {
  transform: translateX(18px);
}

.fb-toggle strong {
  font-size: 13.5px;
  font-weight: 500;
  color: var(--fc-text);
}

/* Upload panel */
.fb-upload {
  position: relative;
  margin-top: 4px;
  padding: 20px;
  min-height: 220px;
  border: 1px dashed rgba(77, 52, 31, 0.22);
  border-radius: 18px;
  background:
    repeating-linear-gradient(
      135deg,
      rgba(255, 253, 249, 0.8) 0 10px,
      rgba(255, 250, 243, 0.85) 10px 20px
    );
  transition:
    border-color var(--fc-duration-normal) var(--fc-ease-in-out),
    background var(--fc-duration-normal) var(--fc-ease-in-out);
}

.fb-upload:hover {
  border-color: var(--fc-accent-soft);
  background: rgba(255, 248, 237, 0.6);
}

.fb-upload.is-filled {
  border-style: solid;
  border-color: var(--fc-border);
  background: rgba(255, 253, 249, 0.96);
  padding: 16px;
}

.fb-upload-empty {
  min-height: 180px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  gap: 10px;
}

.fb-upload-icon {
  width: 44px;
  height: 44px;
  border-radius: 14px;
  background: var(--fc-accent-veil);
  color: var(--fc-accent-strong);
  display: flex;
  align-items: center;
  justify-content: center;
}

.fb-upload-icon svg {
  width: 22px;
  height: 22px;
}

.fb-upload-empty strong {
  font-family: var(--fc-font-display);
  font-size: 20px;
  color: var(--fc-text);
  font-weight: 600;
}

.fb-upload-empty p {
  margin: 0;
  max-width: 320px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--fc-text-sec);
}

.fb-shot {
  width: 100%;
  max-height: 280px;
  object-fit: cover;
  border-radius: 14px;
  border: 1px solid var(--fc-border);
}

.fb-upload-actions {
  margin-top: 14px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.fb-file-input { display: none; }

/* Submit row */
.fb-submit {
  margin-top: 4px;
  padding-top: 18px;
  border-top: 1px solid var(--fc-rule-soft);
  flex-direction: row;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}

.fb-submit-btn {
  min-width: 168px;
}

.fb-submit-hint {
  line-height: 1.6;
}

@media (max-width: 720px) {
  .fb-toolbar-hint { display: none; }
  .fb-form { grid-template-columns: 1fr; }
  .fb-submit { flex-direction: column; align-items: stretch; }
  .fb-submit-btn { width: 100%; }
}
</style>
