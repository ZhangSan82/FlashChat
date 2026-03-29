<template>
  <div class="fc-root">
    <div v-if="!authReady" class="fc-loading">
      <div class="fc-pulse"></div>
      <p v-if="!initFailed" class="fc-load-text">正在连接 FlashChat...</p>
      <div v-else class="fc-load-fail">
        <p class="fc-load-text fc-danger">连接失败</p>
        <p class="fc-load-hint">请确认后端服务已启动（8081 端口）</p>
        <button class="fc-retry" @click="retryInit">重新连接</button>
      </div>
    </div>

    <div v-else class="fc-shell" :style="{ height: viewH }">
      <div class="fc-shell-glow fc-shell-glow-a"></div>
      <div class="fc-shell-glow fc-shell-glow-b"></div>
      <div class="fc-shell-noise"></div>
      <vue-advanced-chat
          ref="chatEl"
          height="100%"
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
          :message-actions="messageActionsConfigStr"
          :accepted-files="acceptFiles"
          :styles="themeStr"
          room-info-enabled="true"
      />
    </div>

    <!-- 侧滑菜单 -->
    <SideDrawer
        :visible="drawerOpen"
        :nickname="auth.identity.value?.nickname || ''"
        :account-id="auth.identity.value?.accountId || ''"
        :avatar-color="auth.identity.value?.avatarColor || '#C8956C'"
        :avatar-url="auth.identity.value?.avatarUrl || ''"
        @close="drawerOpen = false"
        @action="onDrawerAction"
    />

    <!-- 房间信息面板 -->
    <RoomInfoDeck
        :visible="panelOpen"
        :room="curRoomRaw"
        :members="curMembers"
        :is-host="chat.isCurrentUserHost(chat.currentRoomId.value)"
        @close="panelOpen = false"
        @leave="doLeaveFromPanel"
        @close-room="doCloseFromPanel"
        @extend-room="extendDlg = true"
        @resize-room="resizeDlg = true"
    />

    <!-- ★ 个人资料面板 -->
    <ProfilePanel
        :visible="profileOpen"
        @close="profileOpen = false"
        @set-password="openPasswordDialog('set')"
        @change-password="openPasswordDialog('change')"
        @upgrade="profileOpen = false; upgradeDlg = true"
        @logout="doLogout"
        @delete-account="doDeleteAccount"
        @profile-updated="onProfileUpdated"
    />

    <!-- ★ 账号升级弹窗 -->
    <UpgradeDialog
        :visible="upgradeDlg"
        @upgraded="onUpgraded"
        @close="upgradeDlg = false"
    />

    <!-- ★ 密码弹窗 -->
    <PasswordDialog
        :visible="passwordDlg"
        :mode="passwordMode"
        @success="onPasswordSuccess"
        @close="passwordDlg = false"
    />

    <!-- 创建/加入房间弹窗 -->
    <RoomCreateSheet :visible="createDlg" @create="doCreateRoom" @close="createDlg = false" />
    <JoinRoomDialog :visible="joinDlg" @join="doJoinRoom" @close="joinDlg = false" />
    <RoomExtendSheet :visible="extendDlg" :room="curRoomRaw" @extend="doExtendRoom" @close="extendDlg = false" />
    <RoomResizeSheet :visible="resizeDlg" :room="curRoomRaw" @resize="doResizeRoom" @close="resizeDlg = false" />

    <Teleport to="body">
      <transition name="confirm">
        <div v-if="legacyDeleteConfirmVisible" class="fc-confirm-mask" @click.self="closeDeleteConfirm">
          <div class="fc-confirm-card">
            <div class="fc-confirm-kicker">Message Action</div>
            <div class="fc-confirm-title">删除这条消息？</div>
            <p class="fc-confirm-text">房主删除后，这条消息会从当前房间里移除，其他成员也会同步看不到。</p>
            <div v-if="deleteConfirm.message" class="fc-confirm-preview">
              {{ deleteConfirm.message.content || '[文件消息]' }}
            </div>
            <div class="fc-confirm-actions">
              <button class="fc-confirm-btn fc-confirm-btn-ghost" type="button" :disabled="deleteConfirm.pending" @click="closeDeleteConfirm">
                取消
              </button>
              <button class="fc-confirm-btn fc-confirm-btn-danger" type="button" :disabled="deleteConfirm.pending" @click="confirmDeleteMessage">
                {{ deleteConfirm.pending ? '删除中...' : '确认删除' }}
              </button>
            </div>
          </div>
        </div>
      </transition>
    </Teleport>

    <Teleport to="body">
      <transition name="confirm">
        <div v-if="deleteConfirm.visible" class="fc-confirm-mask" @click.self="closeDeleteConfirm">
          <div class="fc-confirm-card">
            <div class="fc-confirm-kicker">Message Action</div>
            <div class="fc-confirm-title">{{ deleteConfirmTitle }}</div>
            <p class="fc-confirm-text">{{ deleteConfirmText }}</p>
            <div v-if="deleteConfirm.message" class="fc-confirm-preview">
              {{ deleteConfirm.message.content || '[文件消息]' }}
            </div>
            <div class="fc-confirm-actions">
              <button class="fc-confirm-btn fc-confirm-btn-ghost" type="button" :disabled="deleteConfirm.pending" @click="closeDeleteConfirm">
                取消
              </button>
              <button class="fc-confirm-btn fc-confirm-btn-danger" type="button" :disabled="deleteConfirm.pending" @click="confirmDeleteMessage">
                {{ deleteConfirm.pending ? '处理中...' : deleteConfirmButtonText }}
              </button>
            </div>
          </div>
        </div>
      </transition>
    </Teleport>

    <!-- Toast -->
    <transition name="toast">
      <div v-if="toast.show" :class="['fc-toast', `fc-toast-${toast.type}`]">{{ toast.msg }}</div>
    </transition>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { register } from 'vue-advanced-chat'
