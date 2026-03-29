<template>
  <Teleport to="body">
    <transition name="sheet">
      <div v-if="visible" class="sheet-overlay" @click.self="$emit('close')">
        <div class="sheet-card">
          <div class="sheet-head">
            <div>
              <div class="sheet-kicker">Room Setup</div>
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

const form = reactive({
  title: '',
  duration: 'MIN_30',
  maxMembers: 50,
  isPublic: 0
})

const canSubmit = computed(() => form.title.trim().length > 0 && form.maxMembers >= 2 && form.maxMembers <= 200)
const selectedPricing = computed(() =>
  pricingOptions.value.find(item => item.name === form.duration) || pricingOptions.value[0] || null
)

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
    isPublic: form.isPublic
  })
  form.title = ''
  form.duration = 'MIN_30'
  form.maxMembers = 50
  form.isPublic = 0
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
  backdrop-filter: blur(18px);
  z-index: 9600;
}

.sheet-card {
  width: min(640px, 100%);
  max-height: min(92vh, 860px);
  overflow: auto;
  border: 1px solid rgba(77, 52, 31, 0.10);
  border-radius: 30px;
  background:
    radial-gradient(circle at top right, rgba(221, 193, 163, 0.24), transparent 30%),
    linear-gradient(180deg, rgba(255, 250, 243, 0.98), rgba(247, 239, 228, 0.98));
  box-shadow: 0 34px 60px rgba(61, 40, 22, 0.22);
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

.sheet-kicker,
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
  font-family: var(--fc-font);
  font-size: 28px;
  line-height: 1.05;
  color: var(--fc-text);
}

.sheet-close {
  width: 42px;
  height: 42px;
  border: 1px solid var(--fc-border);
  border-radius: 50%;
  background: rgba(255, 250, 243, 0.82);
  color: var(--fc-text);
  font-size: 26px;
  line-height: 1;
  cursor: pointer;
}

.sheet-body {
  padding: 0 28px 22px;
}

.sheet-input {
  width: 100%;
  padding: 14px 16px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 18px;
  background: rgba(243, 231, 215, 0.9);
  color: var(--fc-text);
  font-family: var(--fc-font);
  font-size: 15px;
  outline: none;
}

.sheet-input:focus {
  border-color: rgba(140, 90, 43, 0.24);
  box-shadow: 0 0 0 4px rgba(173, 122, 68, 0.08);
}

.sheet-label {
  display: block;
  margin: 18px 0 10px;
}

.sheet-loading {
  padding: 18px;
  border-radius: 18px;
  background: rgba(255, 250, 243, 0.72);
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
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 22px;
  background: rgba(255, 250, 243, 0.76);
  cursor: pointer;
  transition: transform .18s ease, box-shadow .18s ease, border-color .18s ease;
}

.sheet-price:hover {
  transform: translateY(-1px);
  box-shadow: var(--fc-shadow-soft);
}

.sheet-price.active {
  border-color: rgba(140, 90, 43, 0.24);
  background: linear-gradient(180deg, rgba(251, 245, 236, 0.96), rgba(241, 225, 203, 0.96));
  box-shadow: 0 16px 26px rgba(140, 90, 43, 0.14);
}

.sheet-price-name {
  font-family: var(--fc-font);
  font-size: 16px;
  font-weight: 700;
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
  font-weight: 700;
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
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 22px;
  background: rgba(255, 250, 243, 0.78);
}

.sheet-summary-value {
  margin-top: 6px;
  font-family: var(--fc-font);
  font-size: 18px;
  font-weight: 700;
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
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 18px;
  background: rgba(255, 250, 243, 0.76);
  font-family: var(--fc-font);
  font-size: 14px;
  font-weight: 600;
  color: var(--fc-text-sec);
  cursor: pointer;
}

.sheet-toggle-btn.active {
  background: linear-gradient(135deg, #b68450 0%, #8c5a2b 100%);
  border-color: transparent;
  color: #fffaf3;
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
  border: 1px solid rgba(77, 52, 31, 0.10);
  border-radius: 18px;
  font-family: var(--fc-font);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}

.sheet-btn-ghost {
  background: rgba(255, 250, 243, 0.78);
  color: var(--fc-text-sec);
}

.sheet-btn-primary {
  background: linear-gradient(135deg, #b68450 0%, #8c5a2b 100%);
  color: #fffaf3;
  box-shadow: 0 16px 28px rgba(140, 90, 43, 0.22);
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
