<template>
  <Teleport to="body">
    <transition name="acd">
      <div v-if="visible" class="acd-mask" @click.self="handleClose">
        <div class="acd-card" :class="`is-${toneClass}`">
          <div class="acd-shell">
            <div class="acd-badge">{{ badgeLabel }}</div>

            <div class="acd-head">
              <div class="acd-icon">{{ toneIcon }}</div>
              <div class="acd-copy">
                <div class="acd-kicker">{{ kicker }}</div>
                <div class="acd-title">{{ title }}</div>
                <p class="acd-text">{{ text }}</p>
              </div>
            </div>

            <div v-if="facts.length" class="acd-facts">
              <div v-for="fact in facts" :key="fact" class="acd-fact">{{ fact }}</div>
            </div>

            <div v-if="preview" class="acd-preview">
              <span v-if="previewLabel" class="acd-preview-label">{{ previewLabel }}</span>
              <div class="acd-preview-value">{{ preview }}</div>
            </div>

            <div class="acd-actions">
              <button class="acd-btn acd-btn-ghost" type="button" :disabled="pending" @click="handleClose">
                {{ secondaryText }}
              </button>
              <button class="acd-btn acd-btn-primary" :class="`is-${toneClass}`" type="button" :disabled="pending" @click="$emit('confirm')">
                {{ pending ? pendingText : primaryText }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  tone: { type: String, default: 'danger' },
  kicker: { type: String, default: 'Action' },
  title: { type: String, default: '' },
  text: { type: String, default: '' },
  facts: { type: Array, default: () => [] },
  preview: { type: String, default: '' },
  previewLabel: { type: String, default: '' },
  primaryText: { type: String, default: '确认' },
  pendingText: { type: String, default: '处理中...' },
  secondaryText: { type: String, default: '取消' },
  pending: { type: Boolean, default: false }
})

const emit = defineEmits(['close', 'confirm'])

const toneClass = computed(() => {
  if (['danger', 'warning', 'accent', 'muted'].includes(props.tone)) return props.tone
  return 'danger'
})

const toneIcon = computed(() => {
  if (toneClass.value === 'warning') return '!'
  if (toneClass.value === 'accent') return '+'
  if (toneClass.value === 'muted') return '~'
  return 'x'
})

const badgeLabel = computed(() => {
  if (toneClass.value === 'warning') return '谨慎操作'
  if (toneClass.value === 'accent') return '状态调整'
  if (toneClass.value === 'muted') return '仅当前会话'
  return '影响当前房间'
})

function handleClose() {
  if (props.pending) return
  emit('close')
}
</script>

<style scoped>
.acd-mask {
  position: fixed;
  inset: 0;
  z-index: 10020;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: var(--fc-backdrop);
}

.acd-card {
  width: min(520px, 100%);
  border-radius: 30px;
  border: 1px solid var(--fc-border-strong);
  background: var(--fc-panel-elevated);
  box-shadow: var(--fc-shadow-panel);
  position: relative;
  overflow: hidden;
}

/* ::before overlay removed for fusion design */

.acd-shell {
  position: relative;
  z-index: 1;
  padding: 24px;
}

.acd-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 8px 12px;
  border-radius: 999px;
  border: 1px solid var(--fc-border);
  background: var(--fc-surface);
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.acd-head {
  margin-top: 16px;
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 16px;
  align-items: start;
}

.acd-icon {
  width: 48px;
  height: 48px;
  border-radius: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--fc-font-display);
  font-size: 22px;
  line-height: 1;
  background: var(--fc-surface);
  border: 1px solid var(--fc-border);
  box-shadow: var(--fc-shadow-soft);
}

.acd-kicker {
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.acd-title {
  margin-top: 10px;
  font-family: var(--fc-font-display);
  font-size: 20px;
  line-height: 1.15;
  color: var(--fc-text);
}

.acd-text {
  margin: 12px 0 0;
  font-family: var(--fc-font);
  font-size: 14px;
  line-height: 1.7;
  color: var(--fc-text-sec);
}

.acd-facts {
  margin-top: 18px;
  display: grid;
  gap: 10px;
}

.acd-fact {
  padding: 12px 14px;
  border-radius: 18px;
  border: 1px solid var(--fc-border);
  background: var(--fc-surface);
  font-family: var(--fc-font);
  font-size: 13px;
  line-height: 1.6;
  color: var(--fc-text-sec);
}

.acd-preview {
  margin-top: 18px;
  padding: 14px 16px;
  border-radius: 20px;
  border: 1px solid var(--fc-border);
  background: var(--fc-surface);
}

.acd-preview-label {
  display: inline-flex;
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.acd-preview-value {
  margin-top: 10px;
  font-family: var(--fc-font);
  font-size: 14px;
  line-height: 1.6;
  color: var(--fc-text);
  word-break: break-word;
}

.acd-actions {
  margin-top: 22px;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.acd-btn {
  min-width: 116px;
  padding: 12px 18px;
  border-radius: 999px;
  border: 1px solid var(--fc-border);
  font-family: var(--fc-font);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: filter var(--fc-duration-normal) var(--fc-ease-in-out), opacity var(--fc-duration-normal) var(--fc-ease-in-out);
}

.acd-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.acd-btn-ghost {
  background: var(--fc-surface);
  color: var(--fc-text);
}

.acd-btn-primary {
  color: #fffaf3;
}

.acd-btn-primary.is-danger {
  background: #a74f35;
}

.acd-btn-primary.is-warning {
  background: #a86d1f;
}

.acd-btn-primary.is-accent,
.acd-btn-primary.is-muted {
  background: var(--fc-accent);
}

.acd-btn:not(:disabled):hover {
  filter: brightness(1.03);
}

.acd-card.is-danger .acd-icon,
.acd-card.is-danger .acd-title {
  color: #8b3a35;
}

.acd-card.is-warning .acd-icon,
.acd-card.is-warning .acd-title {
  color: #8b641c;
}

.acd-card.is-accent .acd-icon,
.acd-card.is-accent .acd-title {
  color: var(--fc-accent-strong);
}

.acd-card.is-muted .acd-icon,
.acd-card.is-muted .acd-title {
  color: #6d5b4b;
}

.acd-enter-active,
.acd-leave-active {
  transition: opacity var(--fc-duration-normal) var(--fc-ease-in-out);
}

.acd-enter-active .acd-card,
.acd-leave-active .acd-card {
  transition: transform var(--fc-duration-normal) var(--fc-ease-in-out), opacity var(--fc-duration-normal) var(--fc-ease-in-out);
}

.acd-enter-from,
.acd-leave-to {
  opacity: 0;
}

.acd-enter-from .acd-card,
.acd-leave-to .acd-card {
  transform: translateY(12px) scale(0.98);
  opacity: 0;
}

@media (max-width: 768px) {
  .acd-mask {
    padding: 16px;
  }

  .acd-shell {
    padding: 20px;
  }

  .acd-head {
    grid-template-columns: 1fr;
  }

  .acd-title {
    font-size: 18px;
  }

  .acd-actions {
    flex-direction: column-reverse;
  }

  .acd-btn {
    width: 100%;
  }
}
</style>
