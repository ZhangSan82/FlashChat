<template>
  <div
    class="mrs-shell"
    :class="{ 'mrs-shell-open': open }"
    @click.capture="onClickCapture"
    @touchstart.passive="onTouchStart"
    @touchmove="onTouchMove"
    @touchend="onTouchEnd"
    @touchcancel="onTouchEnd"
  >
    <div class="mrs-actions" :style="actionsStyle">
      <button
        v-for="action in quickActions"
        :key="action.name"
        class="mrs-action"
        :class="`mrs-action-${action.tone}`"
        type="button"
        @click.stop="emitAction(action.name)"
      >
        {{ action.label }}
      </button>
    </div>

    <div class="mrs-card" :style="cardStyle">
      <div class="mrs-card-sheen"></div>

      <div class="mrs-avatar-shell">
        <img class="mrs-avatar" :src="roomVisualUrl" :alt="roomName" />
      </div>

      <div class="mrs-body">
        <div class="mrs-topline">
          <span class="mrs-spacer"></span>
          <span class="mrs-countdown">⏳ {{ countdownText }}</span>
        </div>

        <div class="mrs-title-row">
          <div class="mrs-title">{{ roomName }}</div>
        </div>

        <div class="mrs-footer">
          <div v-if="room?.unreadCount" class="mrs-badge">{{ unreadText }}</div>
          <div class="mrs-members">{{ memberText }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { getRoomDisplayName, getRoomVisualUrl } from '@/utils/roomVisual'

const props = defineProps({
  room: { type: Object, required: true },
  selected: { type: Boolean, default: false },
  open: { type: Boolean, default: false },
  canClose: { type: Boolean, default: false }
})

const emit = defineEmits(['swipe-state', 'action'])

const dragging = ref(false)
const offset = ref(0)
const suppressTap = ref(false)

let startX = 0
let startY = 0
let startOffset = 0
let gestureLock = ''
let suppressTimer = null

const quickActions = computed(() => [
  { name: 'roomInfo', label: '详情', tone: 'soft' },
  props.canClose
    ? { name: 'closeRoom', label: '关闭', tone: 'danger' }
    : { name: 'leaveRoom', label: '离开', tone: 'warn' }
])

const actionsWidth = computed(() => quickActions.value.length * 82)

watch(
  () => props.open,
  (open) => {
    offset.value = open ? actionsWidth.value : 0
  },
  { immediate: true }
)

watch(actionsWidth, (width) => {
  offset.value = props.open ? width : Math.min(offset.value, width)
})

const roomName = computed(() => getRoomDisplayName(props.room))
const roomVisualUrl = computed(() => getRoomVisualUrl(props.room))

const countdownText = computed(() => {
  const raw = String(props.room?.lastMessage?.timestamp || '').replace(/\s+/g, ' ').trim()
  if (!raw || raw === '-' || raw === '--') return '永久'
  return raw.replace(/^[⌛⏳]\s*/, '')
})

const memberText = computed(() => {
  const total = Number(
    props.room?._raw?.memberCount
      ?? props.room?.memberCount
      ?? (Array.isArray(props.room?.users) ? props.room.users.filter((user) => !String(user?._id || '').startsWith('_placeholder_')).length : 0)
  )
  return `${total} 位成员`
})

const unreadText = computed(() => {
  const count = Number(props.room?.unreadCount || 0)
  return count > 99 ? '99+' : String(count)
})

const cardStyle = computed(() => ({
  transform: `translate3d(-${offset.value}px, 0, 0)`,
  transition: dragging.value ? 'none' : 'transform .22s ease'
}))

const actionsStyle = computed(() => {
  const reveal = actionsWidth.value ? Math.min(offset.value / actionsWidth.value, 1) : 0
  return {
    width: `${actionsWidth.value}px`,
    opacity: reveal,
    transform: `translate3d(${(1 - reveal) * 18}px, 0, 0)`,
    pointerEvents: reveal > 0.04 ? 'auto' : 'none'
  }
})

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value))
}

function rememberSuppressTap() {
  suppressTap.value = true
  clearTimeout(suppressTimer)
  suppressTimer = setTimeout(() => {
    suppressTap.value = false
  }, 260)
}

