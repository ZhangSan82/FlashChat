<template>
  <div class="fc-root">
    <!-- 加载屏 -->
    <div v-if="!authReady" class="fc-loading">
      <div class="fc-pulse"></div>
      <p v-if="!initFailed" class="fc-load-text">正在连接 FlashChat...</p>
      <div v-else class="fc-load-fail">
        <p class="fc-load-text fc-danger">连接失败</p>
        <p class="fc-load-hint">请确认后端服务已启动（8081 端口）</p>
        <button class="fc-retry" @click="retryInit">重新连接</button>
      </div>
    </div>

    <!-- 主界面 -->
    <vue-advanced-chat
        v-else
        ref="chatEl"
        :height="viewH"
        :current-user-id="myId"
        :rooms="roomsStr"
        :rooms-loaded="chat.roomsLoaded.value"
        :loading-rooms="chat.loadingRooms.value"
        :messages="msgsStr"
        :messages-loaded="chat.messagesLoaded.value"
        :room-id="chat.currentRoomId.value || ''"
        show-search="true"
        show-add-room="true"
        show-files="true"
        show-audio="true"
        show-emojis="true"
        show-reaction-emojis="false"
        show-new-messages-divider="true"
        :show-footer="!!chat.currentRoomId.value"
        :text-messages="txtMsg"
        :room-actions="roomAct"
        :message-actions="msgAct"
        :accepted-files="acceptFiles"
        :styles="themeStr"
        room-info-enabled="true"
    />

    <!-- 侧滑菜单 -->
    <SideDrawer
        :visible="drawerOpen"
        :nickname="auth.identity.value?.nickname || ''"
        :account-id="auth.identity.value?.accountId || ''"
        :avatar-color="auth.identity.value?.avatarColor || '#C8956C'"
        @close="drawerOpen = false"
        @action="onDrawerAction"
    />

    <!-- 房间信息面板 -->
    <RoomInfoPanel
        :visible="panelOpen"
        :room="curRoomRaw"
        :members="curMembers"
        @close="panelOpen = false"
        @leave="doLeaveFromPanel"
        @close-room="doCloseFromPanel"
    />

    <!-- 弹窗 -->
    <CreateRoomDialog :visible="createDlg" @create="doCreateRoom" @close="createDlg = false" />
    <JoinRoomDialog :visible="joinDlg" @join="doJoinRoom" @close="joinDlg = false" />

    <!-- Toast -->
    <transition name="toast">
      <div v-if="toast.show" :class="['fc-toast', `fc-toast-${toast.type}`]">{{ toast.msg }}</div>
    </transition>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { register } from 'vue-advanced-chat'
register()

import SideDrawer from './SideDrawer.vue'
import RoomInfoPanel from './RoomInfoPanel.vue'
import CreateRoomDialog from './CreateRoomDialog.vue'
import JoinRoomDialog from './JoinRoomDialog.vue'

import { useAuth } from '@/composables/useAuth'
import { useWebSocket, WS_TYPE } from '@/composables/useWebSocket'
import { useChat } from '@/composables/useChat'

const auth = useAuth()
const ws = useWebSocket()
const chat = useChat(() => auth.identity.value?.accountId, () => auth.memberId.value)

const authReady = computed(() => auth.isReady.value)
const myId = computed(() => String(auth.memberId.value || ''))
const drawerOpen = ref(false)
const panelOpen = ref(false)
const createDlg = ref(false)
const joinDlg = ref(false)
const viewH = ref('100vh')
const chatEl = ref(null)
const initFailed = ref(false)
const curMembers = ref([])
let bound = false
let cdTimer = null
let expiryTimer = null
let roomObserver = null

const curRoomRaw = computed(() =>
    chat.roomsRaw.value.find(r => r.roomId === chat.currentRoomId.value) || null
)

// ---- props ----
const roomsStr = computed(() => JSON.stringify(chat.rooms.value))
const msgsStr = computed(() => JSON.stringify(chat.messages.value))

const txtMsg = JSON.stringify({
  ROOMS_EMPTY: '还没有加入任何房间，点击右上角 + 号开始',
  ROOM_EMPTY: '还没有消息', NEW_MESSAGES: '新消息',
  MESSAGE_DELETED: '此消息已被删除', MESSAGES_EMPTY: '还没有消息',
  CONVERSATION_STARTED: '会话开始', TYPE_MESSAGE: '输入消息...',
  SEARCH: '搜索房间...', IS_ONLINE: '在线', LAST_SEEN: '最近活跃',
  IS_TYPING: '正在输入...'
})