register()

import SideDrawer from './SideDrawer.vue'
import RoomInfoDeck from './RoomInfoDeck.vue'
import RoomCreateSheet from './RoomCreateSheet.vue'
import RoomExtendSheet from './RoomExtendSheet.vue'
import RoomResizeSheet from './RoomResizeSheet.vue'
import JoinRoomDialog from './JoinRoomDialog.vue'
// ★ 新增组件导入
import ProfilePanel from './ProfilePanel.vue'
import UpgradeDialog from './UpgradeDialog.vue'
import PasswordDialog from './PasswordDialog.vue'

import { useAuth } from '@/composables/useAuth'
import { useWebSocket, WS_TYPE } from '@/composables/useWebSocket'
import { useChat } from '@/composables/useChat'
import { deleteAccount as apiDeleteAccount } from '@/api/account'
import { extendRoom as apiExtendRoom, resizeRoom as apiResizeRoom } from '@/api/room'

const router = useRouter()
const auth = useAuth()
const ws = useWebSocket()
const chat = useChat(
    () => auth.memberId.value,
    () => auth.identity.value  // { nickname, avatarColor, avatarUrl, ... }
)

const authReady = computed(() => auth.isReady.value)
const myId = computed(() => String(auth.memberId.value || ''))

const drawerOpen = ref(false)
const panelOpen = ref(false)
const createDlg = ref(false)
const joinDlg = ref(false)
const extendDlg = ref(false)
const resizeDlg = ref(false)
const viewH = ref('100vh')
const chatEl = ref(null)
const initFailed = ref(false)
const curMembers = ref([])

// ★ 新增状态
const profileOpen = ref(false)
const upgradeDlg = ref(false)
const passwordDlg = ref(false)
const passwordMode = ref('set')
const deleteConfirm = ref({
  visible: false,
  pending: false,
  roomId: '',
  message: null,
  mode: 'self'
})
const legacyDeleteConfirmVisible = computed(() => false)

let bound = false
let cdTimer = null
let expiryTimer = null
let roomLoadSeq = 0
let typingIdleTimer = null
let typingRoomId = null

const curRoomRaw = computed(() =>
    chat.roomsRaw.value.find(r => r.roomId === chat.currentRoomId.value) || null
)

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
const messageActionsStr = computed(() => JSON.stringify([
  { name: 'replyMessage', title: '回复' },
  { name: 'recallMessage', title: '撤回（仅自己）' },
  ...(chat.isCurrentUserHost(chat.currentRoomId.value) ? [{ name: 'deleteMessageOwner', title: '删除（房主）' }] : [])
]))
const messageActionsConfigStr = computed(() => JSON.stringify([
  { name: 'replyMessage', title: '回复' },
  { name: 'recallMessage', title: '撤回（仅自己）' },
  { name: 'deleteMessageSelf', title: '删除（仅自己）' },
  ...(chat.isCurrentUserHost(chat.currentRoomId.value) ? [{ name: 'deleteMessageAll', title: '删除（所有人）' }] : [])
]))
const deleteConfirmTitle = computed(() =>
  deleteConfirm.value.mode === 'all' ? '为所有人删除这条消息？' : '为自己删除这条消息？'
)
const deleteConfirmText = computed(() =>
  deleteConfirm.value.mode === 'all'
    ? '删除后，这条消息会从当前房间中移除，其他成员也会同步看不到。'
    : '这次只会在你的当前设备上隐藏，房间里的其他成员仍然可以看到。'
)
const deleteConfirmButtonText = computed(() =>
  deleteConfirm.value.mode === 'all' ? '确认为所有人删除' : '确认仅自己删除'
)
const acceptFiles = 'image/*,audio/*,video/*,application/pdf,text/plain'

