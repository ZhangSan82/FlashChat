<template>
  <Teleport to="body">
    <transition name="extend">
      <div v-if="visible" class="extend-overlay" @click.self="$emit('close')">
        <div class="extend-card">
          <div class="extend-head">
            <div>
              <div class="extend-kicker">Host Action</div>
              <h3 class="extend-title">房间延期</h3>
            </div>
            <button class="extend-close" type="button" @click="$emit('close')">×</button>
          </div>

          <div class="extend-body">
            <div class="extend-room">
              <div class="extend-room-name">{{ room?.title || '未命名房间' }}</div>
              <div class="extend-room-meta">
                当前到期：
                <strong>{{ currentExpireText }}</strong>
              </div>
            </div>

            <div v-if="loading" class="extend-loading">正在读取延期定价...</div>
            <template v-else>
              <div class="extend-balance">
                <span>当前积分</span>
                <strong>{{ creditBalanceDisplay }}</strong>
              </div>

              <div class="extend-grid">
                <button
                  v-for="option in pricingOptions"
                  :key="option.name"
                  type="button"
                  class="extend-option"
                  :class="{ active: selectedName === option.name }"
                  @click="selectedName = option.name"
                >
                  <span class="extend-option-name">{{ option.desc }}</span>
                  <span class="extend-option-minutes">{{ option.minutes }} 分钟</span>
                  <span class="extend-option-cost" :class="{ free: option.cost === 0 }">
                    {{ option.cost === 0 ? '免费' : `${option.cost} 积分` }}
                  </span>
                </button>
              </div>

              <div v-if="selectedOption" class="extend-summary">
                <div>
                  <div class="extend-summary-label">延期后到期时间</div>
                  <div class="extend-summary-time">{{ nextExpireText }}</div>
                </div>
                <div class="extend-summary-cost" :class="{ free: selectedOption.cost === 0, warn: insufficientBalance }">
                  {{ insufficientBalance ? '积分不足' : (selectedOption.cost === 0 ? '无需积分' : `消耗 ${selectedOption.cost} 积分`) }}
                </div>
              </div>

              <p class="extend-hint" v-if="insufficientBalance">
                当前积分不足，提交后后端会拦截。你可以先去积分中心签到或查看积分流水。
              </p>
            </template>
          </div>

          <div class="extend-actions">
            <button class="extend-btn extend-btn-ghost" type="button" @click="$emit('close')">取消</button>
            <button class="extend-btn extend-btn-primary" type="button" :disabled="!selectedOption" @click="submit">
              确认延期
            </button>
          </div>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { getRoomPricing } from '@/api/room'
import { getCreditBalance } from '@/api/account'

const props = defineProps({
  visible: Boolean,
  room: { type: Object, default: null }
})

const emit = defineEmits(['close', 'extend'])

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

const loading = ref(false)
const pricingOptions = ref([...fallbackPricing])
const selectedName = ref('MIN_30')
const creditBalance = ref(null)

const selectedOption = computed(() =>
  pricingOptions.value.find(item => item.name === selectedName.value) || pricingOptions.value[0] || null
)

const creditBalanceDisplay = computed(() =>
  creditBalance.value == null ? '—' : `${creditBalance.value}`
)

const insufficientBalance = computed(() =>
  creditBalance.value != null && selectedOption.value && selectedOption.value.cost > creditBalance.value
)

const baseExpireDate = computed(() => {
  const expire = props.room?.expireTime ? new Date(props.room.expireTime) : null
  if (!expire || Number.isNaN(expire.getTime())) return new Date()
  const now = new Date()
  return expire.getTime() > now.getTime() ? expire : now
})

const currentExpireText = computed(() => formatDateTime(props.room?.expireTime))
const nextExpireText = computed(() => {
  if (!selectedOption.value) return '—'
  const next = new Date(baseExpireDate.value.getTime() + selectedOption.value.minutes * 60 * 1000)
  return formatDateTime(next)
})

watch(
  () => props.visible,
  async (visible) => {
    if (!visible) return
    await loadMeta()
  }
)

async function loadMeta() {
  loading.value = true
  try {
    const [pricing, balance] = await Promise.allSettled([
      getRoomPricing(),
      getCreditBalance()
    ])

    pricingOptions.value = pricing.status === 'fulfilled' && Array.isArray(pricing.value) && pricing.value.length
      ? pricing.value
      : [...fallbackPricing]

    if (!pricingOptions.value.some(item => item.name === selectedName.value)) {
      selectedName.value = pricingOptions.value[0]?.name || 'MIN_30'
    }

    creditBalance.value = balance.status === 'fulfilled' ? balance.value : null
  } finally {
    loading.value = false
  }
}