const roomAct = JSON.stringify([
  { name: 'roomInfo', title: '房间信息' },
  { name: 'leaveRoom', title: '离开房间' },
  { name: 'closeRoom', title: '关闭房间（仅房主）' }
])

const msgAct = JSON.stringify([{ name: 'replyMessage', title: '回复' }])
const acceptFiles = 'image/*,audio/*,video/*,application/pdf,text/plain'

// ---- 暖白主题 ----
const themeStr = JSON.stringify({
  general: {
    color: '#2C2825', colorSpinner: '#C8956C', borderStyle: 'none',
    backgroundInput: '#EDE8DF', colorPlaceholder: '#B5B0A8',
    colorCaret: '#C8956C', backgroundScrollIcon: '#F5F0E8'
  },
  container: { border: 'none', borderRadius: '0', boxShadow: 'none' },
  header: { background: '#F5F0E8', colorRoomName: '#2C2825', colorRoomInfo: '#8A857E' },
  footer: { background: '#F5F0E8', backgroundReply: '#EDE8DF' },
  sidenav: {
    background: '#F5F0E8', backgroundHover: '#EDE8DF', backgroundActive: '#EDE8DF',
    colorActive: '#2C2825', borderColorSearch: '#E5E0D8'
  },
  content: { background: '#FAF7F2' },
  message: {
    background: '#FFFFFF', backgroundMe: '#F5F0E8', color: '#2C2825',
    colorStarted: '#B5B0A8', backgroundDeleted: '#EDE8DF', colorDeleted: '#8A857E',
    colorUsername: '#C8956C', colorTimestamp: '#B5B0A8',
    backgroundDate: '#F5F0E8', colorDate: '#8A857E',
    backgroundSystem: 'transparent', colorSystem: '#8A857E',
    colorNewMessages: '#C8956C',
    backgroundReply: 'rgba(200,149,108,0.08)', colorReplyUsername: '#C8956C',
    backgroundImage: '#EDE8DF'
  },
  room: {
    colorUsername: '#2C2825', colorMessage: '#8A857E', colorTimestamp: '#8A857E',
    colorStateOnline: '#7BAF6E', colorStateOffline: '#B5B0A8',
    backgroundCounterBadge: '#C8956C', colorCounterBadge: '#FFFFFF'
  },
  emoji: { background: '#F5F0E8' },
  icons: {
    search: '#8A857E', add: '#C8956C', toggle: '#8A857E', menu: '#8A857E',
    close: '#8A857E', file: '#C8956C', paperclip: '#8A857E', send: '#C8956C',
    sendDisabled: '#B5B0A8', emoji: '#8A857E', document: '#C8956C',
    checkmark: '#C8956C', checkmarkSeen: '#C8956C', eye: '#8A857E',
    dropdownMessage: '#8A857E', dropdownRoom: '#8A857E', dropdownScroll: '#C8956C',
    microphone: '#8A857E', audioPlay: '#C8956C', audioPause: '#C8956C',
    audioCancel: '#D4736C', audioConfirm: '#7BAF6E'
  }
})

// ---- toast ----
const toast = ref({ show: false, msg: '', type: 'info' })
let toastT = null
function showToast(m, t = 'info', d = 3000) {
  toast.value = { show: true, msg: m, type: t }
  clearTimeout(toastT); toastT = setTimeout(() => { toast.value.show = false }, d)
}