const themeStr = JSON.stringify({
  general: { color: '#201813', colorSpinner: '#AD7A44', borderStyle: 'none', backgroundInput: '#F7EFE4', colorPlaceholder: '#A89482', colorCaret: '#8C5A2B', backgroundScrollIcon: 'rgba(255,250,243,0.94)' },
  container: { border: '1px solid rgba(77,52,31,0.08)', borderRadius: '28px', boxShadow: '0 18px 44px rgba(61,40,22,0.12)' },
  header: { background: 'rgba(255,250,243,0.92)', colorRoomName: '#201813', colorRoomInfo: '#6B5B4B' },
  footer: { background: 'rgba(255,250,243,0.94)', backgroundReply: '#F3E7D7' },
  sidenav: { background: 'rgba(244,234,220,0.74)', backgroundHover: 'rgba(255,250,243,0.82)', backgroundActive: '#FFFAF3', colorActive: '#201813', borderColorSearch: 'rgba(77,52,31,0.12)' },
  content: { background: 'linear-gradient(180deg,#FBF7F1 0%,#F7EFE4 100%)' },
  message: { background: 'rgba(255,255,255,0.94)', backgroundMe: '#F2E1CB', color: '#201813', colorStarted: '#A89482', backgroundDeleted: '#F0E3D5', colorDeleted: '#8F7E6E', colorUsername: '#8C5A2B', colorTimestamp: '#A89482', backgroundDate: 'rgba(255,250,243,0.92)', colorDate: '#6B5B4B', backgroundSystem: 'transparent', colorSystem: '#7D6C5C', colorNewMessages: '#8C5A2B', backgroundReply: 'rgba(173,122,68,0.10)', colorReplyUsername: '#8C5A2B', backgroundImage: '#EFE1D0' },
  room: { colorUsername: '#201813', colorMessage: '#7A6959', colorTimestamp: '#8F7E6E', colorStateOnline: '#6F9B6B', colorStateOffline: '#B1A08F', backgroundCounterBadge: '#8C5A2B', colorCounterBadge: '#FFFAF3' },
  emoji: { background: '#FFFAF3' },
  icons: { search: '#7A6959', add: '#8C5A2B', toggle: '#7A6959', menu: '#7A6959', close: '#7A6959', file: '#8C5A2B', paperclip: '#7A6959', send: '#8C5A2B', sendDisabled: '#B9AA99', emoji: '#7A6959', document: '#8C5A2B', checkmark: '#8C5A2B', checkmarkSeen: '#8C5A2B', eye: '#7A6959', dropdownMessage: '#7A6959', dropdownRoom: '#7A6959', dropdownScroll: '#8C5A2B', microphone: '#7A6959', audioPlay: '#8C5A2B', audioPause: '#8C5A2B', audioCancel: '#BB6A5E', audioConfirm: '#6F9B6B' }
})

// ---- toast ----
const toast = ref({ show: false, msg: '', type: 'info' })
let toastT = null
function showToast(m, t = 'info', d = 3000) {
  toast.value = { show: true, msg: m, type: t }
  clearTimeout(toastT)
  toastT = setTimeout(() => { toast.value.show = false }, d)
}

