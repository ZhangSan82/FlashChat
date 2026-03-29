<template>
  <Teleport to="body">
    <transition name="rp">
      <div v-if="visible" class="rp-overlay" @click.self="$emit('close')">
        <div class="rp-card">
          <div class="rp-hdr">
            <button class="rp-x" @click="$emit('close')">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#2C2825" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
            <span class="rp-title">房间信息</span>
          </div>

          <div class="rp-body">
            <div class="rp-profile">
              <div class="rp-avatar">{{ (room?.title||'?')[0].toUpperCase() }}</div>
              <div class="rp-name">{{ room?.title || '—' }}</div>
              <div class="rp-meta">{{ members.length }} 位成员 · 创建于 {{ ctime }}</div>
            </div>

            <!-- 二维码 + 分享链接 -->
            <div class="rp-share" v-if="shareUrl">
              <div class="rp-share-label">扫码加入房间</div>
              <div class="rp-qr-box">
                <img v-if="qrDataUrl" :src="qrDataUrl" class="rp-qr-img" alt="QR Code" />
                <div v-else class="rp-qr-loading">
                  <div class="rp-qr-spinner"></div>
                </div>
              </div>
              <div class="rp-share-url-row">
                <input class="rp-share-input" :value="shareUrl" readonly @click="$event.target.select()" />
                <button class="rp-copy-btn" @click="copyShareUrl">
                  {{ copied ? '✓ 已复制' : '复制' }}
                </button>
              </div>
            </div>

            <div class="rp-cd">
              <div class="rp-cd-label">剩余时间</div>
              <div class="rp-cd-val" :style="{color: cdColor}">{{ cdText }}</div>
              <div class="rp-bar"><div class="rp-bar-fill" :style="{width: cdPct, background: cdColor}"></div></div>
            </div>

            <div class="rp-members">
              <div class="rp-m-title">成员 ({{ members.length }})</div>
              <div v-for="m in members" :key="m._id" class="rp-m-row">
                <div class="rp-m-aw">
                  <img v-if="hasAvatarImage(m)" :src="m.avatar" :alt="m.username || 'avatar'" class="rp-m-av rp-m-av-img" />
                  <div v-else class="rp-m-av" :style="{background: mColor(m)}">{{ mInit(m) }}</div>
                  <div class="rp-m-dot" :style="{background: m.status?.state==='online'?'#7BAF6E':'#B5B0A8'}"></div>
                </div>
                <div class="rp-m-info">
                  <div class="rp-m-name">{{ m.username }}</div>
                  <div class="rp-m-state">{{ m.status?.state==='online'?'在线':'离线' }}</div>
                </div>
                <div v-if="m._raw?.isHost||m._raw?.role===1" class="rp-m-badge">房主</div>
              </div>
            </div>

            <div class="rp-acts">
              <button class="rp-btn" @click="$emit('leave')">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>
                离开房间
              </button>
              <button class="rp-btn rp-danger" @click="$emit('close-room')">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>
                关闭房间
              </button>
            </div>
          </div>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<script setup>
import { computed, ref, watch, onMounted, onUnmounted } from 'vue'
import { formatCountdown, calcProgress, getCountdownColor } from '@/utils/formatter'
import { generateQRCodeDataUrl } from '@/utils/qrcode'

const props = defineProps({ visible: Boolean, room: Object, members: { type: Array, default: () => [] } })
defineEmits(['close', 'leave', 'close-room'])

const now = ref(Date.now())
let t = null
onMounted(() => { t = setInterval(() => now.value = Date.now(), 10000) })
onUnmounted(() => clearInterval(t))

const qrDataUrl = ref('')
const copied = ref(false)

const shareUrl = computed(() => props.room?.shareUrl || props.room?._raw?.shareUrl || null)

// 异步生成二维码
watch(shareUrl, async (url) => {
  if (url) {
    qrDataUrl.value = await generateQRCodeDataUrl(url, 180) || ''
  } else {
    qrDataUrl.value = ''
  }
}, { immediate: true })

const ctime = computed(() => {
  if (!props.room?.createTime) return '—'
  return new Date(props.room.createTime).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit',hour12:false})
})
const cdText = computed(() => {
  if (!props.room?.expireTime) return '永不过期'
  return formatCountdown(new Date(props.room.expireTime).getTime() - now.value)
})
const cdColor = computed(() => {
  if (!props.room?.expireTime) return '#B5B0A8'
  return getCountdownColor(new Date(props.room.expireTime).getTime() - now.value)
})
const cdPct = computed(() => {
  if (!props.room?.expireTime) return '100%'
  return Math.round(calcProgress(props.room.expireTime, props.room.createTime)*100)+'%'
})