// ================================================================
//  Shadow DOM CSS 注入
// ================================================================
function injectShadowCSS() {
  const el = chatEl.value
  if (!el?.shadowRoot) return
  if (el.shadowRoot.querySelector('#fc-injected')) return

  const style = document.createElement('style')
  style.id = 'fc-injected'
  style.textContent = `
    /* 房间选中态 — 多层选择器提高优先级 */
    .vac-room-item.vac-room-selected,
    .vac-rooms-container .vac-room-selected,
    [class*="room-selected"] {
      background: #EDE8DF !important;
      border-left: 3px solid #C8956C !important;
      box-shadow: inset 2px 2px 4px #D1CBC3, inset -2px -2px 4px #FFFFFF !important;
    }
    /* 未选中的房间 — 凸起阴影 */
    .vac-room-item:not(.vac-room-selected) {
      box-shadow: 3px 3px 6px #D1CBC3, -3px -3px 6px #FFFFFF !important;
      background: #F5F0E8 !important;
    }
    .vac-room-item:hover {
      background: #EDE8DF !important;
      box-shadow: inset 2px 2px 4px #D1CBC3, inset -2px -2px 4px #FFFFFF !important;
    }
    .vac-room-item.vac-room-selected:hover {
      background: #EDE8DF !important;
      box-shadow: inset 2px 2px 4px #D1CBC3, inset -2px -2px 4px #FFFFFF !important;
    }
    .vac-text-username {
      font-size: 11px !important;
      font-weight: 600 !important;
    }
    .vac-message-wrapper .vac-message-current .vac-message-card {
      box-shadow: 3px 3px 6px #D1CBC3, -3px -3px 6px #FFFFFF !important;
      border: none !important;
    }
    .vac-message-wrapper:not(.vac-message-current) .vac-message-card {
      border: 1px solid rgba(0,0,0,0.04) !important;
      box-shadow: none !important;
    }
    .vac-room-footer .vac-box-footer {
      box-shadow: inset 2px 2px 4px #D1CBC3, inset -2px -2px 4px #FFFFFF !important;
      border: none !important;
      border-radius: 12px !important;
    }
    .vac-rooms-container .vac-room-header input {
      box-shadow: inset 2px 2px 4px #D1CBC3, inset -2px -2px 4px #FFFFFF !important;
      border: none !important;
      border-radius: 10px !important;
    }
    .vac-room-item {
      border-radius: 12px !important;
      margin: 3px 6px !important;
    }
    .vac-card-date {
      border-radius: 20px !important;
      box-shadow: 2px 2px 4px #D1CBC3, -2px -2px 4px #FFFFFF !important;
    }
    ::-webkit-scrollbar { width: 4px; }
    ::-webkit-scrollbar-thumb { background: #C8C3BB; border-radius: 4px; }
    ::-webkit-scrollbar-track { background: transparent; }
    .vac-room-header .vac-add-icon { border-radius: 50% !important; }
  `
  el.shadowRoot.appendChild(style)
  console.log('[FlashChat] ✅ Shadow DOM CSS 注入成功')
}

// ================================================================
//  ★ 主动加载房间消息（不依赖 fetch-messages 事件）
// ================================================================
async function forceLoadRoom(rid) {
  if (!rid || rid === '') return
  console.log('[FlashChat] ★ 主动加载房间:', rid)
  // ★ 先加载成员，确保 users 数组完整，组件才能显示发送者名字
  const members = await chat.loadRoomUsers(rid)
  curMembers.value = members || []
  // ★ 强制刷新 rooms 让组件拿到最新的 users
  chat.refreshRoomCountdowns()
  // 再加载消息
  chat.loadMessages(rid)
}
// ================================================================
//  ★ 事件绑定 — 只绑一次
// ================================================================
function bindEvents() {
  if (bound) return
  const el = chatEl.value
  if (!el) return
  bound = true
  console.log('[FlashChat] ✅ 事件绑定成功')

  function getDetail(e) {
    const d = e.detail
    return Array.isArray(d) ? d[0] : d
  }

  function getRoomId(detail) {
    if (!detail) return null
    if (detail.room?.roomId) return detail.room.roomId
    if (detail.roomId) return detail.roomId
    if (typeof detail.room === 'string') return detail.room
    return detail.room?._id || detail.room?.id || null
  }

  // fetch-messages
  el.addEventListener('fetch-messages', (e) => {
    const d = getDetail(e)
    let rid = getRoomId(d)
    console.log('[fetch-messages] roomId:', rid)

    if (!rid) rid = chat.currentRoomId.value
    if (rid) forceLoadRoom(rid)
  })

  // send-message
  el.addEventListener('send-message', (e) => {
    const d = getDetail(e)
    if (d) chat.sendMessage(d)
  })

  // room-action-handler（房间列表下拉菜单）
  el.addEventListener('room-action-handler', (e) => {
    const d = getDetail(e)
    const name = d?.action?.name
    const rid = d?.roomId || chat.currentRoomId.value
    if (name === 'roomInfo') panelOpen.value = true
    else if (name === 'leaveRoom') { if (confirm('确定离开？')) { chat.leaveRoom(rid); showToast('已离开') } }
    else if (name === 'closeRoom') { if (confirm('确定关闭？')) { chat.closeRoom(rid); showToast('已关闭') } }
  })

  // ★ room-info（点击聊天区 header 打开房间信息面板）
  el.addEventListener('room-info', () => {
    console.log('[room-info] 触发，打开房间信息面板')
    panelOpen.value = true
  })

  // add-room → 打开 SideDrawer
  el.addEventListener('add-room', () => {
    drawerOpen.value = true
  })

  el.addEventListener('toggle-rooms-list', () => {})

  // ★ 统一轮询：每 300ms 检查一次，如果有选中房间但没消息就加载
  // 持续 5 秒后自动停止
  let checkCount = 0
  const checker = setInterval(() => {
    checkCount++
    if (checkCount > 16) { clearInterval(checker); return }  // 5秒后停止

    const rid = el.getAttribute('room-id')
    if (rid && rid !== '' && chat.messages.value.length === 0 && !chat.messagesLoaded.value) {
      console.log('[FlashChat] ★ 轮询检测到需加载:', rid, '第', checkCount, '次')
      forceLoadRoom(rid)
      clearInterval(checker)  // 加载了就停止
    }
  }, 300)

  // ================================================================
  //  ★ 修复刷新后转圈：MutationObserver 监听 room-id 属性变化
  // ================================================================
  roomObserver = new MutationObserver(() => {
    const rid = el.getAttribute('room-id')
    if (rid && rid !== '' && chat.messages.value.length === 0 && !chat.messagesLoaded.value) {
      console.log('[FlashChat] ★ Observer 检测到房间需加载:', rid)
      forceLoadRoom(rid)
    }
  })
  roomObserver.observe(el, { attributes: true, attributeFilter: ['room-id'] })

  // ★ 兜底：2秒后检查，如果有选中房间但没消息且不在加载中，强制加载
  setTimeout(() => {
    if (chat.messages.value.length === 0 && !chat.messagesLoaded.value) {
      const rid = chat.currentRoomId.value || el.getAttribute('room-id')
      if (rid && rid !== '') {
        console.log('[FlashChat] ★ 兜底加载 roomId:', rid)
        forceLoadRoom(rid)
      }
    }
  }, 300)
}