// ---- Shadow DOM CSS ----
function injectShadowCSS() {
  const el = chatEl.value
  if (!el?.shadowRoot) return
  if (el.shadowRoot.querySelector('#fc-injected')) return
  const style = document.createElement('style')
  style.id = 'fc-injected'
  style.textContent = `
    .vac-card-window{background:rgba(255,250,243,0.62)!important;backdrop-filter:blur(18px)!important}
    .vac-room-header,.vac-box-search,.vac-room-footer{backdrop-filter:blur(18px)!important}
    .vac-room-item{border-radius:18px!important;margin:6px 10px!important;border:1px solid transparent!important;background:transparent!important;box-shadow:none!important;transition:transform .22s ease,background .22s ease,border-color .22s ease,box-shadow .22s ease!important}
    .vac-room-item:hover{background:rgba(255,250,243,0.78)!important;border-color:rgba(77,52,31,0.08)!important;transform:translateX(2px)!important}
    .vac-room-item.vac-room-selected,.vac-rooms-container .vac-room-selected,[class*="room-selected"]{background:#fffaf3!important;border-color:rgba(77,52,31,0.16)!important;box-shadow:0 16px 30px rgba(61,40,22,0.10)!important}
    .vac-rooms-container .vac-room-header input{background:rgba(255,250,243,0.78)!important;border:1px solid rgba(77,52,31,0.12)!important;border-radius:18px!important;box-shadow:none!important}
    .vac-room-header .vac-add-icon{border-radius:50%!important;background:rgba(255,250,243,0.82)!important;border:1px solid rgba(77,52,31,0.10)!important;padding:6px!important}
    .vac-room-footer .vac-box-footer{background:rgba(255,250,243,0.94)!important;border:1px solid rgba(77,52,31,0.10)!important;border-radius:22px!important;box-shadow:0 14px 30px rgba(61,40,22,0.08)!important}
    .vac-message-wrapper .vac-message-box{max-width:78%!important;line-height:1.2!important;margin-bottom:9px!important}
    .vac-message-wrapper .vac-avatar{margin:0 0 20px!important}
    .vac-message-wrapper .vac-message-container{padding:4px 10px!important;min-width:auto!important;overflow:visible!important}
    .vac-message-wrapper .vac-message-container-offset{margin-top:5px!important}
    .vac-message-wrapper .vac-message-card{position:relative!important;min-width:138px!important;border-radius:24px!important;border:1px solid rgba(77,52,31,0.08)!important;box-shadow:0 12px 24px rgba(61,40,22,0.07)!important;padding:8px 48px 10px 16px!important}
    .vac-message-wrapper .vac-message-box:not(.vac-offset-current) .vac-message-card::before{content:''!important;position:absolute!important;left:-7px!important;bottom:10px!important;width:16px!important;height:18px!important;background:rgba(255,255,255,0.94)!important;border-left:1px solid rgba(77,52,31,0.08)!important;border-bottom:1px solid rgba(77,52,31,0.08)!important;border-bottom-left-radius:14px!important;transform:skewY(26deg) rotate(7deg)!important;box-shadow:-3px 6px 12px rgba(61,40,22,0.04)!important}
    .vac-message-wrapper .vac-message-card.vac-message-current{background:linear-gradient(180deg,#f6e5ce 0%,#edd6b8 100%)!important;border-color:rgba(140,90,43,0.18)!important}
    .vac-message-wrapper .vac-message-box.vac-offset-current .vac-message-card::before{content:''!important;position:absolute!important;left:auto!important;right:-7px!important;bottom:10px!important;width:16px!important;height:18px!important;background:#edd6b8!important;border-left:none!important;border-right:1px solid rgba(140,90,43,0.18)!important;border-bottom:1px solid rgba(140,90,43,0.18)!important;border-bottom-left-radius:0!important;border-bottom-right-radius:14px!important;transform:skewY(-26deg) rotate(-7deg)!important;box-shadow:3px 6px 12px rgba(140,90,43,0.06)!important}
    .vac-message-wrapper .vac-message-card:not(.vac-message-current){background:rgba(255,255,255,0.90)!important}
    .vac-message-wrapper .vac-format-message-wrapper,.vac-message-wrapper .vac-format-container{display:block!important;font-size:14px!important;line-height:1.22!important;margin-top:2px!important}
    .vac-message-wrapper .vac-text-username{font-size:12px!important;font-weight:600!important;letter-spacing:.01em!important;line-height:1.02!important;margin-bottom:5px!important}
    .vac-message-wrapper .vac-text-timestamp{position:absolute!important;right:15px!important;bottom:9px!important;font-size:10px!important;line-height:1!important;margin-top:0!important;display:flex!important;align-items:center!important;gap:3px!important}
    .vac-message-wrapper .vac-icon-edited{margin:0!important}
    .vac-message-wrapper .vac-reply-message{margin:0 -4px 6px!important;padding:5px 10px!important;border-radius:14px!important}
    .vac-message-wrapper .vac-reply-message .vac-reply-username{font-size:11px!important;line-height:1.08!important;margin-bottom:2px!important}
    .vac-message-wrapper .vac-reply-message .vac-reply-content{font-size:11px!important;line-height:1.25!important}
    @media only screen and (max-width: 768px){.vac-message-wrapper .vac-message-box{max-width:88%!important;margin-bottom:7px!important}.vac-message-wrapper .vac-avatar{margin:0 0 11px!important}.vac-message-wrapper .vac-message-container{padding:4px 5px!important}.vac-message-wrapper .vac-message-card{min-width:122px!important;padding:7px 42px 9px 13px!important;border-radius:22px!important}.vac-message-wrapper .vac-message-box:not(.vac-offset-current) .vac-message-card::before{left:-6px!important;bottom:9px!important;width:14px!important;height:16px!important}.vac-message-wrapper .vac-message-box.vac-offset-current .vac-message-card::before{right:-6px!important;bottom:9px!important;width:14px!important;height:16px!important}.vac-message-wrapper .vac-text-timestamp{right:13px!important;bottom:8px!important}}
    .vac-card-date{border-radius:999px!important;border:1px solid rgba(77,52,31,0.10)!important;box-shadow:none!important}
    ::-webkit-scrollbar{width:5px}::-webkit-scrollbar-thumb{background:rgba(140,90,43,0.26);border-radius:999px}::-webkit-scrollbar-track{background:transparent}
  `
  el.shadowRoot.appendChild(style)
}

async function forceLoadRoom(rid, options = {}) {
  if (!rid || rid === '') return
  if (typingRoomId && typingRoomId !== rid) {
    stopTyping(typingRoomId)
  }
  const isReset = options.reset === true
  const requestId = isReset ? ++roomLoadSeq : roomLoadSeq
  if (isReset) curMembers.value = []

  const [members] = await Promise.all([
    chat.loadRoomUsers(rid),
    chat.loadMessages(rid, { reset: isReset })
  ])

  if ((isReset && requestId !== roomLoadSeq) || chat.currentRoomId.value !== rid) return
  curMembers.value = members || []
  chat.refreshRoomCountdowns()
}

function stopTyping(roomId = typingRoomId) {
  clearTimeout(typingIdleTimer)
  typingIdleTimer = null
  if (!roomId) return
  ws.sendTyping(roomId, false)
  if (typingRoomId === roomId) {
    typingRoomId = null
  }
}

function handleTyping(detail) {
  const rid = detail?.roomId || chat.currentRoomId.value
  if (!rid) return

  const content = typeof detail?.message === 'string' ? detail.message : ''
  if (!content.trim()) {
    stopTyping(rid)
    return
  }

  if (typingRoomId !== rid) {
    if (typingRoomId) stopTyping(typingRoomId)
    ws.sendTyping(rid, true)
    typingRoomId = rid
  }

  clearTimeout(typingIdleTimer)
  typingIdleTimer = setTimeout(() => {
    stopTyping(rid)
  }, 2000)
}

