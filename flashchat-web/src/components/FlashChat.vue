<template>
  <div class="fc-root">
    <div v-if="!authReady" class="fc-loading">
      <div class="fc-pulse"></div>
      <p v-if="!initFailed" class="fc-load-text">姝ｅ湪杩炴帴 FlashChat...</p>
      <div v-else class="fc-load-fail">
        <p class="fc-load-text fc-danger">杩炴帴澶辫触</p>
        <p class="fc-load-hint">请确认后端服务已经启动，并检查 `8081` 端口。</p>
        <button class="fc-retry" @click="retryInit">閲嶆柊杩炴帴</button>
      </div>
    </div>

    <div v-else class="fc-shell" :style="{ height: viewH }">
      <div class="fc-shell-glow fc-shell-glow-a"></div>
      <div class="fc-shell-glow fc-shell-glow-b"></div>
      <div class="fc-shell-noise"></div>
      <div v-if="!chatLibraryReady && !chatLibraryFailed" class="fc-chat-boot">
        <div class="fc-chat-boot-rail">
          <div class="fc-chat-boot-rail-head">
            <span class="fc-chat-boot-kicker">Chat Stage</span>
            <strong>对话舞台正在装载</strong>
          </div>
          <div v-for="index in 4" :key="`boot-room-${index}`" class="fc-chat-boot-room">
            <span class="fc-chat-boot-room-avatar"></span>
            <span class="fc-chat-boot-room-copy">
              <em></em>
              <i></i>
            </span>
          </div>
        </div>

        <div class="fc-chat-boot-stage">
          <div class="fc-chat-boot-stage-head">
            <div>
              <span class="fc-chat-boot-kicker">Private Salon</span>
              <strong>房间与消息面板正在就位</strong>
            </div>
            <div class="fc-chat-boot-pills">
              <span>Rooms</span>
              <span>Messages</span>
              <span>Actions</span>
            </div>
          </div>

          <div class="fc-chat-boot-stream">
            <div class="fc-chat-boot-bubble is-other">
              <em></em>
              <i></i>
            </div>
            <div class="fc-chat-boot-bubble is-me">
              <em></em>
              <i></i>
            </div>
            <div class="fc-chat-boot-bubble is-other">
              <em></em>
              <i></i>
            </div>
          </div>

          <div class="fc-chat-boot-compose">
            <span></span>
            <div class="fc-chat-boot-compose-btn"></div>
          </div>
        </div>
      </div>

      <div v-else-if="chatLibraryFailed" class="fc-chat-boot fc-chat-boot-fail">
        <span class="fc-chat-boot-kicker">Chat Stage</span>
        <strong>聊天界面加载失败</strong>
        <p>可以重新加载聊天舞台，或刷新页面后再次进入房间。</p>
        <button class="fc-retry" type="button" @click="retryChatLibrary">重新加载聊天界面</button>
      </div>

      <vue-advanced-chat
          v-else
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
          show-reaction-emojis="true"
          show-new-messages-divider="true"
          :show-footer="!!chat.currentRoomId.value"
          :text-messages="txtMsg"
          :message-actions="messageActionsConfigStr"
          :accepted-files="acceptFiles"
          :styles="themeStr"
          room-info-enabled="true"
      >
        <template v-if="mobileViewport">
          <MobileRoomSwipeItem
              v-for="room in chat.rooms.value"
              :key="room.roomId"
              :slot="`room-list-item_${room.roomId}`"
              :room="room"
              :selected="chat.currentRoomId.value === room.roomId"
              :open="mobileSwipeRoomId === room.roomId"
              :can-close="chat.isCurrentUserHost(room.roomId)"
              @swipe-state="onMobileRoomSwipeState"
              @action="onMobileRoomAction($event, room.roomId)"
          />
        </template>
      </vue-advanced-chat>

      <transition name="room-banner">
        <div v-if="showRoomBanner" class="fc-room-banner" :class="`is-${activeRoomState.kind}`">
          <div class="fc-room-banner-head">
            <div>
              <div class="fc-room-banner-kicker">{{ roomBannerKicker }}</div>
              <div class="fc-room-banner-title">{{ activeRoomState.title }}</div>
            </div>
            <div class="fc-room-banner-status">{{ roomBannerStatus }}</div>
          </div>
          <div class="fc-room-banner-text">{{ activeRoomState.detail }}</div>
          <div v-if="roomBannerTags.length" class="fc-room-banner-tags">
            <span v-for="tag in roomBannerTags" :key="tag">{{ tag }}</span>
          </div>
        </div>
      </transition>

      <transition name="room-lockbar">
        <div v-if="showRoomLockbar" class="fc-room-lockbar" :class="`is-${activeRoomState.kind}`">
          <div class="fc-room-lockbar-mark">{{ roomLockbarMark }}</div>
          <div class="fc-room-lockbar-copy">
            <div class="fc-room-lockbar-title">{{ activeRoomState.title }}</div>
            <div class="fc-room-lockbar-text">{{ activeRoomState.blockReason }}</div>
            <div v-if="roomLockbarTags.length" class="fc-room-lockbar-tags">
              <span v-for="tag in roomLockbarTags" :key="tag">{{ tag }}</span>
            </div>
          </div>
          <button class="fc-room-lockbar-btn" type="button" @click="openRoomInfoPanel(chat.currentRoomId.value)">
            {{ roomLockbarButtonText }}
          </button>
        </div>
      </transition>

      <GameOverlay
          v-if="gameOverlayMounted"
          :game="game"
          :current-room-id="chat.currentRoomId.value || ''"
          :member-id="auth.memberId.value"
          @switch-room="switchToGameRoom"
      />
    </div>

    <!-- 渚ф粦鑿滃崟 -->
    <SideDrawer
        v-if="drawerMounted"
        :visible="drawerOpen"
        :nickname="auth.identity.value?.nickname || ''"
        :account-id="auth.identity.value?.accountId || ''"
        :avatar-color="auth.identity.value?.avatarColor || '#C8956C'"
        :avatar-url="auth.identity.value?.avatarUrl || ''"
        :is-admin="Boolean(auth.identity.value?.isAdmin)"
        @close="drawerOpen = false"
        @action="onDrawerAction"
    />

    <!-- 鎴块棿淇℃伅闈㈡澘 -->
    <RoomInfoDeck
        v-if="panelMounted"
        :visible="panelOpen"
        :room="curRoomRaw"
        :members="panelMembers"
        :is-host="chat.isCurrentUserHost(panelRoomId || chat.currentRoomId.value)"
        :current-account-id="auth.memberId.value"
        :room-state="panelRoomState"
        :show-game-create="showGameCreateInPanel"
        :game-action-pending="game.state.actionPending"
        @close="closeRoomInfoPanel"
        @leave="doLeaveFromPanel"
        @close-room="doCloseFromPanel"
        @extend-room="extendDlg = true"
        @resize-room="resizeDlg = true"
        @member-action="openMemberActionConfirm"
        @create-game="doCreateGameFromPanel"
        @update-avatar="doUpdateRoomAvatar"
    />

    <!-- 鈽?涓汉璧勬枡闈㈡澘 -->
    <ProfilePanel
        v-if="profileMounted"
        :visible="profileOpen"
        @close="profileOpen = false"
        @set-password="openPasswordDialog('set')"
        @change-password="openPasswordDialog('change')"
        @upgrade="profileOpen = false; upgradeDlg = true"
        @logout="doLogout"
        @delete-account="doDeleteAccount"
        @profile-updated="onProfileUpdated"
    />

    <!-- 鈽?璐﹀彿鍗囩骇寮圭獥 -->
    <UpgradeDialog
        v-if="upgradeMounted"
        :visible="upgradeDlg"
        @upgraded="onUpgraded"
        @close="upgradeDlg = false"
    />

    <!-- 鈽?瀵嗙爜寮圭獥 -->
    <PasswordDialog
        v-if="passwordMounted"
        :visible="passwordDlg"
        :mode="passwordMode"
        @success="onPasswordSuccess"
        @close="passwordDlg = false"
    />

    <!-- 鍒涘缓/鍔犲叆鎴块棿寮圭獥 -->
    <RoomCreateSheet v-if="createMounted" :visible="createDlg" @create="doCreateRoom" @close="createDlg = false" />
    <RoomCreatedDialog v-if="createdRoomMounted" :visible="createdRoomDialog" :room="createdRoomPending" @enter="enterCreatedRoom" />
    <JoinRoomDialog v-if="joinMounted" :visible="joinDlg" @join="doJoinRoom" @close="joinDlg = false" />
    <RoomExtendSheet v-if="extendMounted" :visible="extendDlg" :room="curRoomRaw" @extend="doExtendRoom" @close="extendDlg = false" />
    <RoomResizeSheet v-if="resizeMounted" :visible="resizeDlg" :room="curRoomRaw" @resize="doResizeRoom" @close="resizeDlg = false" />

    <ActionConfirmDialog
        :visible="deleteConfirm.visible"
        :tone="deleteConfirmTone"
        :kicker="deleteConfirmKicker"
        :title="deleteConfirmTitle"
        :text="deleteConfirmText"
        :facts="deleteConfirmFacts"
        :preview="deleteConfirmPreview"
        preview-label="消息预览"
        :primary-text="deleteConfirmButtonText"
        pending-text="处理中..."
        :pending="deleteConfirm.pending"
        @close="closeDeleteConfirm"
        @confirm="confirmDeleteMessage"
    />

    <ActionConfirmDialog
        :visible="roomActionConfirm.visible"
        :tone="roomActionConfirmTone"
        kicker="Room Action"
        :title="roomActionConfirmTitle"
        :text="roomActionConfirmText"
        :facts="roomActionConfirmFacts"
        :primary-text="roomActionConfirmButtonText"
        pending-text="处理中..."
        :pending="roomActionConfirm.pending"
        @close="closeRoomActionConfirm"
        @confirm="confirmRoomAction"
    />

    <ActionConfirmDialog
        :visible="memberActionConfirm.visible"
        :tone="memberActionConfirmTone"
        kicker="Member Action"
        :title="memberActionConfirmTitle"
        :text="memberActionConfirmText"
        :facts="memberActionConfirmFacts"
        :preview="memberActionConfirmPreview"
        preview-label="目标成员"
        :primary-text="memberActionConfirmButtonText"
        pending-text="处理中..."
        :pending="memberActionConfirm.pending"
        @close="closeMemberActionConfirm"
        @confirm="confirmMemberAction"
    />

    <!-- Toast -->
    <transition name="toast">
      <div v-if="toast.show" :class="['fc-toast', `fc-toast-${toast.type}`, { 'fc-toast-with-panel': panelOpen }]">{{ toast.msg }}</div>
    </transition>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted, nextTick, defineAsyncComponent } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import ActionConfirmDialog from './ActionConfirmDialog.vue'
