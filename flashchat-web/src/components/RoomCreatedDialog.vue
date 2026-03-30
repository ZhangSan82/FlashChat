<template>
  <Teleport to="body">
    <transition name="created-room">
      <div v-if="visible" class="created-room-overlay">
        <div class="created-room-card">
          <button
            class="created-room-close"
            type="button"
            aria-label="关闭并进入房间"
            @click="$emit('enter')"
          >
            ×
          </button>

          <div class="created-room-kicker">Room Created</div>
          <h3 class="created-room-title">房间已创建</h3>
          <p class="created-room-desc">先把二维码给对方扫描，准备好了再关闭并进入房间。</p>

          <div class="created-room-qr-shell">
            <img v-if="qrDataUrl" :src="qrDataUrl" alt="room qrcode" class="created-room-qr" />
            <div v-else class="created-room-loading">二维码生成中...</div>
          </div>

          <div class="created-room-name">{{ room?.title || '未命名房间' }}</div>
          <div class="created-room-meta">
            <span>房间 ID {{ room?.roomId || '—' }}</span>
            <span>·</span>
            <span>{{ room?.isPublic === 1 ? '公开房间' : '私密房间' }}</span>
          </div>

          <input
            class="created-room-link"
            :value="resolvedShareUrl"
            readonly
            @click="$event.target.select()"
          />

          <div class="created-room-actions">
            <button class="created-room-btn created-room-btn-ghost" type="button" @click="copyShareUrl">
              {{ copied ? '已复制' : '复制链接' }}
            </button>
            <button class="created-room-btn created-room-btn-primary" type="button" @click="$emit('enter')">
              关闭并进入房间
            </button>
          </div>

          <p class="created-room-tip">第一次使用的人扫码后会先看到加入页，再一键进入房间。</p>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<script setup>
import { ref, watch } from 'vue'
import { getShareUrl } from '@/api/room'
import { generateQRCodeDataUrl } from '@/utils/qrcode'

const props = defineProps({
  visible: { type: Boolean, default: false },
  room: { type: Object, default: null }
})

defineEmits(['enter'])

const resolvedShareUrl = ref('')
const qrDataUrl = ref('')
const copied = ref(false)

watch(
  () => [props.visible, props.room?.roomId, props.room?.shareUrl, props.room?._raw?.shareUrl],
  async ([visible]) => {
    if (!visible) {
      resolvedShareUrl.value = ''
      qrDataUrl.value = ''
      copied.value = false
      return
    }
    await ensureShareUrl()
  },
  { immediate: true }
)

watch(resolvedShareUrl, async url => {
  if (!url) {
    qrDataUrl.value = ''
    return
  }
  qrDataUrl.value = (await generateQRCodeDataUrl(url, 240)) || ''
})

async function ensureShareUrl() {
  const existing = props.room?.shareUrl || props.room?._raw?.shareUrl || ''
  if (existing) {
    resolvedShareUrl.value = existing
    return
  }
  if (!props.room?.roomId) {
    resolvedShareUrl.value = ''
    return
  }
  try {
    resolvedShareUrl.value = await getShareUrl(props.room.roomId)
  } catch {
    resolvedShareUrl.value = `${window.location.origin}/room/${props.room.roomId}`
  }
}

async function copyShareUrl() {
  if (!resolvedShareUrl.value) return
  try {
    await navigator.clipboard.writeText(resolvedShareUrl.value)
  } catch {
    const input = document.createElement('input')
    input.value = resolvedShareUrl.value
    document.body.appendChild(input)
    input.select()
    document.execCommand('copy')
    document.body.removeChild(input)
  }
  copied.value = true
  window.setTimeout(() => {
    copied.value = false
  }, 1600)
}
</script>

<style scoped>
.created-room-overlay {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(36, 24, 14, 0.28);
  backdrop-filter: blur(16px);
  z-index: 9700;
}