function legacyOpenDeleteConfirm(message, roomId) {
  deleteConfirm.value = {
    visible: true,
    pending: false,
    roomId,
    message
  }
}

function legacyCloseDeleteConfirm() {
  if (deleteConfirm.value.pending) return
  deleteConfirm.value = {
    visible: false,
    pending: false,
    roomId: '',
    message: null
  }
}

async function legacyConfirmDeleteMessage() {
  const { roomId, message } = deleteConfirm.value
  if (!roomId || !message) {
    closeDeleteConfirm()
    return
  }

  deleteConfirm.value = {
    ...deleteConfirm.value,
    pending: true
  }

  try {
    await chat.deleteMessage(message, roomId)
    closeDeleteConfirm()
    showToast('已删除消息', 'success')
  } catch (error) {
    deleteConfirm.value = {
      ...deleteConfirm.value,
      pending: false
    }
    showToast(error?.message || '删除失败', 'error')
  }
}

// ---- 事件绑定 ----
function openDeleteConfirm(message, roomId, mode = 'self') {
  deleteConfirm.value = {
    visible: true,
    pending: false,
    roomId,
    message,
    mode
  }
}

function closeDeleteConfirm(force = false) {
  if (deleteConfirm.value.pending && !force) return
  deleteConfirm.value = {
    visible: false,
    pending: false,
    roomId: '',
    message: null,
    mode: 'self'
  }
}

async function confirmDeleteMessage() {
  const { roomId, message, mode } = deleteConfirm.value
  if (!roomId || !message) {
    closeDeleteConfirm(true)
    return
  }

  deleteConfirm.value = {
    ...deleteConfirm.value,
    pending: true
  }

  try {
    if (mode === 'all') {
      await chat.deleteMessageForAll(message, roomId)
      showToast('已为所有人删除消息', 'success')
    } else {
      chat.hideMessageForSelf(message, roomId)
      showToast('已为自己删除消息', 'success')
    }
    closeDeleteConfirm(true)
  } catch (error) {
    deleteConfirm.value = {
      ...deleteConfirm.value,
      pending: false
    }
    showToast(error?.message || '删除失败', 'error')
  }
}

function bindEvents() {
  if (bound) return
  const el = chatEl.value
  if (!el) return
  bound = true

  function getDetail(e) { const d = e.detail; return Array.isArray(d) ? d[0] : d }
  function getRoomId(detail) {
    if (!detail) return null
    if (detail.room?.roomId) return detail.room.roomId
    if (detail.roomId) return detail.roomId
    if (typeof detail.room === 'string') return detail.room
    return detail.room?._id || detail.room?.id || null
  }

  el.addEventListener('fetch-messages', (e) => {
    const d = getDetail(e); let rid = getRoomId(d)
    if (!rid) rid = chat.currentRoomId.value
    if (rid) forceLoadRoom(rid, { reset: d?.options?.reset === true })
  })
  el.addEventListener('send-message', async (e) => {
    const d = getDetail(e)
    if (!d) return
    const sent = await chat.sendMessage(d)
    if (sent) stopTyping(d.roomId || chat.currentRoomId.value)
  })
  el.addEventListener('room-action-handler', (e) => {
    const d = getDetail(e); const name = d?.action?.name; const rid = d?.roomId || chat.currentRoomId.value
    if (name === 'roomInfo') panelOpen.value = true
    else if (name === 'leaveRoom') { if (confirm('确定离开？')) { chat.leaveRoom(rid); showToast('已离开') } }
    else if (name === 'closeRoom') { if (confirm('确定关闭？')) { chat.closeRoom(rid); showToast('已关闭') } }
  })
  el.addEventListener('message-action-handler', async (e) => {
    const d = getDetail(e)
    const name = d?.action?.name
    const rid = d?.roomId || chat.currentRoomId.value
    const message = d?.message

    if (!name || !rid || !message || name === 'replyMessage') return

    try {
      if (name === 'recallMessage') {
        await chat.recallMessage(message, rid)
        showToast('已撤回消息', 'success')
      } else if (name === 'deleteMessageOwner') {
        openDeleteConfirm(message, rid)
      }
    } catch (error) {
      showToast(error?.message || '操作失败', 'error')
    }
  })
  el.addEventListener('message-action-handler', (e) => {
    const d = getDetail(e)
    const name = d?.action?.name
    const rid = d?.roomId || chat.currentRoomId.value
    const message = d?.message

    if (!rid || !message) return
    if (name === 'deleteMessageSelf') {
      openDeleteConfirm(message, rid, 'self')
    } else if (name === 'deleteMessageAll') {
      openDeleteConfirm(message, rid, 'all')
    }
  })
  el.addEventListener('typing-message', (e) => {
    handleTyping(getDetail(e))
  })
  el.addEventListener('room-info', () => { panelOpen.value = true })
  el.addEventListener('add-room', () => { drawerOpen.value = true })
  el.addEventListener('toggle-rooms-list', () => {})

  const initialRoomId = chat.currentRoomId.value || el.getAttribute('room-id')
  if (initialRoomId && chat.messages.value.length === 0) {
    forceLoadRoom(initialRoomId, { reset: true })
  }
}