import MobileRoomSwipeItem from './MobileRoomSwipeItem.vue'

import { useAuth } from '@/composables/useAuth'
import { useWebSocket, WS_TYPE } from '@/composables/useWebSocket'
import { useChat } from '@/composables/useChat'
import { GAME_WS_TYPE, useGame } from '@/composables/useGame'
import { deleteAccount as apiDeleteAccount } from '@/api/account'
import { extendRoom as apiExtendRoom, resizeRoom as apiResizeRoom, updateRoomAvatar as apiUpdateRoomAvatar } from '@/api/room'
import { formatCountdownShort } from '@/utils/formatter'

const lazySurface = loader => defineAsyncComponent({ loader, suspensible: false })
const SideDrawer = lazySurface(() => import('./SideDrawer.vue'))
const RoomInfoDeck = lazySurface(() => import('./RoomInfoDeck.vue'))
const RoomCreateSheet = lazySurface(() => import('./RoomCreateSheet.vue'))
const RoomCreatedDialog = lazySurface(() => import('./RoomCreatedDialog.vue'))
const RoomExtendSheet = lazySurface(() => import('./RoomExtendSheet.vue'))
const RoomResizeSheet = lazySurface(() => import('./RoomResizeSheet.vue'))
const JoinRoomDialog = lazySurface(() => import('./JoinRoomDialog.vue'))
const GameOverlay = lazySurface(() => import('./game/GameOverlay.vue'))
const ProfilePanel = lazySurface(() => import('./ProfilePanel.vue'))
const UpgradeDialog = lazySurface(() => import('./UpgradeDialog.vue'))
const PasswordDialog = lazySurface(() => import('./PasswordDialog.vue'))

const route = useRoute()
const router = useRouter()
const auth = useAuth()
const ws = useWebSocket()
const chat = useChat(
    () => auth.memberId.value,
    () => auth.identity.value  // { nickname, avatarColor, avatarUrl, ... }
)
const game = useGame({
  getCurrentRoomId: () => chat.currentRoomId.value,
  getMemberId: () => auth.memberId.value,
  getIdentity: () => auth.identity.value
})

const authReady = computed(() => auth.isReady.value)
const myId = computed(() => String(auth.memberId.value || ''))

const drawerOpen = ref(false)
const panelOpen = ref(false)
const panelRoomId = ref('')
const panelMembers = ref([])
const createDlg = ref(false)
const createdRoomDialog = ref(false)
const createdRoomPending = ref(null)
const joinDlg = ref(false)
const extendDlg = ref(false)
const resizeDlg = ref(false)
const viewH = ref('100vh')
const mobileViewport = ref(false)
const mobileSwipeRoomId = ref('')
const chatEl = ref(null)
const initFailed = ref(false)
const curMembers = ref([])
const chatLibraryReady = ref(false)
const chatLibraryFailed = ref(false)

// 鈽?鏂板鐘舵€?
const profileOpen = ref(false)
const upgradeDlg = ref(false)
const passwordDlg = ref(false)
const passwordMode = ref('set')

function useLazyMount(source) {
  const mounted = ref(Boolean(source.value))
  watch(source, visible => {
    if (visible) mounted.value = true
  }, { immediate: true })
  return mounted
}

let chatLibraryPromise = null

async function ensureChatLibrary(force = false) {
  if (chatLibraryReady.value && !force) return true
  if (chatLibraryPromise && !force) return chatLibraryPromise

  chatLibraryFailed.value = false
  chatLibraryPromise = import('vue-advanced-chat')
    .then(({ register }) => {
      if (!customElements.get('vue-advanced-chat')) {
        register()
      }
      chatLibraryReady.value = true
      return true
    })
    .catch(error => {
      console.error('[FlashChat] 聊天组件加载失败', error)
      chatLibraryFailed.value = true
      showToast('聊天界面加载失败', 'error')
      return false
    })
    .finally(() => {
      chatLibraryPromise = null
    })

  return chatLibraryPromise
}

function retryChatLibrary() {
  ensureChatLibrary(true)
}
const deleteConfirm = ref({
  visible: false,
  pending: false,
  roomId: '',
  message: null,
  mode: 'self'
})
const roomActionConfirm = ref({
  visible: false,
  pending: false,
  roomId: '',
  action: ''
})
const memberActionConfirm = ref({
  visible: false,
  pending: false,
  roomId: '',
  action: '',
  member: null
})

let bound = false
let cdTimer = null
let expiryTimer = null
let roomLoadSeq = 0
let typingIdleTimer = null
let typingRoomId = null

const curRoomRaw = computed(() =>
    chat.roomsRaw.value.find(r => r.roomId === (panelRoomId.value || chat.currentRoomId.value)) || null
)
const activeRoomState = computed(() => chat.getRoomState(chat.currentRoomId.value))
const panelRoomState = computed(() => chat.getRoomState(panelRoomId.value || chat.currentRoomId.value))
const showGameCreateInPanel = computed(() => {
  const roomId = panelRoomId.value || chat.currentRoomId.value
  if (!roomId) return false
  if (game.state.session?.gameId || game.state.session?.result) return false
  if (game.state.roomActiveInfo) return false
  return true
})
const gameOverlayVisible = computed(() =>
  Boolean(
    game.state.notice ||
    game.state.roomActiveInfo ||
    game.state.session?.gameId ||
    game.state.session?.result
  )
)
const drawerMounted = useLazyMount(drawerOpen)
const panelMounted = useLazyMount(panelOpen)
const profileMounted = useLazyMount(profileOpen)
const upgradeMounted = useLazyMount(upgradeDlg)
const passwordMounted = useLazyMount(passwordDlg)
const createMounted = useLazyMount(createDlg)
const createdRoomMounted = useLazyMount(createdRoomDialog)
const joinMounted = useLazyMount(joinDlg)
const extendMounted = useLazyMount(extendDlg)
const resizeMounted = useLazyMount(resizeDlg)
const gameOverlayMounted = useLazyMount(gameOverlayVisible)
const showRoomBanner = computed(() => {
  if (!chat.currentRoomId.value) return false
  return ['expiring', 'grace', 'closed', 'muted'].includes(activeRoomState.value?.kind)
})
const showRoomLockbar = computed(() => {
  if (!chat.currentRoomId.value) return false
  return ['grace', 'closed', 'muted'].includes(activeRoomState.value?.kind)
})
const roomBannerKicker = computed(() => {
  const kind = activeRoomState.value?.kind
  if (kind === 'expiring') return '即将到期'
  if (kind === 'grace') return '宽限期'
  if (kind === 'closed') return '房间关闭'
  if (kind === 'muted') return '发言受限'
  return '房间状态'
})
const roomStateCountdownLabel = computed(() => {
  const countdownMs = Number(activeRoomState.value?.countdownMs ?? 0)
  return countdownMs > 0 ? formatCountdownShort(countdownMs) : ''
})
const roomBannerStatus = computed(() => {
  const kind = activeRoomState.value?.kind
  if (kind === 'muted') return '只读中'
  if (kind === 'closed') return '已结束'
  if (kind === 'grace') return '只读中'
  if (kind === 'expiring') return '可发言'
  return activeRoomState.value?.canSend ? '开放中' : '处理中'
})
const roomBannerTags = computed(() => {
  const tags = []
  const kind = activeRoomState.value?.kind
  const countdown = roomStateCountdownLabel.value
  if (countdown) tags.push(`剩余 ${countdown}`)
  if (kind === 'expiring') tags.push('到期后进入 5 分钟宽限期')
  if (kind === 'grace') tags.push('现在只能查看消息')
  if (kind === 'closed') tags.push('当前聊天不再接收新消息')
  if (kind === 'muted') tags.push('等待房主解除禁言')
  return tags
})
const roomLockbarMark = computed(() => {
  const kind = activeRoomState.value?.kind
  if (kind === 'muted') return 'Read Only'
  if (kind === 'closed') return 'Closed'
  if (kind === 'grace') return 'Grace'
  return 'Room'
})
const roomLockbarTags = computed(() => {
  const tags = []
  if (roomStateCountdownLabel.value) tags.push(`剩余 ${roomStateCountdownLabel.value}`)
  if (activeRoomState.value?.kind === 'muted') tags.push('你仍然可以查看全部消息')
  if (activeRoomState.value?.kind === 'grace') tags.push('宽限期结束后房间会关闭')
  if (activeRoomState.value?.kind === 'closed') tags.push('可以查看房间资料与成员记录')
  return tags
})
const roomLockbarButtonText = computed(() => {
  if (activeRoomState.value?.kind === 'muted') return '查看成员'
  return '查看房间'
})