function mInit(m) { return (m.username||'?')[0].toUpperCase() }
function hasAvatarImage(m) { return typeof m?.avatar === 'string' && m.avatar.length > 0 }
function mColor(m) { return m._raw?.avatar?.startsWith?.('#') ? m._raw.avatar : '#C8956C' }

async function copyShareUrl() {
  if (!shareUrl.value) return
  try {
    await navigator.clipboard.writeText(shareUrl.value)
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  } catch {
    const input = document.createElement('input')
    input.value = shareUrl.value
    document.body.appendChild(input)
    input.select()
    document.execCommand('copy')
    document.body.removeChild(input)
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  }
}
</script>

<style scoped>
.rp-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.25); z-index: 9500; display: flex; justify-content: flex-end; }
.rp-card { width: 320px; max-width: 85vw; height: 100%; background: #F5F0E8; display: flex; flex-direction: column; box-shadow: -4px 0 20px rgba(0,0,0,.12); }
.rp-hdr { height: 52px; display: flex; align-items: center; gap: 12px; padding: 0 16px; border-bottom: 1px solid #E5E0D8; flex-shrink: 0; }
.rp-x { width: 32px; height: 32px; border: none; border-radius: 50%; background: transparent; cursor: pointer; display: flex; align-items: center; justify-content: center; }
.rp-x:hover { background: #EDE8DF; }
.rp-title { font-family: 'Poppins',sans-serif; font-size: 16px; font-weight: 600; color: #2C2825; }
.rp-body { flex: 1; overflow-y: auto; padding: 20px 18px; }
.rp-profile { text-align: center; margin-bottom: 20px; }
.rp-avatar { width: 64px; height: 64px; border-radius: 50%; background: #C8956C; margin: 0 auto; display: flex; align-items: center; justify-content: center; color: #fff; font-family: 'Poppins',sans-serif; font-size: 24px; font-weight: 600; box-shadow: 4px 4px 8px #D1CBC3, -4px -4px 8px #fff; }
.rp-name { margin-top: 14px; font-family: 'Poppins',sans-serif; font-size: 18px; font-weight: 700; color: #2C2825; }
.rp-meta { margin-top: 4px; font-family: 'Poppins',sans-serif; font-size: 12px; color: #8A857E; }

/* 分享区域 */
.rp-share { background: #EDE8DF; border-radius: 14px; box-shadow: inset 2px 2px 5px #D1CBC3, inset -2px -2px 5px #fff; padding: 16px; margin-bottom: 20px; text-align: center; }
.rp-share-label { font-family: 'Poppins',sans-serif; font-size: 11px; font-weight: 500; color: #8A857E; margin-bottom: 12px; text-transform: uppercase; letter-spacing: 1px; }
.rp-qr-box { margin-bottom: 12px; min-height: 160px; display: flex; align-items: center; justify-content: center; }
.rp-qr-img { width: 160px; height: 160px; border-radius: 10px; background: #fff; padding: 8px; box-shadow: 2px 2px 6px #D1CBC3, -2px -2px 6px #fff; }
.rp-qr-loading { width: 160px; height: 160px; display: flex; align-items: center; justify-content: center; }
.rp-qr-spinner { width: 28px; height: 28px; border: 3px solid #E5E0D8; border-top-color: #C8956C; border-radius: 50%; animation: rp-spin 0.7s linear infinite; }
@keyframes rp-spin { to { transform: rotate(360deg); } }
.rp-share-url-row { display: flex; gap: 8px; }
.rp-share-input { flex: 1; padding: 8px 10px; background: #F5F0E8; border: none; border-radius: 8px; font-family: 'SF Mono','Fira Code',monospace; font-size: 11px; color: #8A857E; outline: none; box-shadow: inset 2px 2px 4px #D1CBC3, inset -2px -2px 4px #fff; }
.rp-copy-btn { padding: 8px 14px; border: none; border-radius: 8px; background: #C8956C; color: #fff; font-family: 'Poppins',sans-serif; font-size: 12px; font-weight: 600; cursor: pointer; white-space: nowrap; transition: all .2s; }
.rp-copy-btn:hover { filter: brightness(1.05); }

.rp-cd { background: #EDE8DF; border-radius: 12px; padding: 14px 16px; margin-bottom: 20px; box-shadow: inset 2px 2px 4px #D1CBC3, inset -2px -2px 4px #fff; }
.rp-cd-label { font-family: 'Poppins',sans-serif; font-size: 11px; color: #8A857E; margin-bottom: 4px; }
.rp-cd-val { font-family: 'Poppins',sans-serif; font-size: 22px; font-weight: 700; }
.rp-bar { height: 4px; background: #E5E0D8; border-radius: 2px; margin-top: 10px; }
.rp-bar-fill { height: 4px; border-radius: 2px; transition: width 1s; }
.rp-members { margin-bottom: 20px; }
.rp-m-title { font-family: 'Poppins',sans-serif; font-size: 13px; font-weight: 500; color: #8A857E; margin-bottom: 12px; }
.rp-m-row { display: flex; align-items: center; gap: 10px; padding: 8px 0; border-bottom: 1px solid #EDE8DF; }
.rp-m-row:last-child { border-bottom: none; }
.rp-m-aw { position: relative; flex-shrink: 0; }
.rp-m-av { width: 34px; height: 34px; border-radius: 50%; display: flex; align-items: center; justify-content: center; color: #fff; font-family: 'Poppins',sans-serif; font-size: 13px; font-weight: 600; }
.rp-m-av-img { object-fit: cover; box-shadow: 2px 2px 4px #D1CBC3, -2px -2px 4px #fff; }
.rp-m-dot { position: absolute; bottom: -1px; right: -1px; width: 10px; height: 10px; border-radius: 50%; border: 2px solid #F5F0E8; }
.rp-m-info { flex: 1; }
.rp-m-name { font-family: 'Poppins',sans-serif; font-size: 14px; font-weight: 500; color: #2C2825; }
.rp-m-state { font-family: 'Poppins',sans-serif; font-size: 11px; color: #8A857E; }
.rp-m-badge { font-family: 'Poppins',sans-serif; font-size: 10px; font-weight: 600; color: #C8956C; background: #EDE8DF; padding: 2px 10px; border-radius: 20px; }
.rp-acts { display: flex; flex-direction: column; gap: 8px; margin-top: 8px; }
.rp-btn { padding: 12px 16px; border: none; border-radius: 12px; background: #F5F0E8; box-shadow: 3px 3px 6px #D1CBC3, -3px -3px 6px #fff; font-family: 'Poppins',sans-serif; font-size: 14px; color: #2C2825; cursor: pointer; display: flex; align-items: center; gap: 10px; transition: all .2s; }
.rp-btn:hover { box-shadow: inset 2px 2px 4px #D1CBC3, inset -2px -2px 4px #fff; }
.rp-danger { color: #D4736C; }
.rp-enter-active { transition: opacity .25s; }
.rp-leave-active { transition: opacity .2s; }
.rp-enter-active .rp-card { transition: transform .25s cubic-bezier(.4,0,.2,1); }
.rp-leave-active .rp-card { transition: transform .2s; }
.rp-enter-from, .rp-leave-to { opacity: 0; }
.rp-enter-from .rp-card, .rp-leave-to .rp-card { transform: translateX(100%); }
</style>

<style scoped>
.rp-overlay {
  background: var(--fc-backdrop);
  backdrop-filter: blur(16px);
}

.rp-card {
  width: 410px;
  max-width: 94vw;
  background: linear-gradient(180deg, rgba(255, 250, 243, 0.96), rgba(247, 239, 228, 0.98));
  border-left: 1px solid rgba(77, 52, 31, 0.12);
  box-shadow: -20px 0 50px rgba(61, 40, 22, 0.18);
}

.rp-hdr {
  height: 72px;
  padding: 0 22px;
  border-bottom: 1px solid rgba(77, 52, 31, 0.08);
}

.rp-x {
  width: 40px;
  height: 40px;
  border: 1px solid var(--fc-border);
  background: rgba(255, 250, 243, 0.82);
  transition: transform .2s ease, background .2s ease;
}

.rp-x:hover {
  background: #fffaf3;
  transform: rotate(90deg);
}

.rp-title {
  font-family: var(--fc-font);
  font-size: 20px;
  font-weight: 700;
  color: var(--fc-text);
}

.rp-body { padding: 22px; }

.rp-profile,
.rp-share,
.rp-cd,
.rp-members {
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 24px;
  background: rgba(255, 250, 243, 0.74);
  box-shadow: var(--fc-shadow-soft);
}

.rp-profile {
  padding: 24px;
  margin-bottom: 18px;
}

.rp-avatar {
  width: 76px;
  height: 76px;
  background: linear-gradient(145deg, #be8c58, #8c5a2b);
  font-size: 28px;
  font-weight: 700;
  box-shadow: 0 18px 30px rgba(140, 90, 43, 0.24);
}

.rp-name {
  margin-top: 16px;
  font-family: var(--fc-font);
  font-size: 24px;
  font-weight: 700;
  color: var(--fc-text);
}

.rp-meta {
  margin-top: 8px;
  font-family: var(--fc-font);
  font-size: 13px;
  color: var(--fc-text-sec);
}

.rp-share,
.rp-cd,
.rp-members {
  padding: 18px;
  margin-bottom: 18px;
}

.rp-share-label,
.rp-cd-label,
.rp-m-title {
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
  color: var(--fc-text-muted);
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.rp-share-label { margin-bottom: 14px; }

.rp-qr-box {
  margin-bottom: 14px;
  min-height: 170px;
}

.rp-qr-img {
  width: 170px;
  height: 170px;
  border-radius: 18px;
  padding: 10px;
  box-shadow: 0 16px 26px rgba(61, 40, 22, 0.12);
}

.rp-qr-spinner {
  border-color: rgba(77, 52, 31, 0.10);
  border-top-color: var(--fc-accent);
}

.rp-share-input {
  padding: 11px 12px;
  background: rgba(243, 231, 215, 0.92);
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 14px;
  color: var(--fc-text-sec);
  box-shadow: none;
}

.rp-copy-btn,
.rp-btn {
  border: 1px solid rgba(77, 52, 31, 0.10);
  border-radius: 16px;
  font-family: var(--fc-font);
  font-weight: 600;
  transition: transform .2s ease, filter .2s ease, background .2s ease;
}

.rp-copy-btn {
  padding: 11px 14px;
  background: linear-gradient(135deg, #b68450 0%, #8c5a2b 100%);
  color: #fffaf3;
}

.rp-copy-btn:hover,
.rp-btn:hover {
  transform: translateY(-1px);
  filter: brightness(1.03);
}

.rp-cd-val {
  font-family: var(--fc-font);
  font-size: 28px;
}

.rp-bar {
  height: 6px;
  background: rgba(77, 52, 31, 0.08);
  border-radius: 999px;
  margin-top: 14px;
}

.rp-bar-fill {
  height: 6px;
  border-radius: 999px;
}

.rp-m-title { margin-bottom: 14px; }

.rp-m-row {
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid rgba(77, 52, 31, 0.06);
}

.rp-m-av {
  width: 42px;
  height: 42px;
  font-size: 15px;
}

.rp-m-av-img { box-shadow: 0 12px 20px rgba(61, 40, 22, 0.12); }

.rp-m-dot {
  width: 12px;
  height: 12px;
  border: 2px solid #fffaf3;
}

.rp-m-name {
  font-family: var(--fc-font);
  font-size: 15px;
  font-weight: 600;
  color: var(--fc-text);
}

.rp-m-state {
  margin-top: 3px;
  font-family: var(--fc-font);
  font-size: 12px;
  color: var(--fc-text-sec);
}

.rp-m-badge {
  font-family: var(--fc-font);
  font-size: 11px;
  color: var(--fc-accent-strong);
  background: rgba(243, 231, 215, 0.9);
  padding: 5px 12px;
  border-radius: 999px;
}

.rp-acts {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.rp-btn {
  padding: 14px 16px;
  background: rgba(255, 250, 243, 0.78);
  color: var(--fc-text);
  box-shadow: var(--fc-shadow-soft);
}

.rp-danger {
  color: var(--fc-danger);
  background: rgba(255, 243, 241, 0.82);
}

.rp-enter-active { transition: opacity .28s ease; }
.rp-leave-active { transition: opacity .22s ease; }
.rp-enter-active .rp-card { transition: transform .28s cubic-bezier(.2,.8,.2,1); }
.rp-leave-active .rp-card { transition: transform .2s ease; }
.rp-enter-from, .rp-leave-to { opacity: 0; }
.rp-enter-from .rp-card, .rp-leave-to .rp-card { transform: translateX(100%); }

@media (max-width: 640px) {
  .rp-card {
    width: 100%;
    max-width: 100%;
  }
}
</style>