function checkExpiry() {
  const raw = curRoomRaw.value
  if (!raw?.expireTime) return
  const remain = new Date(raw.expireTime).getTime() - Date.now()
  if (remain > 0 && remain <= 5 * 60000) {
    const mins = Math.ceil(remain / 60000)
    if (!chat.messages.value.some(m => m._id?.startsWith('sys-expiry'))) {
      chat.messages.value = [...chat.messages.value, {
        _id: `sys-expiry-${Date.now()}`, system: true, senderId: 'system',
        content: `\u26A0\uFE0F 房间将在 ${mins} 分钟后到期`, date: '', timestamp: ''
      }]
    }
  }
}

// ================================================================
// ★ 操作处理
// ================================================================

function onDrawerAction(act) {
  drawerOpen.value = false
  if (act === 'create') createDlg.value = true
  else if (act === 'join') joinDlg.value = true
  else if (act === 'public') router.push('/room/public')
  else if (act === 'credits') router.push('/credits')
  else if (act === 'invites') router.push('/invites')
  else if (act === 'profile') profileOpen.value = true
  else if (act === 'logout') doLogout()
}

function applyRoomRawUpdate(roomId, patch = {}) {
  const room = chat.roomsRaw.value.find(item => item.roomId === roomId)
  if (!room) return null
  Object.assign(room, patch)
  chat.refreshRoomCountdowns()
  return room
}

async function doCreateRoom(data) {
  try {
    const room = await chat.createRoom(data)
    if (room?.roomId) {
      await forceLoadRoom(room.roomId, { reset: true })
    }
    createDlg.value = false
    showToast('创建成功', 'success')
  }
  catch (e) { showToast(e.message || '创建失败', 'error') }
}

async function doJoinRoom(rid) {
  try {
    const room = await chat.joinRoom(rid)
    await forceLoadRoom(room?.roomId || rid, { reset: true })
    joinDlg.value = false
    showToast('加入成功', 'success')
  }
  catch (e) { showToast(e.message || '加入失败', 'error') }
}

function doLeaveFromPanel() {
  const rid = chat.currentRoomId.value
  if (rid) { chat.leaveRoom(rid); panelOpen.value = false; showToast('已离开') }
}

function doCloseFromPanel() {
  const rid = chat.currentRoomId.value
  if (rid) { chat.closeRoom(rid); panelOpen.value = false; showToast('已关闭') }
}

// ★ 密码弹窗
async function doExtendRoom({ roomId, duration, option }) {
  try {
    const resp = await apiExtendRoom({ roomId, duration })
    applyRoomRawUpdate(roomId, {
      expireTime: resp?.newExpireTime || resp?.expireTime,
      status: resp?.status,
      statusDesc: resp?.statusDesc
    })
    chat.onRoomExtended({
      newExpireTime: resp?.newExpireTime || resp?.expireTime,
      durationDesc: option?.desc || '',
      status: resp?.status,
      statusDesc: resp?.statusDesc
    }, roomId)
    extendDlg.value = false
    panelOpen.value = false
    showToast('房间已延期', 'success')
  } catch (error) {
    showToast(error?.message || '延期失败', 'error')
  }
}

async function doResizeRoom({ roomId, newMaxMembers }) {
  try {
    await apiResizeRoom({ roomId, newMaxMembers })
    applyRoomRawUpdate(roomId, { maxMembers: newMaxMembers })
    resizeDlg.value = false
    panelOpen.value = false
    showToast('房间人数上限已更新', 'success')
  } catch (error) {
    showToast(error?.message || '扩容失败', 'error')
  }
}

function openPasswordDialog(mode) {
  profileOpen.value = false
  passwordMode.value = mode
  passwordDlg.value = true
}

function onPasswordSuccess() {
  passwordDlg.value = false
  showToast(passwordMode.value === 'set' ? '密码设置成功' : '密码修改成功', 'success')
}

// ★ 账号升级
function onUpgraded(resp) {
  upgradeDlg.value = false
  auth.onAuthRefreshed(resp)
  showToast('升级成功！已获得注册奖励积分', 'success')

  // 升级后 SaToken loginId 变了（member_X → user_X），需要重连 WS
  ws.disconnect()
  const token = auth.getToken()
  if (token) ws.connect(token)
}

// ★ 登出
async function doLogout() {
  if (!confirm('确定退出登录？')) return
  await auth.logout()
  stopTyping()
  ws.disconnect()
  window.location.reload()
}

// ★ 注销账号
async function doDeleteAccount() {
  if (!confirm('注销后账号将无法恢复，确定要注销吗？')) return
  if (!confirm('再次确认：你的所有数据（房间、消息、积分）将永久丢失')) return
  try {
    await apiDeleteAccount()
    auth.reset()
    stopTyping()
    ws.disconnect()
    window.location.reload()
  } catch (e) {
    showToast(e.message || '注销失败', 'error')
  }
}