const roomsStr = computed(() => JSON.stringify(chat.rooms.value))
const msgsStr = computed(() => JSON.stringify(chat.messages.value))

const txtMsg = JSON.stringify({
  ROOMS_EMPTY: '还没有加入任何房间，点击右上角 + 开始创建',
  ROOM_EMPTY: '还没有消息',
  NEW_MESSAGES: '新消息',
  MESSAGE_DELETED: '此消息已被删除',
  MESSAGES_EMPTY: '还没有消息',
  CONVERSATION_STARTED: '会话开始',
  TYPE_MESSAGE: '输入消息...',
  SEARCH: '搜索房间...',
  IS_ONLINE: '在线',
  LAST_SEEN: '最近活跃',
  IS_TYPING: '正在输入...'
})

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
const deleteConfirmTone = computed(() => deleteConfirm.value.mode === 'all' ? 'danger' : 'muted')
const deleteConfirmKicker = computed(() => deleteConfirm.value.mode === 'all' ? 'Message Action' : 'Private Action')
const deleteConfirmFacts = computed(() => (
  deleteConfirm.value.mode === 'all'
    ? ['房间内其他成员会同步失去这条消息', '该操作会立即影响当前会话中的所有人']
    : ['只会在你的当前设备上隐藏', '房间里的其他成员仍然可以继续看到']
))
const deleteConfirmPreview = computed(() => deleteConfirm.value.message?.content || '[文件消息]')
const roomActionConfirmTitle = computed(() =>
  roomActionConfirm.value.action === 'closeRoom' ? '关闭这个房间？' : '离开这个房间？'
)
const roomActionConfirmText = computed(() =>
  roomActionConfirm.value.action === 'closeRoom'
    ? '关闭后，房间会对所有成员停止可用，当前聊天也会结束。'
    : '离开后，你会返回房间列表，不再停留在这个聊天里。'
)
const roomActionConfirmButtonText = computed(() =>
  roomActionConfirm.value.action === 'closeRoom' ? '确认关闭' : '确认离开'
)
const roomActionConfirmTone = computed(() =>
  roomActionConfirm.value.action === 'closeRoom' ? 'danger' : 'warning'
)
const roomActionConfirmFacts = computed(() => (
  roomActionConfirm.value.action === 'closeRoom'
    ? ['所有成员都会失去当前房间入口', '房间状态会立即变为已关闭']
    : ['你会回到房间列表界面', '之后需要重新加入才能再次进入']
))
const memberActionConfirmTitle = computed(() => {
  const name = memberActionConfirm.value.member?.username || '这位成员'
  if (memberActionConfirm.value.action === 'kick') return `将 ${name} 移出房间？`
  if (memberActionConfirm.value.action === 'unmute') return `解除 ${name} 的禁言？`
  return `对 ${name} 执行禁言？`
})
const memberActionConfirmText = computed(() => {
  if (memberActionConfirm.value.action === 'kick') {
    return '踢出后，该成员会立刻退出当前房间，需要重新加入后才能再次进入。'
  }
  if (memberActionConfirm.value.action === 'unmute') {
    return '解除后，对方会立刻恢复发言能力。'
  }
  return '禁言后，对方仍可查看消息，但无法继续发送新消息。'
})
const memberActionConfirmButtonText = computed(() => {
  if (memberActionConfirm.value.action === 'kick') return '确认踢出'
  if (memberActionConfirm.value.action === 'unmute') return '确认解除'
  return '确认禁言'
})
const memberActionConfirmTone = computed(() => {
  if (memberActionConfirm.value.action === 'kick') return 'danger'
  if (memberActionConfirm.value.action === 'unmute') return 'accent'
  return 'warning'
})
const memberActionConfirmFacts = computed(() => {
  if (memberActionConfirm.value.action === 'kick') {
    return ['对方会立即离开当前房间', '需要重新加入后才能再次进入']
  }
  if (memberActionConfirm.value.action === 'unmute') {
    return ['对方会立刻恢复发言能力', '房间里的最新状态会同步刷新']
  }
  return ['对方仍然可以查看全部消息', '新的发言会被房间规则拦截']
})
const memberActionConfirmPreview = computed(() =>
  memberActionConfirm.value.member?.username || '匿名成员'
)
const acceptFiles = 'image/*,audio/*,video/*,application/pdf,text/plain'