function submit() {
  if (!props.room?.roomId || !selectedOption.value) return
  emit('extend', {
    roomId: props.room.roomId,
    duration: selectedOption.value.name,
    option: selectedOption.value
  })
}

function formatDateTime(value) {
  if (!value) return '未设置'
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return '未设置'
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  })
}
</script>

<style scoped>
.extend-overlay {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: var(--fc-backdrop);
  backdrop-filter: blur(18px);
  z-index: 9700;
}

.extend-card {
  width: min(620px, 100%);
  border: 1px solid rgba(77, 52, 31, 0.10);
  border-radius: 28px;
  background: linear-gradient(180deg, rgba(255, 250, 243, 0.98), rgba(247, 239, 228, 0.98));
  box-shadow: 0 30px 60px rgba(61, 40, 22, 0.22);
}

.extend-head,
.extend-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.extend-head {
  padding: 24px 26px 18px;
}

.extend-kicker,
.extend-summary-label {
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.extend-title {
  margin: 8px 0 0;
  font-family: var(--fc-font);
  font-size: 26px;
  color: var(--fc-text);
}

.extend-close {
  width: 40px;
  height: 40px;
  border: 1px solid var(--fc-border);
  border-radius: 50%;
  background: rgba(255, 250, 243, 0.82);
  color: var(--fc-text);
  font-size: 24px;
  cursor: pointer;
}

.extend-body {
  padding: 0 26px 24px;
}

.extend-room,
.extend-balance,
.extend-summary {
  padding: 16px 18px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 22px;
  background: rgba(255, 250, 243, 0.76);
}

.extend-room-name,
.extend-summary-time {
  font-family: var(--fc-font);
  font-size: 20px;
  font-weight: 700;
  color: var(--fc-text);
}

.extend-room-meta,
.extend-hint,
.extend-loading {
  margin-top: 8px;
  font-family: var(--fc-font);
  font-size: 13px;
  line-height: 1.6;
  color: var(--fc-text-sec);
}

.extend-loading {
  padding: 16px 4px;
}

.extend-balance {
  margin-top: 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-family: var(--fc-font);
  color: var(--fc-text-sec);
}

.extend-balance strong {
  font-size: 24px;
  color: var(--fc-accent-strong);
}

.extend-grid {
  margin-top: 16px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.extend-option {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 16px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 22px;
  background: rgba(255, 250, 243, 0.78);
  text-align: left;
  cursor: pointer;
}

.extend-option.active {
  border-color: rgba(140, 90, 43, 0.24);
  background: linear-gradient(180deg, rgba(251, 245, 236, 0.96), rgba(241, 225, 203, 0.96));
  box-shadow: 0 16px 26px rgba(140, 90, 43, 0.14);
}

.extend-option-name {
  font-family: var(--fc-font);
  font-size: 16px;
  font-weight: 700;
  color: var(--fc-text);
}

.extend-option-minutes {
  font-family: var(--fc-font);
  font-size: 12px;
  color: var(--fc-text-sec);
}

.extend-option-cost,
.extend-summary-cost {
  font-family: var(--fc-font);
  font-size: 13px;
  font-weight: 700;
  color: var(--fc-accent-strong);
}

.extend-option-cost.free,
.extend-summary-cost.free {
  color: var(--fc-success);
}

.extend-summary {
  margin-top: 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.extend-summary-cost.warn {
  color: var(--fc-danger);
}

.extend-hint {
  color: var(--fc-danger);
}

.extend-actions {
  padding: 0 26px 26px;
  gap: 12px;
  justify-content: flex-end;
}

.extend-btn {
  min-width: 132px;
  padding: 14px 18px;
  border: 1px solid rgba(77, 52, 31, 0.10);
  border-radius: 18px;
  font-family: var(--fc-font);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}

.extend-btn-ghost {
  background: rgba(255, 250, 243, 0.78);
  color: var(--fc-text-sec);
}

.extend-btn-primary {
  background: linear-gradient(135deg, #b68450 0%, #8c5a2b 100%);
  color: #fffaf3;
  box-shadow: 0 16px 28px rgba(140, 90, 43, 0.22);
}

.extend-btn:disabled {
  opacity: .45;
  cursor: not-allowed;
  box-shadow: none;
}

@media (max-width: 720px) {
  .extend-grid {
    grid-template-columns: 1fr;
  }

  .extend-summary,
  .extend-actions {
    flex-direction: column;
    align-items: stretch;
  }

  .extend-btn {
    width: 100%;
  }
}
</style>