// ★ 个人资料更新后刷新当前房间成员
async function onProfileUpdated() {
  const refreshedMembers = await chat.refreshAllRoomUsers()
  if (chat.currentRoomId.value) {
    curMembers.value = refreshedMembers || []
  }
  // 强制组件重新渲染：通过修改 rooms 引用触发
  chat.rooms.value = [...chat.rooms.value]
}

// ---- init ----
function updateH() { viewH.value = `${Math.max(window.innerHeight - 32, 520)}px` }

async function doInit() {
  initFailed.value = false
  try {
    await auth.init()
    const token = auth.getToken()
    if (!token) { initFailed.value = true; return }

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
    ws.on(WS_TYPE.TYPING_STATUS, chat.onTypingStatus)
    ws.on(WS_TYPE.MEMBER_INFO_CHANGED, (d, r) => {
      const refreshedMembers = chat.onMemberInfoChanged(d, r)
      if (r && r === chat.currentRoomId.value) {
        curMembers.value = refreshedMembers || []
      }
    })
    ws.on(WS_TYPE.MSG_RECALLED, chat.onMsgRecalled)
    ws.on(WS_TYPE.MSG_DELETED, chat.onMsgDeleted)
    ws.on(WS_TYPE.ROOM_EXTENDED, (d, r) => {
      chat.onRoomExtended(d, r)
      if (r === chat.currentRoomId.value && d?.durationDesc) {
        showToast(`房间已延期 ${d.durationDesc}`, 'success')
      }
    })
    ws.on(WS_TYPE.MSG_REACTION_UPDATE, chat.onMsgReactionUpdate)

    ws.connect(token)
  } catch (e) {
    console.error('[FlashChat] 初始化失败', e)
    initFailed.value = true
    showToast('初始化失败', 'error')
  }
}

function retryInit() { doInit() }

onMounted(async () => {
  updateH(); window.addEventListener('resize', updateH)
  cdTimer = setInterval(() => chat.refreshRoomCountdowns(), 60000)
  expiryTimer = setInterval(checkExpiry, 20000)
  await doInit()
  await nextTick()
  const wid = setInterval(() => {
    if (chatEl.value) { bindEvents(); injectShadowCSS(); clearInterval(wid) }
  }, 100)
  setTimeout(() => clearInterval(wid), 8000)
})

onUnmounted(() => {
  window.removeEventListener('resize', updateH)
  clearInterval(cdTimer); clearInterval(expiryTimer)
  stopTyping()
  ws.disconnect()
})
</script>

<style>
@import '@/styles/variables.css';
</style>

<style scoped>
.fc-root {
  width: 100%;
  height: 100vh;
  padding: 16px;
  background:
    radial-gradient(circle at 0% 0%, rgba(221, 193, 163, 0.46), transparent 28%),
    radial-gradient(circle at 100% 10%, rgba(173, 122, 68, 0.14), transparent 20%),
    linear-gradient(180deg, #f4ebdd 0%, #e9dccb 100%);
  position: relative;
  overflow: hidden;
}

.fc-root::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image: linear-gradient(rgba(77, 52, 31, 0.018) 1px, transparent 1px), linear-gradient(90deg, rgba(77, 52, 31, 0.018) 1px, transparent 1px);
  background-size: 36px 36px;
  pointer-events: none;
  opacity: 0.5;
}

.fc-shell {
  position: relative;
  border-radius: var(--fc-radius-xl);
  border: 1px solid rgba(77, 52, 31, 0.12);
  background: rgba(255, 250, 243, 0.52);
  box-shadow: var(--fc-shadow-out);
  overflow: hidden;
  isolation: isolate;
}

.fc-shell-glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(12px);
  pointer-events: none;
  z-index: 0;
}

.fc-shell-glow-a {
  width: 280px;
  height: 280px;
  top: -100px;
  right: -80px;
  background: rgba(173, 122, 68, 0.14);
}

.fc-shell-glow-b {
  width: 220px;
  height: 220px;
  bottom: -120px;
  left: -90px;
  background: rgba(221, 193, 163, 0.34);
}

.fc-shell-noise {
  position: absolute;
  inset: 0;
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.30), transparent 48%, rgba(173, 122, 68, 0.04));
  pointer-events: none;
  z-index: 0;
}

.fc-shell > *:last-child {
  position: relative;
  z-index: 1;
}

.fc-loading {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 18px;
}

.fc-pulse {
  width: 62px;
  height: 62px;
  border-radius: 50%;
  border: 1px solid rgba(77, 52, 31, 0.12);
  background: rgba(255, 250, 243, 0.72);
  box-shadow: var(--fc-shadow-soft);
  position: relative;
  animation: pulse 1.8s ease-in-out infinite;
}

.fc-pulse::after {
  content: '\26A1';
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  color: var(--fc-accent-strong);
}

@keyframes pulse {
  0%, 100% { transform: translateY(0); box-shadow: 0 14px 28px rgba(61, 40, 22, 0.10); }
  50% { transform: translateY(-2px); box-shadow: 0 18px 34px rgba(61, 40, 22, 0.16); }
}

