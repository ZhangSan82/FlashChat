<template>
  <Teleport to="body">
    <transition name="sheet">
      <div v-if="visible" class="sheet-overlay" @click.self="$emit('close')">
        <div class="sheet-card">
          <div class="sheet-head">
            <div>
              <div class="fc-kicker">Room Setup</div>
              <h3 class="sheet-title">创建房间</h3>
            </div>
            <button class="sheet-close" type="button" @click="$emit('close')">×</button>
          </div>

          <div class="sheet-body">
            <label class="sheet-label">房间名称</label>
            <input
              v-model="form.title"
              class="sheet-input"
              type="text"
              maxlength="30"
              placeholder="给这次对话起一个名字"
            />

            <label class="sheet-label">时长与积分消耗</label>
            <div v-if="pricingLoading" class="sheet-loading">正在获取定价...</div>
            <div v-else class="sheet-pricing">
              <button
                v-for="option in pricingOptions"
                :key="option.name"
                type="button"
                class="sheet-price"
                :class="{ active: form.duration === option.name }"
                @click="form.duration = option.name"
              >
                <span class="sheet-price-name">{{ option.desc }}</span>
                <span class="sheet-price-meta">{{ option.minutes }} 分钟</span>
                <span class="sheet-price-cost" :class="{ free: option.cost === 0 }">
                  {{ option.cost === 0 ? '免费' : `${option.cost} 积分` }}
                </span>
              </button>
            </div>

            <div v-if="selectedPricing" class="sheet-summary">
              <div>
                <div class="sheet-summary-label">当前选择</div>
                <div class="sheet-summary-value">{{ selectedPricing.desc }}</div>
              </div>
              <div class="sheet-summary-cost" :class="{ free: selectedPricing.cost === 0 }">
                {{ selectedPricing.cost === 0 ? '免费创建' : `消耗 ${selectedPricing.cost} 积分` }}
              </div>
            </div>

            <label class="sheet-label">房间头像</label>
            <div class="sheet-avatar-row">
              <button class="sheet-avatar-picker" type="button" @click="triggerAvatarUpload">
                <img v-if="roomAvatarPreview" class="sheet-avatar-img" :src="roomAvatarPreview" alt="room avatar" />
                <span v-else class="sheet-avatar-fallback">{{ (form.title || '?').slice(0, 1).toUpperCase() }}</span>
              </button>
              <div class="sheet-avatar-actions">
                <button class="sheet-avatar-btn" type="button" :disabled="avatarUploading" @click="triggerAvatarUpload">
                  {{ avatarUploading ? '上传中...' : (form.avatarUrl ? '更换头像' : '上传头像') }}
                </button>
                <button
                  v-if="form.avatarUrl"
                  class="sheet-avatar-btn ghost"
                  type="button"
                  :disabled="avatarUploading"
                  @click="clearAvatar"
                >
                  清除头像
                </button>
                <div class="sheet-avatar-hint">建议 1:1 图片，不超过 5MB</div>
                <div v-if="avatarError" class="sheet-avatar-error">{{ avatarError }}</div>
              </div>
              <input ref="fileInput" type="file" accept="image/*" class="sheet-file-hidden" @change="onAvatarSelected" />
            </div>

            <div class="sheet-grid">
              <div>
                <label class="sheet-label">人数上限</label>
                <input v-model.number="form.maxMembers" class="sheet-input" type="number" min="2" max="200" />
              </div>
              <div>
                <label class="sheet-label">房间类型</label>
                <div class="sheet-toggle">
                  <button
                    type="button"
                    class="sheet-toggle-btn"
                    :class="{ active: form.isPublic === 0 }"
                    @click="form.isPublic = 0"
                  >
                    私密
                  </button>
                  <button
                    type="button"
                    class="sheet-toggle-btn"
                    :class="{ active: form.isPublic === 1 }"
                    @click="form.isPublic = 1"
                  >
                    公开
                  </button>
                </div>
              </div>
            </div>

            <p class="sheet-hint">
              公开房间会出现在公开大厅；私密房间只能通过房间 ID 或分享链接进入。
            </p>
          </div>

          <div class="sheet-actions">
            <button class="sheet-btn sheet-btn-ghost" type="button" @click="$emit('close')">取消</button>
            <button class="sheet-btn sheet-btn-primary" type="button" :disabled="!canSubmit" @click="submit">
              创建房间
            </button>
          </div>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { getRoomPricing } from '@/api/room'
import { uploadFile } from '@/api/file'

const props = defineProps({
  visible: { type: Boolean, default: false }
})

const emit = defineEmits(['create', 'close'])

const fallbackPricing = [
  { name: 'MIN_10', minutes: 10, desc: '10 分钟', cost: 0 },
  { name: 'MIN_30', minutes: 30, desc: '30 分钟', cost: 10 },
  { name: 'HOUR_1', minutes: 60, desc: '1 小时', cost: 20 },
  { name: 'HOUR_2', minutes: 120, desc: '2 小时', cost: 30 },
  { name: 'HOUR_6', minutes: 360, desc: '6 小时', cost: 50 },
  { name: 'HOUR_12', minutes: 720, desc: '12 小时', cost: 80 },
  { name: 'HOUR_24', minutes: 1440, desc: '24 小时', cost: 100 },
  { name: 'DAY_3', minutes: 4320, desc: '3 天', cost: 200 },
  { name: 'DAY_7', minutes: 10080, desc: '7 天', cost: 400 }
]