function onTouchStart(event) {
  if (event.touches.length !== 1) return
  const touch = event.touches[0]
  startX = touch.clientX
  startY = touch.clientY
  startOffset = offset.value
  gestureLock = ''
  dragging.value = false
}

function onTouchMove(event) {
  if (event.touches.length !== 1) return

  const touch = event.touches[0]
  const deltaX = touch.clientX - startX
  const deltaY = touch.clientY - startY

  if (!gestureLock) {
    if (Math.abs(deltaX) < 10 && Math.abs(deltaY) < 10) return
    gestureLock = Math.abs(deltaX) > Math.abs(deltaY) ? 'x' : 'y'
  }

  if (gestureLock !== 'x') return

  dragging.value = true
  offset.value = clamp(startOffset - deltaX, 0, actionsWidth.value)
  emit('swipe-state', props.room?.roomId || '')

  if (event.cancelable) {
    event.preventDefault()
  }
}

function onTouchEnd() {
  if (gestureLock !== 'x') {
    gestureLock = ''
    dragging.value = false
    return
  }

  const shouldOpen = offset.value > actionsWidth.value * 0.38
  offset.value = shouldOpen ? actionsWidth.value : 0
  emit('swipe-state', shouldOpen ? props.room?.roomId || '' : '')
  rememberSuppressTap()
  gestureLock = ''
  dragging.value = false
}

function onClickCapture(event) {
  if (!suppressTap.value) return
  event.preventDefault()
  event.stopPropagation()
  suppressTap.value = false
}

function emitAction(name) {
  offset.value = 0
  emit('swipe-state', '')
  emit('action', name)
}
</script>

<style scoped>
.mrs-shell {
  position: relative;
  width: 100%;
  min-height: 104px;
  touch-action: pan-y;
}

.mrs-actions {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  display: flex;
  align-items: stretch;
  justify-content: flex-end;
  gap: 10px;
  padding: 8px 2px 8px 14px;
  transition: opacity .18s ease, transform .18s ease;
}

.mrs-action {
  width: 72px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 24px;
  color: var(--fc-text);
  font-family: var(--fc-font);
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.02em;
}

.mrs-action-soft {
  background: #eddcc4;
  color: #8c5a2b;
}

.mrs-action-warn {
  background: #efd1a8;
  color: #9a6328;
}

.mrs-action-danger {
  background: #edc7c2;
  color: #a0554c;
}

.mrs-card {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 16px;
  min-height: 104px;
  padding: 16px;
  border-radius: 28px;
  background: var(--fc-surface);
  border: 1px solid var(--fc-border);
  box-shadow: var(--fc-shadow-soft);
  overflow: hidden;
}

/* .mrs-card-sheen overlay removed for fusion design */
.mrs-card-sheen {
  display: none;
}

.mrs-avatar-shell {
  position: relative;
  flex: 0 0 58px;
  width: 58px;
  height: 58px;
  padding: 4px;
  border-radius: 999px;
  background: var(--fc-surface);
  box-shadow: inset 0 0 0 1px var(--fc-border);
}

.mrs-avatar {
  width: 100%;
  height: 100%;
  border-radius: 999px;
  object-fit: cover;
  display: block;
  background: var(--fc-accent);
}

.mrs-body {
  position: relative;
  min-width: 0;
  flex: 1;
}

.mrs-topline,
.mrs-title-row,
.mrs-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.mrs-spacer,
.mrs-countdown,
.mrs-members {
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.1em;
  color: var(--fc-text-muted);
}

.mrs-spacer {
  min-width: 1px;
  flex: 1;
}

.mrs-countdown {
  text-transform: uppercase;
  white-space: nowrap;
}

.mrs-title {
  min-width: 0;
  flex: 1;
  margin-top: 10px;
  font-family: var(--fc-font-display);
  font-size: 18px;
  line-height: 1.15;
  font-weight: 600;
  color: var(--fc-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  text-transform: none;
}

.mrs-footer {
  margin-top: 16px;
}

.mrs-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 24px;
  padding: 0 10px;
  border-radius: 999px;
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
}

.mrs-badge {
  min-width: 24px;
  background: #c96c59;
  color: #fffaf3;
}

.mrs-members {
  margin-left: auto;
  letter-spacing: 0.04em;
  text-transform: none;
}
</style>
