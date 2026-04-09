<template>
  <Teleport to="body">
    <transition name="resize">
      <div v-if="visible" class="resize-overlay" @click.self="$emit('close')">
        <div class="resize-card">
          <div class="resize-head">
            <div>
              <div class="resize-kicker">Host Action</div>
              <h3 class="resize-title">房间扩容</h3>
            </div>
            <button class="resize-close" type="button" @click="$emit('close')">×</button>
          </div>

          <div class="resize-body">
            <div class="resize-stats">
              <div class="resize-stat">
                <span class="resize-stat-label">当前成员</span>
                <strong>{{ memberCount }}</strong>
              </div>
              <div class="resize-stat">
                <span class="resize-stat-label">当前上限</span>
                <strong>{{ currentMax }}</strong>
              </div>
              <div class="resize-stat accent">
                <span class="resize-stat-label">新上限</span>
                <strong>{{ form.newMaxMembers }}</strong>
              </div>
            </div>

            <div class="resize-presets">
              <button
                v-for="size in presets"
                :key="size"
                type="button"
                class="resize-preset"
                :class="{ active: form.newMaxMembers === size }"
                @click="form.newMaxMembers = size"
              >
                {{ size }} 人
              </button>
            </div>

            <label class="resize-label">自定义人数上限</label>
            <input
              v-model.number="form.newMaxMembers"
              class="resize-input"
              type="number"
              :min="Math.max(currentMax + 1, memberCount + 1)"
              max="200"
            />

            <p class="resize-hint">
              只能扩大房间上限，不能缩小。建议至少比当前成员数多预留 2-5 个位置。
            </p>
          </div>

          <div class="resize-actions">
            <button class="resize-btn resize-btn-ghost" type="button" @click="$emit('close')">取消</button>
            <button class="resize-btn resize-btn-primary" type="button" :disabled="!canSubmit" @click="submit">
              确认扩容
            </button>
          </div>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<script setup>
import { computed, reactive, watch } from 'vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  room: { type: Object, default: null }
})

const emit = defineEmits(['close', 'resize'])

const form = reactive({
  newMaxMembers: 60
})

const memberCount = computed(() => props.room?.memberCount || 0)
const currentMax = computed(() => props.room?.maxMembers || 50)
const presets = computed(() => {
  const base = currentMax.value
  const options = [base + 5, base + 10, base + 25, base + 50, 100, 150, 200]
  return [...new Set(options.filter(size => size > base && size >= memberCount.value + 1 && size <= 200))]
})

const canSubmit = computed(() =>
  form.newMaxMembers > currentMax.value &&
  form.newMaxMembers >= memberCount.value + 1 &&
  form.newMaxMembers <= 200
)

watch(
  () => props.visible,
  visible => {
    if (!visible) return
    form.newMaxMembers = Math.min(
      200,
      Math.max(currentMax.value + 10, memberCount.value + 2)
    )
  }
)

function submit() {
  if (!props.room?.roomId || !canSubmit.value) return
  emit('resize', {
    roomId: props.room.roomId,
    newMaxMembers: Number(form.newMaxMembers)
  })
}
</script>

<style scoped>
.resize-overlay {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: var(--fc-backdrop);
  z-index: 9700;
  overflow-y: auto;
  overscroll-behavior: contain;
  -webkit-overflow-scrolling: touch;
}

.resize-card {
  width: min(560px, 100%);
  max-height: min(820px, calc(100dvh - 40px));
  border: 1px solid var(--fc-border);
  border-radius: 28px;
  background: var(--fc-surface);
  box-shadow: var(--fc-shadow-panel);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  margin: auto;
}

.resize-head,
.resize-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.resize-head {
  padding: 24px 26px 18px;
}

.resize-kicker,
.resize-stat-label,
.resize-label {
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.resize-title {
  margin: 8px 0 0;
  font-family: var(--fc-font-display);
  font-size: 20px;
  color: var(--fc-text);
}

.resize-close {
  width: 40px;
  height: 40px;
  border: 1px solid var(--fc-border);
  border-radius: 50%;
  background: var(--fc-surface);
  color: var(--fc-text);
  font-size: 20px;
  cursor: pointer;
}

.resize-body {
  padding: 0 26px 24px;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  -webkit-overflow-scrolling: touch;
}

.resize-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.resize-stat {
  padding: 16px;
  border: 1px solid var(--fc-border);
  border-radius: 22px;
  background: var(--fc-surface);
}

.resize-stat.accent {
  background: var(--fc-bg);
}

.resize-stat strong {
  display: block;
  margin-top: 10px;
  font-family: var(--fc-font);
  font-size: 22px;
  color: var(--fc-text);
}

.resize-presets {
  margin-top: 18px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.resize-preset {
  padding: 11px 16px;
  border: 1px solid var(--fc-border);
  border-radius: 999px;
  background: var(--fc-surface);
  font-family: var(--fc-font);
  font-size: 13px;
  font-weight: 600;
  color: var(--fc-text-sec);
  cursor: pointer;
  transition: border-color .18s ease, background-color .18s ease, box-shadow .18s ease, color .18s ease;
}

.resize-preset.active {
  background: var(--fc-selected-bg);
  border-color: var(--fc-selected-border);
  color: var(--fc-selected-text);
  box-shadow: var(--fc-selected-shadow);
}

.resize-label {
  display: block;
  margin: 18px 0 10px;
}

.resize-input {
  width: 100%;
  padding: 14px 16px;
  border: 1px solid var(--fc-border);
  border-radius: 18px;
  background: var(--fc-bg);
  color: var(--fc-text);
  font-family: var(--fc-font);
  font-size: 16px;
}

.resize-hint {
  margin-top: 12px;
  font-family: var(--fc-font);
  font-size: 13px;
  line-height: 1.6;
  color: var(--fc-text-sec);
}

.resize-actions {
  padding: 0 26px 26px;
  gap: 12px;
  justify-content: flex-end;
}

.resize-btn {
  min-width: 132px;
  padding: 14px 18px;
  border: 1px solid var(--fc-border);
  border-radius: 18px;
  font-family: var(--fc-font);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}

.resize-btn-ghost {
  background: var(--fc-surface);
  color: var(--fc-text-sec);
}

.resize-btn-primary {
  background: var(--fc-accent);
  color: #fffaf3;
}

.resize-btn:disabled {
  opacity: .45;
  cursor: not-allowed;
  box-shadow: none;
}

@media (max-width: 720px) {
  .resize-overlay {
    padding: 12px;
  }

  .resize-card {
    width: 100%;
    max-height: calc(100dvh - 24px);
    border-radius: 24px;
  }

  .resize-head {
    padding: 20px 20px 16px;
  }

  .resize-body {
    padding: 0 20px 20px;
  }

  .resize-actions {
    padding: 0 20px calc(20px + env(safe-area-inset-bottom));
  }

  .resize-stats {
    grid-template-columns: 1fr;
  }

  .resize-actions {
    flex-direction: column-reverse;
  }

  .resize-btn {
    width: 100%;
  }
}
</style>