.created-room-card {
  position: relative;
  width: min(460px, 100%);
  padding: 28px;
  border: 1px solid rgba(77, 52, 31, 0.12);
  border-radius: 30px;
  background:
    radial-gradient(circle at top right, rgba(221, 193, 163, 0.24), transparent 32%),
    linear-gradient(180deg, rgba(255, 250, 243, 0.98), rgba(247, 239, 228, 0.98));
  box-shadow: 0 34px 60px rgba(61, 40, 22, 0.22);
  text-align: center;
}

.created-room-close {
  position: absolute;
  top: 18px;
  right: 18px;
  width: 42px;
  height: 42px;
  border: 1px solid rgba(77, 52, 31, 0.10);
  border-radius: 50%;
  background: rgba(255, 250, 243, 0.88);
  color: var(--fc-text);
  font-size: 26px;
  line-height: 1;
  cursor: pointer;
}

.created-room-kicker,
.created-room-tip {
  font-family: var(--fc-font);
  color: var(--fc-text-muted);
}

.created-room-kicker {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.created-room-title {
  margin: 10px 0 10px;
  font-family: var(--fc-font);
  font-size: 30px;
  line-height: 1.04;
  color: var(--fc-text);
}

.created-room-desc,
.created-room-meta {
  font-family: var(--fc-font);
  color: var(--fc-text-sec);
}

.created-room-desc {
  margin: 0 auto;
  max-width: 320px;
  font-size: 14px;
  line-height: 1.6;
}

.created-room-qr-shell {
  width: min(260px, 100%);
  min-height: 260px;
  margin: 22px auto 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 28px;
  background: rgba(243, 231, 215, 0.88);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.55);
}

.created-room-qr {
  width: 220px;
  height: 220px;
  padding: 12px;
  border-radius: 22px;
  background: #fffaf3;
}

.created-room-loading {
  font-family: var(--fc-font);
  font-size: 13px;
  color: var(--fc-text-sec);
}

.created-room-name {
  margin-top: 18px;
  font-family: var(--fc-font);
  font-size: 24px;
  font-weight: 700;
  color: var(--fc-text);
  word-break: break-word;
}

.created-room-meta {
  margin-top: 8px;
  display: flex;
  justify-content: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 13px;
}

.created-room-link {
  width: 100%;
  margin-top: 18px;
  padding: 13px 14px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 18px;
  background: rgba(243, 231, 215, 0.92);
  color: var(--fc-text-sec);
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-size: 12px;
}

.created-room-actions {
  margin-top: 16px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.created-room-btn {
  padding: 14px 16px;
  border: 1px solid rgba(77, 52, 31, 0.10);
  border-radius: 18px;
  font-family: var(--fc-font);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}

.created-room-btn-ghost {
  background: rgba(255, 250, 243, 0.86);
  color: var(--fc-text-sec);
}

.created-room-btn-primary {
  background: linear-gradient(135deg, #b68450 0%, #8c5a2b 100%);
  color: #fffaf3;
  box-shadow: 0 16px 28px rgba(140, 90, 43, 0.22);
}

.created-room-tip {
  margin-top: 14px;
  font-size: 12px;
  line-height: 1.6;
}

.created-room-enter-active,
.created-room-leave-active {
  transition: opacity .24s ease;
}

.created-room-enter-active .created-room-card,
.created-room-leave-active .created-room-card {
  transition: transform .24s ease, opacity .24s ease;
}

.created-room-enter-from,
.created-room-leave-to {
  opacity: 0;
}

.created-room-enter-from .created-room-card,
.created-room-leave-to .created-room-card {
  transform: translateY(14px) scale(0.98);
  opacity: 0;
}

@media (max-width: 640px) {
  .created-room-card {
    padding: 22px 18px 18px;
    border-radius: 24px;
  }

  .created-room-qr-shell {
    min-height: 220px;
  }

  .created-room-qr {
    width: 188px;
    height: 188px;
  }

  .created-room-actions {
    grid-template-columns: 1fr;
  }
}
</style>