// ---- 过期提醒 ----
function checkExpiry() {
  const raw = curRoomRaw.value
  if (!raw?.expireTime) return
  const remain = new Date(raw.expireTime).getTime() - Date.now()
  if (remain > 0 && remain <= 5 * 60000) {
    const mins = Math.ceil(remain / 60000)
    if (!chat.messages.value.some(m => m._id?.startsWith('sys-expiry'))) {
      chat.messages.value = [...chat.messages.value, {
        _id: `sys-expiry-${Date.now()}`, system: true,
        senderId: 'system',
        content: `⚠️ 房间将在 ${mins} 分钟后到期`, date: '', timestamp: ''
      }]
    }
  }
}

// ---- actions ----
function onDrawerAction(act) {
  drawerOpen.value = false
  if (act === 'create') createDlg.value = true
  else if (act === 'join') joinDlg.value = true
  else if (act === 'profile') showToast('个人资料功能开发中')
  else if (act === 'settings') showToast('设置功能开发中')
}

async function doCreateRoom(data) {
  try {
    await chat.createRoom({ ...data, accountId: auth.identity.value?.accountId })
    createDlg.value = false; showToast('创建成功', 'success')
  } catch (e) { showToast(e.message || '创建失败', 'error') }
}

async function doJoinRoom(rid) {
  try {
    await chat.joinRoom(rid); joinDlg.value = false; showToast('加入成功', 'success')
  } catch (e) { showToast(e.message || '加入失败', 'error') }
}

function doLeaveFromPanel() {
  const rid = chat.currentRoomId.value
  if (rid) { chat.leaveRoom(rid); panelOpen.value = false; showToast('已离开') }
}

function doCloseFromPanel() {
  const rid = chat.currentRoomId.value
  if (rid) { chat.closeRoom(rid); panelOpen.value = false; showToast('已关闭') }
}

// ---- init ----
function updateH() { viewH.value = `${window.innerHeight}px` }