.fc-load-text { font-family: var(--fc-font); font-size: 14px; color: var(--fc-text-sec); }
.fc-danger { color: var(--fc-danger); font-weight: 600; }
.fc-load-fail { display: flex; flex-direction: column; align-items: center; gap: 6px; }
.fc-load-hint { font-family: var(--fc-font); font-size: 12px; color: var(--fc-text-muted); }

.fc-retry {
  margin-top: 12px;
  padding: 11px 28px;
  border: 1px solid rgba(77, 52, 31, 0.10);
  border-radius: var(--fc-radius-pill);
  background: linear-gradient(135deg, #b68450 0%, #8c5a2b 100%);
  color: #fffaf3;
  font-family: var(--fc-font);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  box-shadow: 0 16px 28px rgba(140, 90, 43, 0.24);
}

.fc-retry:hover { filter: brightness(1.04); transform: translateY(-1px); }

.fc-toast {
  position: fixed;
  top: 26px;
  left: 50%;
  transform: translateX(-50%);
  padding: 12px 22px;
  border: 1px solid rgba(77, 52, 31, 0.10);
  border-radius: var(--fc-radius-pill);
  font-family: var(--fc-font);
  font-size: 13px;
  font-weight: 500;
  z-index: 10000;
  pointer-events: none;
  backdrop-filter: blur(14px);
  box-shadow: 0 14px 30px rgba(61, 40, 22, 0.14);
}

.fc-toast-info { background: rgba(255, 250, 243, 0.92); color: var(--fc-text); }
.fc-toast-success { background: rgba(235, 245, 230, 0.95); color: #42673f; }
.fc-toast-warning { background: rgba(255, 242, 224, 0.96); color: #8b641c; }
.fc-toast-error { background: rgba(253, 236, 234, 0.96); color: #8b3a35; }
.toast-enter-active, .toast-leave-active { transition: all .3s ease; }
.toast-enter-from { opacity: 0; transform: translateX(-50%) translateY(-16px); }
.toast-leave-to { opacity: 0; transform: translateX(-50%) translateY(-16px); }

.fc-confirm-mask {
  position: fixed;
  inset: 0;
  background: rgba(41, 30, 18, 0.28);
  backdrop-filter: blur(10px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  z-index: 10020;
}

.fc-confirm-card {
  width: min(460px, 100%);
  padding: 24px;
  border-radius: 26px;
  border: 1px solid rgba(77, 52, 31, 0.12);
  background: linear-gradient(180deg, rgba(255, 250, 243, 0.98), rgba(247, 239, 228, 0.98));
  box-shadow: 0 24px 50px rgba(61, 40, 22, 0.18);
}

.fc-confirm-kicker {
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.fc-confirm-title {
  margin-top: 10px;
  font-family: var(--fc-font);
  font-size: 26px;
  font-weight: 700;
  color: var(--fc-text);
}

.fc-confirm-text {
  margin-top: 10px;
  font-family: var(--fc-font);
  font-size: 14px;
  line-height: 1.6;
  color: var(--fc-text-sec);
}

.fc-confirm-preview {
  margin-top: 16px;
  padding: 14px 16px;
  border-radius: 18px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  background: rgba(255, 255, 255, 0.72);
  font-family: var(--fc-font);
  font-size: 14px;
  color: var(--fc-text);
  word-break: break-word;
}

.fc-confirm-actions {
  margin-top: 22px;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.fc-confirm-btn {
  min-width: 108px;
  padding: 12px 18px;
  border-radius: 999px;
  border: 1px solid rgba(77, 52, 31, 0.10);
  font-family: var(--fc-font);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: transform .2s ease, filter .2s ease, opacity .2s ease;
}

.fc-confirm-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.fc-confirm-btn-ghost {
  background: rgba(255, 250, 243, 0.9);
  color: var(--fc-text);
}

.fc-confirm-btn-danger {
  background: linear-gradient(135deg, #c97d5e 0%, #a74f35 100%);
  color: #fffaf3;
  box-shadow: 0 16px 28px rgba(167, 79, 53, 0.22);
}

.fc-confirm-btn:not(:disabled):hover {
  transform: translateY(-1px);
  filter: brightness(1.03);
}

.confirm-enter-active, .confirm-leave-active {
  transition: opacity .24s ease;
}

.confirm-enter-active .fc-confirm-card,
.confirm-leave-active .fc-confirm-card {
  transition: transform .24s ease, opacity .24s ease;
}

.confirm-enter-from,
.confirm-leave-to {
  opacity: 0;
}

.confirm-enter-from .fc-confirm-card,
.confirm-leave-to .fc-confirm-card {
  transform: translateY(12px) scale(0.98);
  opacity: 0;
}

@media (max-width: 768px) {
  .fc-root { padding: 10px; }
  .fc-confirm-card {
    padding: 20px;
    border-radius: 22px;
  }
  .fc-confirm-title {
    font-size: 22px;
  }
  .fc-confirm-actions {
    flex-direction: column-reverse;
  }
  .fc-confirm-btn {
    width: 100%;
  }
}
</style>