const themeStr = JSON.stringify({
  general: { color: '#211A14', colorSpinner: '#B67639', borderStyle: 'none', backgroundInput: '#F8F3EC', colorPlaceholder: '#948474', colorCaret: '#975A26', backgroundScrollIcon: 'rgba(255,253,249,0.96)' },
  container: { border: '1px solid rgba(33,26,20,0.08)', borderRadius: '34px', boxShadow: '0 22px 48px rgba(33,26,20,0.10)' },
  header: { background: 'rgba(255,253,249,0.94)', colorRoomName: '#211A14', colorRoomInfo: '#5E4F42' },
  footer: { background: 'rgba(255,253,249,0.96)', backgroundReply: '#F4E8D9' },
  sidenav: { background: 'rgba(255,255,255,0.92)', backgroundHover: 'rgba(247,242,235,0.94)', backgroundActive: '#FFFCF7', colorActive: '#211A14', borderColorSearch: 'rgba(33,26,20,0.10)' },
  content: { background: 'linear-gradient(180deg,#FCF8F1 0%,#F6EEE2 100%)' },
  message: { background: 'rgba(255,253,249,0.98)', backgroundMe: '#F4E7D7', color: '#211A14', colorStarted: '#948474', backgroundDeleted: '#F3E4DE', colorDeleted: '#8E7D70', colorUsername: '#85511F', colorTimestamp: '#948474', backgroundDate: 'rgba(255,252,247,0.94)', colorDate: '#4D463F', backgroundSystem: 'transparent', colorSystem: '#685C52', colorNewMessages: '#85511F', backgroundReply: 'rgba(182,118,57,0.12)', colorReplyUsername: '#85511F', backgroundImage: '#F1E5D7' },
  room: { colorUsername: '#211A14', colorMessage: '#5E4F42', colorTimestamp: '#8E7D70', colorStateOnline: '#54784C', colorStateOffline: '#AA9A88', backgroundCounterBadge: '#975A26', colorCounterBadge: '#FFFAF3' },
  emoji: { background: '#FFF9EF' },
  icons: { search: '#4D463F', add: '#975A26', toggle: '#4D463F', menu: '#4D463F', close: '#4D463F', file: '#975A26', paperclip: '#4D463F', send: '#975A26', sendDisabled: '#B6AB9A', emoji: '#4D463F', emojiReaction: '#6B5D4E', document: '#975A26', checkmark: '#975A26', checkmarkSeen: '#975A26', eye: '#4D463F', dropdownMessage: '#6B5D4E', dropdownMessageBackground: '#FFFDF8', dropdownRoom: '#4D463F', dropdownScroll: '#975A26', microphone: '#4D463F', audioPlay: '#975A26', audioPause: '#975A26', audioCancel: '#B8604B', audioConfirm: '#54784C' }
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
    .vac-card-window{background:#FFFFFF!important}
    .vac-chat-container{background:var(--fc-bg,#F7F2EB)!important}
    .vac-rooms-container{background:#FFFFFF!important;border-right:1px solid rgba(28,23,20,0.08)!important;position:relative!important}
    .vac-room-header,.vac-box-search,.vac-room-footer{backdrop-filter:none!important}
    .vac-room-header{height:72px!important;background:#FFFFFF!important;border-bottom:1px solid rgba(28,23,20,0.08)!important}
    .vac-room-header .vac-room-wrapper{padding:0 18px!important}
    .vac-room-header .vac-avatar{height:42px!important;width:42px!important;min-height:42px!important;min-width:42px!important;margin-right:14px!important;border-radius:50%!important;box-shadow:none!important}
    .vac-room-header .vac-room-name{font-family:var(--fc-font-display)!important;font-size:17px!important;line-height:1.2!important;font-weight:600!important;letter-spacing:.01em!important}
    .vac-room-header .vac-room-info{font-size:11px!important;line-height:16px!important;letter-spacing:.14em!important;text-transform:uppercase!important;color:#9E9083!important}
    .vac-rooms-container .vac-room-list{padding:10px 12px 8px!important}
    .vac-room-item{position:relative!important;border-radius:16px!important;margin:4px 8px!important;border:1px solid transparent!important;background:transparent!important;box-shadow:none!important;transition:background .2s ease,border-color .2s ease,box-shadow .2s ease!important}
    .vac-room-item:hover{background:var(--fc-bg,#F7F2EB)!important;border-color:rgba(28,23,20,0.08)!important;box-shadow:inset 0 0 0 1px rgba(255,255,255,0.62)!important;transform:none!important}
    .vac-room-item.vac-room-selected,.vac-rooms-container .vac-room-selected,[class*="room-selected"]{background:var(--fc-bg,#F7F2EB)!important;border-color:rgba(28,23,20,0.14)!important;box-shadow:inset 0 0 0 1px rgba(255,255,255,0.72)!important}
    .vac-room-item.vac-room-selected::before,.vac-rooms-container .vac-room-selected::before,[class*="room-selected"]::before{content:none!important;display:none!important}
    .vac-room-container .vac-room-name{font-family:var(--fc-font-display)!important;font-size:15px!important;line-height:1.2!important;font-weight:600!important}
    .vac-room-container .vac-text-date{margin-left:8px!important;font-size:10px!important;letter-spacing:.12em!important;text-transform:uppercase!important;font-variant-numeric:tabular-nums!important;color:#8B7E71!important}
    .vac-room-container .vac-text-last{display:flex!important;align-items:center!important;gap:3px!important;font-size:12px!important;line-height:18px!important;color:#5C4F42!important}
    .vac-room-container .vac-room-badge{min-width:20px!important;height:20px!important;padding:0 6px!important;border-radius:999px!important;box-shadow:none!important}
    .vac-rooms-container .vac-room-header input{background:var(--fc-bg,#F7F2EB)!important;border:1px solid rgba(28,23,20,0.08)!important;border-radius:20px!important;box-shadow:none!important}
    .vac-room-header .vac-add-icon{border-radius:50%!important;background:var(--fc-bg,#F7F2EB)!important;border:1px solid rgba(28,23,20,0.08)!important;padding:7px!important}
    .vac-room-footer .vac-box-footer{background:#FFFFFF!important;border:1px solid rgba(28,23,20,0.08)!important;border-radius:24px!important;box-shadow:none!important}
    .vac-col-messages .vac-container-scroll{background:var(--fc-bg,#F7F2EB)!important}
    .vac-message-wrapper .vac-card-system{max-width:320px!important;padding:8px 18px!important;border-radius:999px!important;border:1px solid rgba(28,23,20,0.08)!important;background:#FFFFFF!important;color:#5C4F42!important;box-shadow:none!important}
    .vac-message-wrapper .vac-message-box{max-width:78%!important;line-height:1.2!important;margin-bottom:9px!important}
    .vac-message-wrapper .vac-avatar{height:34px!important;width:34px!important;min-height:34px!important;min-width:34px!important;margin:0 0 20px!important;border-radius:50%!important;box-shadow:none!important}
    .vac-message-wrapper .vac-avatar-current-offset{margin-right:34px!important}
    .vac-message-wrapper .vac-avatar-offset{margin-left:34px!important}
    .vac-message-wrapper .vac-message-container{padding:4px 10px!important;min-width:auto!important;overflow:visible!important}
    .vac-message-wrapper .vac-message-container-offset{margin-top:5px!important}
    .vac-message-wrapper .vac-message-card{position:relative!important;min-width:138px!important;border-radius:20px!important;border:1px solid rgba(28,23,20,0.08)!important;box-shadow:none!important;padding:8px 54px 10px 16px!important}
    .vac-message-wrapper .vac-message-box:not(.vac-offset-current) .vac-message-card::before{content:''!important;position:absolute!important;left:-7px!important;bottom:10px!important;width:16px!important;height:18px!important;background:#FFFFFF!important;border-left:1px solid rgba(28,23,20,0.08)!important;border-bottom:1px solid rgba(28,23,20,0.08)!important;border-bottom-left-radius:14px!important;transform:skewY(26deg) rotate(7deg)!important;box-shadow:none!important}
    .vac-message-wrapper .vac-message-card.vac-message-current{background:#F4E7D7!important;border-color:rgba(28,23,20,0.10)!important}
    .vac-message-wrapper .vac-message-box.vac-offset-current .vac-message-card::before{content:''!important;position:absolute!important;left:auto!important;right:-7px!important;bottom:10px!important;width:16px!important;height:18px!important;background:#F4E7D7!important;border-left:none!important;border-right:1px solid rgba(28,23,20,0.10)!important;border-bottom:1px solid rgba(28,23,20,0.10)!important;border-bottom-left-radius:0!important;border-bottom-right-radius:14px!important;transform:skewY(-26deg) rotate(-7deg)!important;box-shadow:none!important}
    .vac-message-wrapper .vac-message-card:not(.vac-message-current){background:#FFFFFF!important}
    .vac-message-wrapper .vac-format-message-wrapper,.vac-message-wrapper .vac-format-container{display:block!important;font-size:14px!important;line-height:1.22!important;margin-top:2px!important}
    .vac-message-wrapper .vac-text-username{font-size:12px!important;font-weight:600!important;letter-spacing:.01em!important;line-height:1.02!important;margin-bottom:5px!important}
    .vac-message-wrapper .vac-text-timestamp{position:absolute!important;right:15px!important;bottom:9px!important;font-size:10px!important;line-height:1!important;margin-top:0!important;display:flex!important;align-items:center!important;gap:3px!important;padding:2px 6px!important;border-radius:999px!important;background:rgba(255,255,255,0.72)!important;border:1px solid rgba(28,23,20,0.08)!important;color:#7A6D60!important;font-variant-numeric:tabular-nums!important}
    .vac-message-wrapper .vac-message-card.vac-message-current .vac-text-timestamp{background:rgba(255,250,244,0.9)!important;border-color:rgba(151,90,38,0.2)!important}
    .vac-message-wrapper .vac-icon-edited{margin:0!important}
    .vac-message-wrapper .vac-reply-message{position:relative!important;margin:0 -4px 6px!important;padding:6px 10px 6px 12px!important;border-radius:14px!important;border:1px solid rgba(23,23,23,0.10)!important;background:rgba(255,252,246,0.94)!important}
    .vac-message-wrapper .vac-reply-message::before{content:''!important;position:absolute!important;left:6px!important;top:6px!important;bottom:6px!important;width:2px!important;border-radius:999px!important;background:linear-gradient(180deg,#B67639 0%,#975A26 100%)!important}
    .vac-message-wrapper .vac-reply-message .vac-reply-username{font-size:11px!important;line-height:1.08!important;margin-bottom:2px!important;color:#85511F!important}
    .vac-message-wrapper .vac-reply-message .vac-reply-content{font-size:11px!important;line-height:1.25!important;color:#5F5346!important}
    .vac-message-wrapper .vac-message-file-container .vac-file-wrapper{max-width:100%!important}
    .vac-message-wrapper .vac-message-file-container .vac-file-wrapper .vac-file-container{height:104px!important;width:104px!important;margin:4px 0 6px!important;border-radius:14px!important;background:rgba(255,255,255,0.84)!important;border:1px solid rgba(28,23,20,0.10)!important;box-shadow:inset 0 1px 0 rgba(255,255,255,0.76)!important;padding:14px!important}
    .vac-message-wrapper .vac-message-file-container .vac-file-wrapper .vac-file-container svg{height:32px!important;width:32px!important}
    .vac-message-wrapper .vac-message-file-container .vac-file-wrapper .vac-text-extension{margin-top:4px!important;font-size:13px!important;font-weight:600!important;color:#7A5A17!important;letter-spacing:.01em!important}
    @media only screen and (max-width: 768px){.vac-rooms-container .vac-room-list{padding:4px 8px calc(16px + env(safe-area-inset-bottom))!important}.vac-room-item,.vac-room-item.vac-room-selected,.vac-rooms-container .vac-room-selected,[class*="room-selected"]{margin:4px 8px!important;padding:0!important;min-height:auto!important;background:transparent!important;border:none!important;box-shadow:none!important;border-radius:30px!important;overflow:hidden!important}.vac-room-item.vac-room-selected::before,.vac-rooms-container .vac-room-selected::before,[class*="room-selected"]::before{left:6px!important;height:20px!important}.vac-room-item:hover{background:transparent!important;border-color:transparent!important;transform:none!important}.vac-room-container{display:block!important}.vac-box-search{height:auto!important;padding:12px 12px 12px!important}.vac-box-search .vac-input{height:44px!important;font-size:16px!important;padding-left:42px!important;border-radius:22px!important}.vac-room-header{height:56px!important}.vac-room-header .vac-room-wrapper{padding:0 12px!important}.vac-room-header .vac-room-name{font-size:16px!important}.vac-room-header .vac-room-info{font-size:10px!important}.vac-room-header .vac-avatar{height:38px!important;width:38px!important;min-height:38px!important;min-width:38px!important}.vac-room-header .vac-add-icon{padding:8px!important}.vac-room-footer .vac-box-footer{padding-bottom:calc(8px + env(safe-area-inset-bottom))!important}.vac-message-wrapper .vac-card-system{max-width:280px!important;padding:8px 14px!important}.vac-message-wrapper .vac-message-box{max-width:88%!important;margin-bottom:7px!important}.vac-message-wrapper .vac-avatar{height:30px!important;width:30px!important;min-height:30px!important;min-width:30px!important;margin:0 0 11px!important;border-radius:50%!important}.vac-message-wrapper .vac-avatar.vac-avatar-current{margin:0 0 11px 8px!important}.vac-message-wrapper .vac-avatar-current-offset{margin-right:30px!important}.vac-message-wrapper .vac-avatar-offset{margin-left:30px!important}.vac-message-wrapper .vac-message-container{padding:4px 5px!important}.vac-message-wrapper .vac-message-card{min-width:122px!important;padding:7px 50px 9px 13px!important;border-radius:22px!important}.vac-message-wrapper .vac-message-box:not(.vac-offset-current) .vac-message-card::before{left:-6px!important;bottom:9px!important;width:14px!important;height:16px!important}.vac-message-wrapper .vac-message-box.vac-offset-current .vac-message-card::before{right:-6px!important;bottom:9px!important;width:14px!important;height:16px!important}.vac-message-wrapper .vac-text-timestamp{right:13px!important;bottom:8px!important;padding:1px 5px!important;font-size:9px!important}}
    .vac-card-date{border-radius:999px!important;border:1px solid rgba(28,23,20,0.08)!important;box-shadow:none!important}
    ::-webkit-scrollbar{width:5px}::-webkit-scrollbar-thumb{background:rgba(28,23,20,0.14);border-radius:999px}::-webkit-scrollbar-track{background:transparent}
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
  if (panelRoomId.value === rid) {
    panelMembers.value = members || []
  }
  chat.refreshRoomCountdowns()
}

async function switchToGameRoom(roomId) {
  if (!roomId) return
  try {
    await forceLoadRoom(roomId, { reset: true })
  } catch (error) {
    showToast(error?.message || '切换到游戏房间失败', 'error')
  }
}

async function doCreateGameFromPanel(config) {
  const created = await game.createGame(config)
  if (created) {
    panelOpen.value = false
  }
}

async function openRoomInfoPanel(roomId = chat.currentRoomId.value) {
  const rid = roomId || chat.currentRoomId.value
  if (!rid) return
  panelRoomId.value = rid
  panelMembers.value = await chat.loadRoomUsers(rid)
  panelOpen.value = true
}

function closeRoomInfoPanel() {
  panelOpen.value = false
  panelRoomId.value = ''
  panelMembers.value = []
}

async function refreshVisibleMembers(roomId) {
  if (!roomId) return []
  const nextMembers = await chat.loadRoomUsers(roomId)
  if (roomId === chat.currentRoomId.value) {
    curMembers.value = nextMembers || []
  }
  if (panelRoomId.value === roomId) {
    panelMembers.value = nextMembers || []
  }
  return nextMembers || []
}

function openRoomActionConfirm(action, roomId) {
  roomActionConfirm.value = {
    visible: true,
    pending: false,
    roomId,
    action
  }
}

function closeRoomActionConfirm(force = false) {
  if (roomActionConfirm.value.pending && !force) return
  roomActionConfirm.value = {
    visible: false,
    pending: false,
    roomId: '',
    action: ''
  }
}

function openMemberActionConfirm(payload) {
  const roomId = panelRoomId.value || chat.currentRoomId.value
  if (!roomId || !payload?.action || !payload?.member) return
  memberActionConfirm.value = {
    visible: true,
    pending: false,
    roomId,
    action: payload.action,
    member: payload.member
  }
}

function closeMemberActionConfirm(force = false) {
  if (memberActionConfirm.value.pending && !force) return
  memberActionConfirm.value = {
    visible: false,
    pending: false,
    roomId: '',
    action: '',
    member: null
  }
}

async function confirmMemberAction() {
  const { roomId, action, member } = memberActionConfirm.value
  const targetAccountId = member?._raw?.accountId
  if (!roomId || !action || targetAccountId == null) {
    closeMemberActionConfirm(true)
    return
  }

  memberActionConfirm.value = {
    ...memberActionConfirm.value,
    pending: true
  }

  try {
    if (action === 'kick') {
      await chat.kickMember(roomId, targetAccountId)
      showToast(`${member.username || '成员'} 已被移出房间`, 'success')
    } else if (action === 'unmute') {
      await chat.unmuteMember(roomId, targetAccountId)
      showToast(`${member.username || '成员'} 已恢复发言`, 'success')
    } else {
      await chat.muteMember(roomId, targetAccountId)
      showToast(`${member.username || '成员'} 已被禁言`, 'warning')
    }

    await refreshVisibleMembers(roomId)
    closeMemberActionConfirm(true)
  } catch (error) {
    memberActionConfirm.value = {
      ...memberActionConfirm.value,
      pending: false
    }
    showToast(error?.message || '成员操作失败', 'error')
  }
}

async function confirmRoomAction() {
  const { roomId, action } = roomActionConfirm.value
  if (!roomId || !action) return

  if (action === 'leaveRoom' && chat.isCurrentUserHost(roomId)) {
    closeRoomActionConfirm(true)
    showToast('房主不能离开房间，请使用关闭房间功能', 'warning')
    return
  }

  roomActionConfirm.value = {
    ...roomActionConfirm.value,
    pending: true
  }

  try {
    if (action === 'leaveRoom') {
      await chat.leaveRoom(roomId)
      if (panelRoomId.value === roomId) closeRoomInfoPanel()
      showToast('已离开')
    } else if (action === 'closeRoom') {
      await chat.closeRoom(roomId)
      if (panelRoomId.value === roomId) closeRoomInfoPanel()
      showToast('已关闭')
    }

    closeRoomActionConfirm(true)
  } catch (error) {
    roomActionConfirm.value = {
      ...roomActionConfirm.value,
      pending: false
    }
    const fallback = action === 'leaveRoom' ? '离开失败' : '关闭失败'
    showToast(error?.message || fallback, 'error')
  }
}

async function handleRoomAction(name, roomId = chat.currentRoomId.value) {
  const rid = roomId || chat.currentRoomId.value
  if (!name || !rid) return

  mobileSwipeRoomId.value = ''

  try {
    if (name === 'roomInfo') {
      await openRoomInfoPanel(rid)
      return
    }

    if (name === 'leaveRoom') {
      if (chat.isCurrentUserHost(rid)) {
        showToast('房主不能离开房间，请使用关闭房间功能', 'warning')
        return
      }
      openRoomActionConfirm('leaveRoom', rid)
      return
    }

    if (name === 'closeRoom') {
      openRoomActionConfirm('closeRoom', rid)
      return
    }
  } catch (error) {
    const fallback = name === 'leaveRoom' ? '离开失败' : name === 'closeRoom' ? '关闭失败' : '操作失败'
    showToast(error?.message || fallback, 'error')
  }
}

function onMobileRoomSwipeState(roomId) {
  mobileSwipeRoomId.value = roomId || ''
}

function onMobileRoomAction(name, roomId) {
  handleRoomAction(name, roomId)
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

// ---- 浜嬩欢缁戝畾 ----
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
      showToast('宸蹭负鎵€鏈変汉鍒犻櫎娑堟伅', 'success')
    } else {
      chat.hideMessageForSelf(message, roomId)
      showToast('宸蹭负鑷繁鍒犻櫎娑堟伅', 'success')
    }
    closeDeleteConfirm(true)
  } catch (error) {
    deleteConfirm.value = {
      ...deleteConfirm.value,
      pending: false
    }
    showToast(error?.message || '鍒犻櫎澶辫触', 'error')
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
    try {
      const sent = await chat.sendMessage(d)
      if (sent) stopTyping(d.roomId || chat.currentRoomId.value)
    } catch (error) {
      showToast(error?.message || '发送失败', 'warning')
    }
  })
  el.addEventListener('send-message-reaction', async (e) => {
    const d = getDetail(e)
    const rid = d?.roomId || chat.currentRoomId.value
    const messageId = d?.messageId
    const reaction = d?.reaction

    if (!rid || !messageId || !reaction) return

    try {
      await chat.toggleReaction(messageId, reaction, rid)
    } catch (error) {
      showToast(error?.message || '琛ㄦ儏娣诲姞澶辫触', 'error')
    }
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
  el.addEventListener('room-info', () => { openRoomInfoPanel(chat.currentRoomId.value) })
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
        content: `⚠️ 房间将在 ${mins} 分钟后到期`,
        date: '',
        timestamp: ''
      }]
    }
  }
}

// ================================================================
// 鈽?鎿嶄綔澶勭悊
// ================================================================

function onDrawerAction(act) {
  drawerOpen.value = false
  if (act === 'create') createDlg.value = true
  else if (act === 'join') joinDlg.value = true
  else if (act === 'admin') router.push('/admin')
  else if (act === 'public') router.push('/room/public')
  else if (act === 'credits') router.push('/credits')
  else if (act === 'invites') router.push('/invites')
  else if (act === 'profile') profileOpen.value = true
  else if (act === 'logout') doLogout()
}

function showRoomList() {
  stopTyping()
  panelOpen.value = false
  drawerOpen.value = false
  joinDlg.value = false
  extendDlg.value = false
  resizeDlg.value = false
  chat.currentRoomId.value = null
  chat.messages.value = []
  try {
    sessionStorage.removeItem('fc_last_room')
  } catch {}
}

function consumeRouteState() {
  const view = Array.isArray(route.query.view) ? route.query.view[0] : route.query.view
  const action = Array.isArray(route.query.action) ? route.query.action[0] : route.query.action
  let handled = false

  if (view === 'rooms') {
    showRoomList()
    handled = true
  }

  if (action === 'create') {
    createDlg.value = true
    handled = true
  }

  if (handled) {
    router.replace({ name: 'Chat' }).catch(() => {})
  }
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
    createDlg.value = false
    createdRoomPending.value = room || null
    createdRoomDialog.value = Boolean(room?.roomId)
    showRoomList()
    showToast('房间已创建，先分享二维码吧', 'success')
  }
  catch (e) { showToast(e.message || '创建失败', 'error') }
}

async function enterCreatedRoom() {
  const roomId = createdRoomPending.value?.roomId
  createdRoomDialog.value = false
  if (!roomId) return
  try {
    await forceLoadRoom(roomId, { reset: true })
    createdRoomPending.value = null
    showToast('已进入房间', 'success')
  }
  catch (e) { showToast(e.message || '进入房间失败', 'error') }
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

async function doLeaveFromPanel() {
  const rid = panelRoomId.value || chat.currentRoomId.value
  if (!rid) return
  return handleRoomAction('leaveRoom', rid)
}

async function doCloseFromPanel() {
  const rid = panelRoomId.value || chat.currentRoomId.value
  if (!rid) return
  return handleRoomAction('closeRoom', rid)
}

// 鈽?瀵嗙爜寮圭獥
async function doExtendRoom({ roomId, duration, option }) {
  try {
    const resp = await apiExtendRoom({ roomId, duration })
    applyRoomRawUpdate(roomId, {
      expireTime: resp?.newExpireTime || resp?.expireTime,
      status: resp?.status,
      statusDesc: resp?.statusDesc
    })
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

async function doUpdateRoomAvatar(avatarUrl = '') {
  const roomId = panelRoomId.value || chat.currentRoomId.value
  if (!roomId) return
  try {
    const resp = await apiUpdateRoomAvatar({ roomId, avatarUrl })
    applyRoomRawUpdate(roomId, {
      avatarUrl: resp?.avatarUrl ?? avatarUrl ?? ''
    })
    if (createdRoomPending.value?.roomId === roomId && createdRoomPending.value) {
      createdRoomPending.value = {
        ...createdRoomPending.value,
        avatarUrl: resp?.avatarUrl ?? avatarUrl ?? ''
      }
    }
    showToast(resp?.avatarUrl ? '房间头像已更新' : '房间头像已清除', 'success')
  } catch (error) {
    showToast(error?.message || '更新房间头像失败', 'error')
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

// 鈽?璐﹀彿鍗囩骇
function onUpgraded(resp) {
  upgradeDlg.value = false
  auth.onAuthRefreshed(resp)
  showToast('升级成功，已获得注册奖励积分', 'success')

  // 鍗囩骇鍚?SaToken loginId 鍙樹簡锛坢ember_X 鈫?user_X锛夛紝闇€瑕侀噸杩?WS
  ws.disconnect()
  const token = auth.getToken()
  if (token) ws.connect(token)
}

// 鈽?鐧诲嚭
async function doLogout() {
  if (!confirm('确定退出登录？')) return
  await auth.logout()
  stopTyping()
  ws.disconnect()
  window.location.reload()
}

// 鈽?娉ㄩ攢璐﹀彿
async function doDeleteAccount() {
  if (!confirm('注销后账号将无法恢复，确定要注销吗？')) return
  if (!confirm('再次确认：你的所有数据（房间、消息、积分）都将永久丢失。')) return
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

// 鈽?涓汉璧勬枡鏇存柊鍚庡埛鏂板綋鍓嶆埧闂存垚鍛?
async function onProfileUpdated() {
  const refreshedMembers = await chat.refreshAllRoomUsers()
  if (chat.currentRoomId.value) {
    curMembers.value = refreshedMembers || []
    if (panelRoomId.value === chat.currentRoomId.value) {
      panelMembers.value = refreshedMembers || []
    }
  }
  // 寮哄埗缁勪欢閲嶆柊娓叉煋锛氶€氳繃淇敼 rooms 寮曠敤瑙﹀彂
  chat.rooms.value = [...chat.rooms.value]
}

// ---- init ----
function updateH() {
  viewH.value = `${Math.max(window.innerHeight - 32, 520)}px`
  mobileViewport.value = window.innerWidth <= 768
}

async function doInit() {
  initFailed.value = false
  try {
    await auth.init()
    const token = auth.getToken()
    if (!token) { initFailed.value = true; return }

    ws.on(WS_TYPE.LOGIN_SUCCESS, async d => {
      auth.setMemberId(d.userId)
      try {
        await chat.loadRooms()
      } finally {
        consumeRouteState()
        await game.handleSocketConnected()

        // 快速进入后提示绑定邮箱
        if (sessionStorage.getItem('fc_quick_entry') === '1') {
          sessionStorage.removeItem('fc_quick_entry')
          const id = auth.identity.value
          if (id && !id.isRegistered) {
            setTimeout(() => {
              showToast('当前为快速进入状态，建议绑定邮箱以保留账号', 'info', 6000)
            }, 2000)
          }
        }
      }
    })
    ws.on(WS_TYPE.CHAT_BROADCAST, chat.onChatBroadcast)
    ws.on(WS_TYPE.USER_JOIN, chat.onUserJoin)
    ws.on(WS_TYPE.USER_LEAVE, chat.onUserLeave)
    ws.on(WS_TYPE.YOU_MUTED, async (d, r) => {
      chat.onYouMuted(d, r)
      if (r) await refreshVisibleMembers(r)
      showToast('你被禁言了', 'warning')
    })
    ws.on(WS_TYPE.YOU_UNMUTED, async (d, r) => {
      chat.onYouUnmuted(d, r)
      if (r) await refreshVisibleMembers(r)
      showToast('禁言已解除')
    })
    ws.on(WS_TYPE.YOU_KICKED, async (d, r) => {
      chat.onYouKicked(d, r)
      if (r && panelRoomId.value === r) closeRoomInfoPanel()
      showToast('你被移出房间', 'error')
    })
    ws.on(WS_TYPE.ROOM_EXPIRING, (d, r) => {
      chat.onRoomExpiring(d, r)
      showToast('房间即将到期，5 分钟后进入宽限期', 'warning')
    })
    ws.on(WS_TYPE.ROOM_GRACE, (d, r) => {
      chat.onRoomGrace(d, r)
      showToast('房间已到期，现在进入 5 分钟宽限期', 'warning', 4200)
    })
    ws.on(WS_TYPE.ROOM_CLOSED, (d, r) => {
      chat.onRoomClosed(d, r)
      if (r && panelRoomId.value === r) closeRoomInfoPanel()
      showToast('房间已关闭')
    })
    ws.on(WS_TYPE.SYSTEM_MSG, d => { if (typeof d === 'string') showToast(d) })
    ws.on(WS_TYPE.MSG_REJECTED, d => showToast(typeof d === 'string' ? d : '消息被拒绝', 'error'))
    ws.on(WS_TYPE.USER_ONLINE, chat.onUserOnline)
    ws.on(WS_TYPE.USER_OFFLINE, chat.onUserOffline)
    ws.on(WS_TYPE.TYPING_STATUS, chat.onTypingStatus)
    ws.on(WS_TYPE.MEMBER_INFO_CHANGED, (d, r) => {
      const refreshedMembers = chat.onMemberInfoChanged(d, r)
      if (r && r === chat.currentRoomId.value) {
        curMembers.value = refreshedMembers || []
        if (panelRoomId.value === r) {
          panelMembers.value = refreshedMembers || []
        }
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
    Object.values(GAME_WS_TYPE).forEach(type => {
      ws.on(type, (d, r) => {
        game.handleWsEvent(type, d, r)
      })
    })

    ws.connect(token)
  } catch (e) {
    console.error('[FlashChat] 初始化失败', e)
    initFailed.value = true
    showToast('初始化失败', 'error')
  }
}

function retryInit() { doInit() }

function mountChatSurface() {
  const wid = setInterval(() => {
    if (chatEl.value) {
      bindEvents()
      injectShadowCSS()
      clearInterval(wid)
    }
  }, 100)
  setTimeout(() => clearInterval(wid), 8000)
}

onMounted(async () => {
  updateH(); window.addEventListener('resize', updateH)
  cdTimer = setInterval(() => chat.refreshRoomCountdowns(), 60000)
  expiryTimer = setInterval(checkExpiry, 20000)
  const chatLibraryTask = ensureChatLibrary()
  await doInit()
  await chatLibraryTask
})

onUnmounted(() => {
  window.removeEventListener('resize', updateH)
  clearInterval(cdTimer); clearInterval(expiryTimer)
  stopTyping()
  ws.disconnect()
})

watch(() => chat.currentRoomId.value, (roomId) => {
  mobileSwipeRoomId.value = ''
  game.enterRoom(roomId || '')
})

watch(mobileViewport, (isMobile) => {
  if (!isMobile) mobileSwipeRoomId.value = ''
})

watch(panelOpen, (open) => {
  if (!open) {
    panelRoomId.value = ''
    panelMembers.value = []
    closeMemberActionConfirm(true)
  }
})

watch([chatLibraryReady, authReady], async ([ready, authLoaded]) => {
  if (!ready || !authLoaded) return
  await nextTick()
  mountChatSurface()
}, { immediate: true })
</script>

<style>
@import '@/styles/variables.css';
</style>

<style scoped>
.fc-root {
  width: 100%;
  height: 100vh;
  padding: 16px;
  background: var(--fc-app-gradient);
  position: relative;
  overflow: hidden;
}

.fc-shell {
  position: relative;
  border-radius: var(--fc-radius-xl);
  border: 1px solid var(--fc-border);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.98) 0%, rgba(252, 247, 239, 0.94) 100%);
  box-shadow: var(--fc-shadow-out), inset 0 1px 0 rgba(255, 255, 255, 0.82);
  overflow: hidden;
  isolation: isolate;
}

.fc-shell-glow,
.fc-shell-noise {
  display: none;
}

.fc-shell > vue-advanced-chat,
.fc-shell > .fc-room-banner,
.fc-shell > .fc-room-lockbar {
  position: relative;
  z-index: 1;
}

.fc-chat-boot {
  position: relative;
  z-index: 1;
  height: 100%;
  display: grid;
  grid-template-columns: minmax(280px, 320px) minmax(0, 1fr);
  gap: 18px;
  padding: 18px;
}

.fc-chat-boot-rail,
.fc-chat-boot-stage,
.fc-chat-boot-compose,
.fc-chat-boot-room,
.fc-chat-boot-bubble {
  position: relative;
  overflow: hidden;
  border: 1px solid var(--fc-border);
  background: var(--fc-surface);
}

.fc-chat-boot-rail {
  border-radius: 32px;
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.fc-chat-boot-stage {
  border-radius: 36px;
  padding: 22px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-width: 0;
}

.fc-chat-boot-rail-head,
.fc-chat-boot-stage-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 8px;
}

.fc-chat-boot-rail-head strong,
.fc-chat-boot-stage-head strong,
.fc-chat-boot-fail strong {
  display: block;
  margin-top: 10px;
  font-family: var(--fc-font-display);
  font-size: clamp(20px, 2.4vw, 26px);
  line-height: 1.1;
  color: var(--fc-text);
}

.fc-chat-boot-kicker {
  display: inline-flex;
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.fc-chat-boot-room {
  border-radius: 24px;
  padding: 14px;
  display: flex;
  align-items: center;
  gap: 12px;
}

.fc-chat-boot-room-avatar,
.fc-chat-boot-bubble em,
.fc-chat-boot-bubble i,
.fc-chat-boot-room-copy em,
.fc-chat-boot-room-copy i,
.fc-chat-boot-compose span,
.fc-chat-boot-compose-btn {
  display: block;
  background: linear-gradient(90deg, rgba(233, 219, 201, 0.62), rgba(255, 255, 255, 0.86), rgba(233, 219, 201, 0.62));
  background-size: 200% 100%;
  animation: fc-boot-shimmer 1.7s linear infinite;
}

.fc-chat-boot-room-avatar {
  width: 48px;
  height: 48px;
  border-radius: 18px;
  flex-shrink: 0;
}

.fc-chat-boot-room-copy {
  display: flex;
  flex-direction: column;
  gap: 10px;
  flex: 1;
}

.fc-chat-boot-room-copy em {
  width: 64%;
  height: 15px;
  border-radius: 999px;
}

.fc-chat-boot-room-copy i {
  width: 42%;
  height: 11px;
  border-radius: 999px;
}

.fc-chat-boot-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.fc-chat-boot-pills span {
  padding: 7px 12px;
  border-radius: var(--fc-radius-pill);
  border: 1px solid var(--fc-border);
  background: var(--fc-surface);
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
  color: var(--fc-accent);
}

.fc-chat-boot-stream {
  display: flex;
  flex-direction: column;
  gap: 14px;
  flex: 1;
  justify-content: center;
  padding: 14px 0;
}

.fc-chat-boot-bubble {
  width: min(420px, 72%);
  min-height: 88px;
  padding: 16px;
  border-radius: 28px;
}

.fc-chat-boot-bubble.is-me {
  align-self: flex-end;
  background: var(--fc-bg-dark);
}

.fc-chat-boot-bubble.is-other {
  align-self: flex-start;
}

.fc-chat-boot-bubble em {
  width: 58%;
  height: 14px;
  border-radius: 999px;
}

.fc-chat-boot-bubble i {
  width: 86%;
  height: 11px;
  border-radius: 999px;
  margin-top: 12px;
}

.fc-chat-boot-compose {
  border-radius: 24px;
  padding: 14px;
  display: flex;
  align-items: center;
  gap: 14px;
}

.fc-chat-boot-compose span {
  height: 46px;
  border-radius: 999px;
  flex: 1;
}

.fc-chat-boot-compose-btn {
  width: 52px;
  height: 52px;
  border-radius: 20px;
  flex-shrink: 0;
}

.fc-chat-boot-fail {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  text-align: center;
}

.fc-chat-boot-fail p {
  max-width: 420px;
  margin: 0;
  font-family: var(--fc-font);
  font-size: 14px;
  line-height: 1.7;
  color: var(--fc-text-sec);
}

@keyframes fc-boot-shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

.fc-loading {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 20px;
}

.fc-pulse {
  width: 56px;
  height: 56px;
  border-radius: 20px;
  border: 1px solid var(--fc-border);
  background: var(--fc-surface);
  position: relative;
  animation: pulse 1.8s ease-in-out infinite;
}

.fc-pulse::after {
  content: '';
  position: absolute;
  left: 50%;
  top: 50%;
  width: 12px;
  height: 12px;
  border-radius: 999px;
  transform: translate(-50%, -50%);
  background: var(--fc-accent);
  box-shadow: 0 0 0 5px rgba(182, 118, 57, 0.18);
  display: flex;
  align-items: center;
  justify-content: center;
}

@keyframes pulse {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-2px); }
}

@media (prefers-reduced-motion: reduce) {
  .fc-chat-boot-room-avatar,
  .fc-chat-boot-bubble em,
  .fc-chat-boot-bubble i,
  .fc-chat-boot-room-copy em,
  .fc-chat-boot-room-copy i,
  .fc-chat-boot-compose span,
  .fc-chat-boot-compose-btn,
  .fc-pulse {
    animation: none !important;
  }
}

.fc-load-text { font-family: var(--fc-font); font-size: 14px; color: var(--fc-text-sec); }
.fc-danger { color: var(--fc-danger); font-weight: 600; }
.fc-load-fail { display: flex; flex-direction: column; align-items: center; gap: 6px; }
.fc-load-hint { font-family: var(--fc-font); font-size: 12px; color: var(--fc-text-muted); }

.fc-retry {
  margin-top: 12px;
  padding: 11px 28px;
  border: 1px solid transparent;
  border-radius: var(--fc-radius-pill);
  background: var(--fc-accent);
  color: #fff;
  font-family: var(--fc-font);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  box-shadow: 0 10px 18px rgba(151, 90, 38, 0.16);
  transition: background var(--fc-duration-normal) var(--fc-ease-in-out), box-shadow var(--fc-duration-normal) var(--fc-ease-in-out);
}

.fc-retry:hover {
  background: var(--fc-accent-strong);
  box-shadow: 0 14px 24px rgba(151, 90, 38, 0.2);
}

.fc-toast {
  position: fixed;
  top: 26px;
  left: 50%;
  transform: translateX(-50%);
  padding: 12px 22px;
  border: 1px solid var(--fc-border);
  border-radius: var(--fc-radius-pill);
  font-family: var(--fc-font);
  font-size: 13px;
  font-weight: 500;
  z-index: 10000;
  pointer-events: none;
  box-shadow: var(--fc-shadow-soft);
}

.fc-toast-with-panel {
  top: calc(74px + env(safe-area-inset-top));
}

.fc-toast-info { background: var(--fc-surface); color: var(--fc-text); }
.fc-toast-success { background: var(--fc-surface); color: var(--fc-success); }
.fc-toast-warning { background: var(--fc-surface); color: var(--fc-warn); }
.fc-toast-error { background: var(--fc-surface); color: var(--fc-danger); }
.toast-enter-active, .toast-leave-active { transition: all var(--fc-duration-normal) var(--fc-ease-in-out); }
.toast-enter-from { opacity: 0; transform: translateX(-50%) translateY(-16px); }
.toast-leave-to { opacity: 0; transform: translateX(-50%) translateY(-16px); }

.fc-confirm-mask {
  position: fixed;
  inset: 0;
  background: var(--fc-backdrop);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  z-index: 10020;
}

.fc-confirm-card {
  width: min(460px, 100%);
  padding: 24px;
  border-radius: var(--fc-radius-lg);
  border: 1px solid var(--fc-border);
  background: var(--fc-surface);
  box-shadow: var(--fc-shadow-panel);
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
  font-family: var(--fc-font-display);
  font-size: 20px;
  line-height: 1.15;
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
  border: 1px solid var(--fc-border);
  font-family: var(--fc-font);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: border-color var(--fc-duration-normal) var(--fc-ease-in-out), background var(--fc-duration-normal) var(--fc-ease-in-out), color var(--fc-duration-normal) var(--fc-ease-in-out), box-shadow var(--fc-duration-normal) var(--fc-ease-in-out);
}

.fc-confirm-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.fc-confirm-btn-ghost {
  background: var(--fc-surface);
  color: var(--fc-text);
}

.fc-confirm-btn-ghost:hover {
  border-color: var(--fc-border-strong);
  background: var(--fc-bg-light);
  box-shadow: 0 0 0 3px rgba(182, 118, 57, 0.08);
}

.fc-confirm-btn-danger {
  background: var(--fc-danger);
  border-color: transparent;
  color: #fff;
}

.fc-confirm-btn-danger:hover {
  opacity: 0.9;
}

.fc-retry:focus-visible,
.fc-confirm-btn:focus-visible,
.fc-room-lockbar-btn:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--fc-focus-ring);
}

.fc-confirm-btn-danger:focus-visible {
  box-shadow: 0 0 0 3px rgba(187, 106, 94, 0.18);
}

.confirm-enter-active, .confirm-leave-active {
  transition: opacity var(--fc-duration-normal) var(--fc-ease-in-out);
}

.confirm-enter-active .fc-confirm-card,
.confirm-leave-active .fc-confirm-card {
  transition: transform var(--fc-duration-normal) var(--fc-ease-in-out), opacity var(--fc-duration-normal) var(--fc-ease-in-out);
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

.fc-room-banner {
  position: absolute;
  top: 18px;
  right: 18px;
  width: min(340px, calc(100% - 36px));
  padding: 16px;
  border-radius: var(--fc-radius);
  border: 1px solid var(--fc-border);
  box-shadow: var(--fc-shadow-soft);
  z-index: 4;
  overflow: hidden;
}

.fc-room-banner.is-expiring {
  background: rgba(255, 244, 228, 0.96);
  color: var(--fc-warn);
}

.fc-room-banner.is-grace,
.fc-room-banner.is-closed {
  background: rgba(255, 243, 240, 0.96);
  color: var(--fc-danger);
}

.fc-room-banner.is-muted {
  background: rgba(244, 239, 233, 0.96);
  color: var(--fc-text-sec);
}

.fc-room-banner-head {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.fc-room-banner-kicker {
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  opacity: 0.7;
}

.fc-room-banner-status {
  flex-shrink: 0;
  padding: 7px 12px;
  border-radius: var(--fc-radius-pill);
  border: 1px solid var(--fc-border);
  background: rgba(255, 255, 255, 0.72);
  font-family: var(--fc-font);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.fc-room-banner-title {
  margin-top: 8px;
  font-family: var(--fc-font-display);
  font-size: 18px;
  line-height: 1.15;
  font-weight: 600;
}

.fc-room-banner-text {
  position: relative;
  z-index: 1;
  margin-top: 8px;
  font-family: var(--fc-font);
  font-size: 13px;
  line-height: 1.6;
}

.fc-room-banner-tags {
  position: relative;
  z-index: 1;
  margin-top: 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.fc-room-banner-tags span {
  padding: 6px 10px;
  border-radius: var(--fc-radius-pill);
  border: 1px solid var(--fc-border);
  background: rgba(255, 255, 255, 0.7);
  font-family: var(--fc-font);
  font-size: 12px;
  color: currentColor;
}

.fc-room-lockbar {
  position: absolute;
  left: 18px;
  right: 18px;
  bottom: 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 18px;
  border-radius: var(--fc-radius);
  border: 1px solid var(--fc-border);
  background: rgba(255, 255, 255, 0.92);
  box-shadow: var(--fc-shadow-soft);
  z-index: 4;
  overflow: hidden;
}

.fc-room-lockbar.is-grace,
.fc-room-lockbar.is-closed {
  border-color: rgba(184, 96, 75, 0.14);
}

.fc-room-lockbar.is-muted {
  border-color: var(--fc-border);
}

.fc-room-lockbar-mark {
  flex-shrink: 0;
  align-self: stretch;
  min-width: 86px;
  padding: 12px 10px;
  border-radius: 14px;
  border: 1px solid var(--fc-border);
  background: var(--fc-bg);
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--fc-font-display);
  font-size: 15px;
  line-height: 1;
  letter-spacing: 0.04em;
  color: currentColor;
}

.fc-room-lockbar-copy {
  position: relative;
  z-index: 1;
  min-width: 0;
  flex: 1;
}

.fc-room-lockbar-title {
  font-family: var(--fc-font-display);
  font-size: 18px;
  line-height: 1.15;
  color: var(--fc-text);
}

.fc-room-lockbar-text {
  margin-top: 8px;
  font-family: var(--fc-font);
  font-size: 13px;
  line-height: 1.6;
  color: var(--fc-text-sec);
}

.fc-room-lockbar-tags {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.fc-room-lockbar-tags span {
  padding: 6px 10px;
  border-radius: var(--fc-radius-pill);
  border: 1px solid var(--fc-border);
  background: var(--fc-bg);
  font-family: var(--fc-font);
  font-size: 12px;
  color: var(--fc-text-sec);
}

.fc-room-lockbar-btn {
  flex-shrink: 0;
  min-width: 104px;
  padding: 11px 18px;
  border: 1px solid var(--fc-border);
  border-radius: 999px;
  background: var(--fc-bg-light);
  color: var(--fc-text);
  font-family: var(--fc-font);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: border-color var(--fc-duration-normal) var(--fc-ease-in-out), background var(--fc-duration-normal) var(--fc-ease-in-out), box-shadow var(--fc-duration-normal) var(--fc-ease-in-out);
}

.fc-room-lockbar-btn:hover {
  border-color: var(--fc-border-strong);
  background: #fffdf8;
  box-shadow: 0 0 0 3px rgba(182, 118, 57, 0.08);
}

.room-banner-enter-active,
.room-banner-leave-active,
.room-lockbar-enter-active,
.room-lockbar-leave-active {
  transition: all var(--fc-duration-normal) var(--fc-ease-in-out);
}

.room-banner-enter-from,
.room-banner-leave-to {
  opacity: 0;
  transform: translateY(-10px);
}

.room-lockbar-enter-from,
.room-lockbar-leave-to {
  opacity: 0;
  transform: translateY(12px);
}

@media (max-width: 768px) {
  .fc-root { padding: 10px; }
  .fc-chat-boot {
    grid-template-columns: 1fr;
    gap: 14px;
    padding: 14px;
  }
  .fc-chat-boot-rail,
  .fc-chat-boot-stage {
    border-radius: 28px;
    padding: 18px;
  }
  .fc-chat-boot-stage-head,
  .fc-chat-boot-rail-head {
    flex-direction: column;
  }
  .fc-chat-boot-stage-head strong,
  .fc-chat-boot-rail-head strong,
  .fc-chat-boot-fail strong {
    font-size: 20px;
  }
  .fc-chat-boot-bubble {
    width: min(100%, 320px);
    min-height: 76px;
    padding: 14px;
  }
  .fc-chat-boot-compose {
    padding-bottom: calc(14px + env(safe-area-inset-bottom));
  }
  .fc-toast {
    top: calc(18px + env(safe-area-inset-top));
    max-width: calc(100vw - 32px);
    padding: 12px 18px;
    white-space: normal;
    text-align: center;
  }
  .fc-toast-with-panel {
    top: calc(72px + env(safe-area-inset-top));
  }
  .fc-confirm-card {
    padding: 20px;
    border-radius: 22px;
  }
  .fc-confirm-title {
    font-size: 18px;
  }
  .fc-confirm-actions {
    flex-direction: column-reverse;
  }
  .fc-confirm-btn {
    width: 100%;
  }
  .fc-room-banner {
    top: 12px;
    right: 12px;
    left: 12px;
    width: auto;
  }
  .fc-room-lockbar {
    left: 12px;
    right: 12px;
    bottom: calc(12px + env(safe-area-inset-bottom));
    flex-direction: column;
    align-items: stretch;
  }
  .fc-room-lockbar-mark {
    min-width: 0;
    align-self: auto;
  }
  .fc-room-lockbar-title {
    font-size: 17px;
  }
  .fc-room-lockbar-btn {
    width: 100%;
  }
}
</style>