const pricingLoading = ref(false)
const pricingOptions = ref([...fallbackPricing])
const avatarUploading = ref(false)
const avatarError = ref('')
const fileInput = ref(null)

const form = reactive({
  title: '',
  duration: 'MIN_30',
  maxMembers: 50,
  isPublic: 0,
  avatarUrl: ''
})

const canSubmit = computed(() => form.title.trim().length > 0 && form.maxMembers >= 2 && form.maxMembers <= 200)
const selectedPricing = computed(() =>
  pricingOptions.value.find(item => item.name === form.duration) || pricingOptions.value[0] || null
)
const roomAvatarPreview = computed(() => form.avatarUrl || '')

watch(
  () => props.visible,
  async visible => {
    if (!visible) return
    await loadPricing()
  }
)

async function loadPricing() {
  pricingLoading.value = true
  try {
    const list = await getRoomPricing()
    if (Array.isArray(list) && list.length) {
      pricingOptions.value = list
      if (!pricingOptions.value.some(item => item.name === form.duration)) {
        form.duration = pricingOptions.value[0].name
      }
    } else {
      pricingOptions.value = [...fallbackPricing]
    }
  } catch {
    pricingOptions.value = [...fallbackPricing]
  } finally {
    pricingLoading.value = false
  }
}

function submit() {
  if (!canSubmit.value) return
  emit('create', {
    title: form.title.trim(),
    duration: form.duration,
    maxMembers: Number(form.maxMembers),
    isPublic: form.isPublic,
    avatarUrl: form.avatarUrl || ''
  })
  resetForm()
}

function resetForm() {
  form.title = ''
  form.duration = 'MIN_30'
  form.maxMembers = 50
  form.isPublic = 0
  form.avatarUrl = ''
  avatarError.value = ''
}

function triggerAvatarUpload() {
  if (avatarUploading.value) return
  fileInput.value?.click()
}

async function onAvatarSelected(event) {
  const file = event.target?.files?.[0]
  if (!file) return
  avatarError.value = ''

  if (!file.type?.startsWith('image/')) {
    avatarError.value = '请选择图片文件'
    if (fileInput.value) fileInput.value.value = ''
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    avatarError.value = '图片大小不能超过 5MB'
    if (fileInput.value) fileInput.value.value = ''
    return
  }

  avatarUploading.value = true
  try {
    const result = await uploadFile(file)
    form.avatarUrl = result?.url || ''
  } catch (error) {
    avatarError.value = error?.message || '上传失败'
  } finally {
    avatarUploading.value = false
    if (fileInput.value) fileInput.value.value = ''
  }
}

function clearAvatar() {
  form.avatarUrl = ''
  avatarError.value = ''
}
</script>

<style scoped>
.sheet-overlay {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: var(--fc-backdrop);
  z-index: 9600;
}

.sheet-card {
  width: min(640px, 100%);
  max-height: min(92vh, 860px);
  overflow: auto;
  border: 1px solid var(--fc-border);
  border-radius: 30px;
  background: var(--fc-surface);
  box-shadow: var(--fc-shadow-panel);
}

.sheet-head,
.sheet-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.sheet-head {
  padding: 26px 28px 18px;
}

/* .sheet-kicker → replaced by global .fc-kicker */
.sheet-label,
.sheet-summary-label {
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.sheet-title {
  margin: 8px 0 0;
  font-family: var(--fc-font-display);
  font-size: 20px;
  line-height: 1.15;
  color: var(--fc-text);
}

.sheet-close {
  width: 38px;
  height: 38px;
  border: 1px solid var(--fc-border);
  border-radius: 50%;
  background: var(--fc-surface);
  color: var(--fc-text);
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
}

.sheet-body {
  padding: 0 28px 22px;
}

.sheet-input {
  width: 100%;
  padding: 14px 16px;
  border: 1px solid var(--fc-border);
  border-radius: 18px;
  background: var(--fc-bg);
  color: var(--fc-text);
  font-family: var(--fc-font);
  font-size: 15px;
  outline: none;
}

.sheet-input:focus {
  border-color: var(--fc-border-strong);
}

.sheet-label {
  display: block;
  margin: 18px 0 10px;
}

.sheet-avatar-row {
  display: flex;
  gap: 14px;
  align-items: flex-start;
}

.sheet-avatar-picker {
  width: 74px;
  height: 74px;
  border: 1px solid var(--fc-border);
  border-radius: 20px;
  background: var(--fc-surface);
  padding: 0;
  overflow: hidden;
  cursor: pointer;
  flex-shrink: 0;
}

.sheet-avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.sheet-avatar-fallback {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--fc-font-display);
  font-size: 22px;
  color: var(--fc-text);
  background: var(--fc-accent);
}