async function doInit() {
  initFailed.value = false
  try {
    await auth.init()
    const aid = auth.identity.value?.accountId
    if (!aid) return

    ws.on(WS_TYPE.LOGIN_SUCCESS, d => { auth.setMemberId(d.userId); chat.loadRooms() })
    ws.on(WS_TYPE.CHAT_BROADCAST, chat.onChatBroadcast)
    ws.on(WS_TYPE.USER_JOIN, chat.onUserJoin)
    ws.on(WS_TYPE.USER_LEAVE, chat.onUserLeave)
    ws.on(WS_TYPE.YOU_MUTED, () => { chat.onYouMuted(); showToast('你被禁言了', 'warning') })
    ws.on(WS_TYPE.YOU_UNMUTED, () => { chat.onYouUnmuted(); showToast('禁言已解除') })
    ws.on(WS_TYPE.YOU_KICKED, (d, r) => { chat.onYouKicked(d, r); showToast('你被踢出', 'error') })
    ws.on(WS_TYPE.ROOM_EXPIRING, (d, r) => { chat.onRoomExpiring(d, r); showToast('房间即将到期', 'warning') })
    ws.on(WS_TYPE.ROOM_GRACE, chat.onRoomGrace)
    ws.on(WS_TYPE.ROOM_CLOSED, (d, r) => { chat.onRoomClosed(d, r); showToast('房间已关闭') })
    ws.on(WS_TYPE.SYSTEM_MSG, d => { if (typeof d === 'string') showToast(d) })
    ws.on(WS_TYPE.MSG_REJECTED, d => showToast(typeof d === 'string' ? d : '消息被拒绝', 'error'))
    ws.on(WS_TYPE.USER_ONLINE, chat.onUserOnline)
    ws.on(WS_TYPE.USER_OFFLINE, chat.onUserOffline)

    ws.connect(aid)
  } catch (e) {
    console.error('[FlashChat] 初始化失败', e)
    initFailed.value = true
    showToast('初始化失败', 'error')
  }
}

function retryInit() { doInit() }

// ---- lifecycle ----
onMounted(async () => {
  updateH(); window.addEventListener('resize', updateH)
  cdTimer = setInterval(() => chat.refreshRoomCountdowns(), 60000)
  expiryTimer = setInterval(checkExpiry, 20000)

  await doInit()
  await nextTick()

  const wid = setInterval(() => {
    if (chatEl.value) {
      bindEvents()
      injectShadowCSS()
      clearInterval(wid)
    }
  }, 100)
  setTimeout(() => clearInterval(wid), 8000)
})

onUnmounted(() => {
  window.removeEventListener('resize', updateH)
  clearInterval(cdTimer); clearInterval(expiryTimer)
  if (roomObserver) roomObserver.disconnect()
  ws.disconnect()
})
</script>

<style>
@import '@/styles/variables.css';
</style>

<style scoped>
.fc-root {
  width: 100%; height: 100vh; background: #F5F0E8;
  position: relative; overflow: hidden;
}
.fc-loading {
  width: 100%; height: 100%; display: flex; flex-direction: column;
  align-items: center; justify-content: center; gap: 16px;
}
.fc-pulse {
  width: 52px; height: 52px; border-radius: 50%; background: #F5F0E8;
  box-shadow: 6px 6px 12px #D1CBC3, -6px -6px 12px #FFFFFF;
  position: relative; animation: pulse 1.5s ease-in-out infinite;
}
.fc-pulse::after {
  content: '⚡'; position: absolute; inset: 0;
  display: flex; align-items: center; justify-content: center; font-size: 24px;
}
@keyframes pulse {
  0%,100% { box-shadow: 3px 3px 6px #D1CBC3, -3px -3px 6px #fff; }
  50% { box-shadow: 8px 8px 16px #D1CBC3, -8px -8px 16px #fff; }
}
.fc-load-text { font-family: var(--fc-font); font-size: 14px; color: #8A857E; }
.fc-danger { color: #D4736C; font-weight: 600; }
.fc-load-fail { display: flex; flex-direction: column; align-items: center; gap: 6px; }
.fc-load-hint { font-family: var(--fc-font); font-size: 12px; color: #B5B0A8; }
.fc-retry {
  margin-top: 12px; padding: 10px 28px; border: none; border-radius: 10px;
  background: #C8956C; color: white; font-family: var(--fc-font);
  font-size: 14px; font-weight: 600; cursor: pointer;
  box-shadow: 3px 3px 6px rgba(200,149,108,0.3), -2px -2px 4px rgba(255,255,255,0.6);
}
.fc-retry:hover { filter: brightness(1.05); }

.fc-toast {
  position: fixed; top: 20px; left: 50%; transform: translateX(-50%);
  padding: 10px 24px; border-radius: 999px; font-family: var(--fc-font);
  font-size: 13px; font-weight: 500; z-index: 10000; pointer-events: none;
  box-shadow: 4px 4px 10px rgba(0,0,0,0.1);
}
.fc-toast-info    { background: #F5F0E8; color: #2C2825; }
.fc-toast-success { background: #E8F5E3; color: #3D6B35; }
.fc-toast-warning { background: #FFF3E0; color: #8B6914; }
.fc-toast-error   { background: #FDECEA; color: #8B3A35; }

.toast-enter-active, .toast-leave-active { transition: all .3s ease; }
.toast-enter-from { opacity: 0; transform: translateX(-50%) translateY(-16px); }
.toast-leave-to   { opacity: 0; transform: translateX(-50%) translateY(-16px); }
</style>