.sheet-avatar-actions {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.sheet-avatar-btn {
  padding: 9px 12px;
  border: 1px solid var(--fc-border);
  border-radius: 999px;
  background: var(--fc-surface);
  color: var(--fc-text);
  font-family: var(--fc-font);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.sheet-avatar-btn.ghost {
  color: var(--fc-text-sec);
}

.sheet-avatar-btn:disabled {
  opacity: 0.56;
  cursor: not-allowed;
}

.sheet-avatar-hint {
  width: 100%;
  font-family: var(--fc-font);
  font-size: 12px;
  color: var(--fc-text-muted);
}

.sheet-avatar-error {
  width: 100%;
  font-family: var(--fc-font);
  font-size: 12px;
  color: #a74f35;
}

.sheet-file-hidden {
  display: none;
}

.sheet-loading {
  padding: 18px;
  border-radius: 18px;
  background: var(--fc-surface);
  color: var(--fc-text-sec);
  font-family: var(--fc-font);
}

.sheet-pricing {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.sheet-price {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 16px;
  text-align: left;
  border: 1px solid var(--fc-border);
  border-radius: 22px;
  background: var(--fc-surface);
  cursor: pointer;
  transition: border-color var(--fc-duration-fast) var(--fc-ease-in-out), background-color var(--fc-duration-fast) var(--fc-ease-in-out), box-shadow var(--fc-duration-fast) var(--fc-ease-in-out);
}

.sheet-price:hover {
  border-color: var(--fc-border-strong);
}

.sheet-price.active {
  border-color: var(--fc-selected-border);
  background: var(--fc-selected-bg);
  box-shadow: var(--fc-selected-shadow);
}

.sheet-price.active .sheet-price-name {
  color: var(--fc-selected-text);
}

.sheet-price-name {
  font-family: var(--fc-font);
  font-size: 16px;
  font-weight: 600;
  color: var(--fc-text);
}

.sheet-price-meta {
  font-family: var(--fc-font);
  font-size: 12px;
  color: var(--fc-text-sec);
}

.sheet-price-cost,
.sheet-summary-cost {
  font-family: var(--fc-font);
  font-size: 13px;
  font-weight: 600;
  color: var(--fc-accent-strong);
}

.sheet-price-cost.free,
.sheet-summary-cost.free {
  color: var(--fc-success);
}

.sheet-summary {
  margin-top: 14px;
  padding: 16px 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  border: 1px solid var(--fc-border);
  border-radius: 22px;
  background: var(--fc-surface);
}

.sheet-summary-value {
  margin-top: 6px;
  font-family: var(--fc-font);
  font-size: 15px;
  font-weight: 600;
  color: var(--fc-text);
}

.sheet-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.sheet-toggle {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
}

.sheet-toggle-btn {
  padding: 14px 12px;
  border: 1px solid var(--fc-border);
  border-radius: 18px;
  background: var(--fc-surface);
  font-family: var(--fc-font);
  font-size: 14px;
  font-weight: 600;
  color: var(--fc-text-sec);
  cursor: pointer;
  transition: border-color var(--fc-duration-fast) var(--fc-ease-in-out), background-color var(--fc-duration-fast) var(--fc-ease-in-out), box-shadow var(--fc-duration-fast) var(--fc-ease-in-out), color var(--fc-duration-fast) var(--fc-ease-in-out);
}

.sheet-toggle-btn.active {
  background: var(--fc-selected-bg);
  border-color: var(--fc-selected-border);
  color: var(--fc-selected-text);
  box-shadow: var(--fc-selected-shadow);
}

.sheet-hint {
  margin: 16px 2px 0;
  font-family: var(--fc-font);
  font-size: 13px;
  line-height: 1.6;
  color: var(--fc-text-sec);
}

.sheet-actions {
  padding: 0 28px 28px;
  gap: 12px;
  justify-content: flex-end;
}

.sheet-btn {
  min-width: 132px;
  padding: 14px 18px;
  border: 1px solid var(--fc-border);
  border-radius: 18px;
  font-family: var(--fc-font);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}

.sheet-btn-ghost {
  background: var(--fc-surface);
  color: var(--fc-text-sec);
}

.sheet-btn-primary {
  background: var(--fc-accent);
  color: #fffaf3;
}

.sheet-btn:disabled {
  opacity: .45;
  cursor: not-allowed;
  box-shadow: none;
}

@media (max-width: 720px) {
  .sheet-card {
    border-radius: 24px;
  }

  .sheet-head,
  .sheet-body,
  .sheet-actions {
    padding-left: 18px;
    padding-right: 18px;
  }

  .sheet-pricing,
  .sheet-grid {
    grid-template-columns: 1fr;
  }

  .sheet-avatar-row {
    flex-direction: column;
  }

  .sheet-avatar-actions {
    width: 100%;
  }

  .sheet-summary {
    flex-direction: column;
    align-items: flex-start;
  }

  .sheet-actions {
    flex-direction: column-reverse;
  }

  .sheet-btn {
    width: 100%;
  }
}
</style>
