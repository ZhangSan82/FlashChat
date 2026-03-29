import { ref, reactive } from 'vue'
import * as chatApi from '@/api/chat'
import * as roomApi from '@/api/room'
import { uploadFile } from '@/api/file'
import { formatTime, formatDate, formatCountdownShort } from '@/utils/formatter'
import { resolveAvatar, resolveBackendAvatar } from '@/utils/avatar'

/**
 * @param {Function} getMemberId    - () => t_account.id（数字）
 * @param {Function} getCurrentUser - () => { nickname, avatarColor, avatarUrl }
 */
export function useChat(getMemberId, getCurrentUser) {
    const HIDDEN_MESSAGES_KEY = 'flashchat_hidden_messages_v1'

    const rooms = ref([])
    const roomsRaw = ref([])
    const messages = ref([])
    const currentRoomId = ref(null)
    const loadingRooms = ref(false)
    const roomsLoaded = ref(false)
    const messagesLoaded = ref(false)
    const unreadMap = reactive({})
    const roomUsersMap = reactive({})
    const typingUsersMap = reactive({})
    const hiddenMessageMap = reactive(loadHiddenMessageState())
    const msgCursorMap = reactive({})
    const lastRealMsgMap = reactive({})
    let messageLoadSeq = 0
    const typingTimers = new Map()

    function loadHiddenMessageState() {
        try {
            const raw = localStorage.getItem(HIDDEN_MESSAGES_KEY)
            return raw ? JSON.parse(raw) : {}
        } catch {
            return {}
        }
    }

    function persistHiddenMessageState() {
        try {
            const payload = Object.fromEntries(
                Object.entries(hiddenMessageMap).filter(([, ids]) => Array.isArray(ids) && ids.length > 0)
            )
            localStorage.setItem(HIDDEN_MESSAGES_KEY, JSON.stringify(payload))
        } catch {}
    }

    // ================================================================
    //  房间
    // ================================================================

    async function loadRooms(preferredRoomId = null) {
        const memberId = getMemberId()
        if (!memberId) return currentRoomId.value ? (roomUsersMap[currentRoomId.value] || []) : []

        loadingRooms.value = true
        try {
            const [list, unreads] = await Promise.all([
                roomApi.getMyRooms(),
                chatApi.getUnreadCounts()
            ])
            roomsRaw.value = list || []
            Object.assign(unreadMap, unreads || {})
            rooms.value = roomsRaw.value.map(r => transformRoom(r))
            roomsLoaded.value = true

            const targetRoomId = preferredRoomId || sessionStorage.getItem('fc_last_room')
            if (targetRoomId && roomsRaw.value.find(r => r.roomId === targetRoomId)) {
                currentRoomId.value = targetRoomId
                sessionStorage.setItem('fc_last_room', targetRoomId)
            }
        } catch (e) {
            console.error('[Chat] 加载房间失败', e)
        } finally {
            loadingRooms.value = false
        }
    }

    /**
     * ★ 获取当前用户在 users 数组中的头像
     * 使用统一解析器，确保和 ProfilePanel / SideDrawer 一致
     */
    function getCurrentUserAvatar() {
        const user = getCurrentUser()
        if (!user) return resolveAvatar(null, '#C8956C', '我')
        return resolveAvatar(user.avatarUrl, user.avatarColor, user.nickname)
    }

    function getCurrentUserName() {
        const user = getCurrentUser()
        return user?.nickname || '我'
    }

    function buildCurrentUserEntry(memberId = getMemberId()) {
        if (!memberId) return null

        const username = getCurrentUserName()
        const avatar = getCurrentUserAvatar()

        return {
            _id: String(memberId),
            username,
            avatar,
            _raw: {
                accountId: memberId,
                nickname: username,
                avatar
            }
        }
    }

    function mergeCurrentUserIntoUsers(users = []) {
        const currentUserEntry = buildCurrentUserEntry()
        if (!currentUserEntry) return users

        const index = users.findIndex(user => user._id === currentUserEntry._id)
        if (index === -1) {
            return [...users, currentUserEntry]
        }

        return users.map((user, idx) => {
            if (idx !== index) return user
            return {
                ...user,
                username: currentUserEntry.username,
                avatar: currentUserEntry.avatar,
                _raw: {
                    ...(user._raw || {}),
                    ...currentUserEntry._raw
                }
            }
        })
    }

    function transformRoom(room) {
        const memberId = getMemberId()

        let users = mergeCurrentUserIntoUsers(roomUsersMap[room.roomId] || [])

        // ★ 当前用户使用真实头像
        if (memberId && !users.find(u => u._id === String(memberId))) {
            users = [
                ...users,
                {
                    _id: String(memberId),
                    username: getCurrentUserName(),
                    avatar: getCurrentUserAvatar()
                }
            ]
        }

        // 确保 >= 3 个 user
        while (users.length < 3) {
            users = [...users, {
                _id: `_placeholder_${users.length}`,
                username: '',
                avatar: resolveAvatar(null, '#B5B0A8', '?')
            }]
        }

        let countdown = ''
        if (room.expireTime) {
            const remain = new Date(room.expireTime).getTime() - Date.now()
            countdown = remain > 0 ? ' \u23F3 ' + formatCountdownShort(remain) : ' \u23F3 已过期'
        } else {
            countdown = '∞'
        }

        const lastReal = lastRealMsgMap[room.roomId]
        const preview = lastReal
            ? (lastReal.username ? `${lastReal.username}: ${lastReal.content}` : lastReal.content)
            : (countdown !== '∞' ? countdown : '')

        return {
            roomId: room.roomId,
            roomName: room.title || room.roomId,
            avatar: resolveAvatar(null, '#C8956C', room.title || '?'),
            unreadCount: unreadMap[room.roomId] || 0,
            index: room.createTime ? new Date(room.createTime).getTime() : 0,
            users,
            typingUsers: typingUsersMap[room.roomId] || [],
            lastMessage: {
                content: preview,
                senderId: lastReal?.senderId || '',
                username: '',
                timestamp: countdown,
                saved: true, distributed: true, seen: true, new: false
            },
            _raw: room
        }
    }

    function updateRoomInList(roomId, updater) {
        const idx = rooms.value.findIndex(r => r.roomId === roomId)
        if (idx > -1) {
            rooms.value = [
                ...rooms.value.slice(0, idx),
                { ...rooms.value[idx], ...updater },
                ...rooms.value.slice(idx + 1)
            ]
        }
    }

    function refreshRoomCountdowns() {
        rooms.value = roomsRaw.value.map(r => transformRoom(r))
    }

    function getMessageIndexId(message) {
        return message?.indexId || message?._raw?.indexId || null
    }

    function getMessagePreviewContent(message) {
        if (!message) return ''
        if (message.deleted) return message.content || '此消息已撤回'
        return message.content || '[文件]'
    }

    function rememberLastMessage(roomId, message) {
        if (!roomId || !message || message.system) return
        lastRealMsgMap[roomId] = {
            indexId: getMessageIndexId(message),
            content: getMessagePreviewContent(message),
            username: message.username,
            senderId: message.senderId
        }
    }

    function rebuildRoomPreview(roomId, fallbackContent = '') {
        if (!roomId) return false

        if (roomId === currentRoomId.value) {
            const lastMessage = [...messages.value].reverse().find(message => !message.system) || null
            if (lastMessage) rememberLastMessage(roomId, lastMessage)
            else delete lastRealMsgMap[roomId]
        } else if (fallbackContent && lastRealMsgMap[roomId]) {
            lastRealMsgMap[roomId] = {
                ...lastRealMsgMap[roomId],
                content: fallbackContent
            }
        }

        refreshRoomCountdowns()
    }

    function clearTypingTimer(roomId, userId) {
        const key = `${roomId}:${userId}`
        const timer = typingTimers.get(key)
        if (timer) {
            clearTimeout(timer)
            typingTimers.delete(key)
        }
    }

    function setTypingUser(roomId, userId, typing) {
        if (!roomId || !userId) return

        const key = String(userId)
        const current = typingUsersMap[roomId] || []

        if (typing) {
            if (!current.includes(key)) {
                typingUsersMap[roomId] = [...current, key]
            }
            clearTypingTimer(roomId, key)
            typingTimers.set(`${roomId}:${key}`, setTimeout(() => {
                setTypingUser(roomId, key, false)
            }, 3500))
        } else {
            clearTypingTimer(roomId, key)
            if (current.includes(key)) {
                typingUsersMap[roomId] = current.filter(id => id !== key)
            }
        }

        refreshRoomCountdowns()
    }

    function clearRoomTyping(roomId) {
        if (!roomId) return
        const typingUsers = typingUsersMap[roomId] || []
        typingUsers.forEach(userId => clearTypingTimer(roomId, userId))
        delete typingUsersMap[roomId]
        refreshRoomCountdowns()
    }

    function removeRoomState(roomId) {
        if (!roomId) return
        clearRoomTyping(roomId)
        delete roomUsersMap[roomId]
        delete msgCursorMap[roomId]
        delete lastRealMsgMap[roomId]
        delete unreadMap[roomId]
    }

    function isMessageHiddenForSelf(roomId, messageOrId) {
        if (!roomId) return false
        const rawId = typeof messageOrId === 'object'
            ? getMessageIndexId(messageOrId)
            : messageOrId
        if (!rawId) return false
        return (hiddenMessageMap[roomId] || []).includes(String(rawId))
    }

    function filterHiddenMessages(roomId, list = []) {
        if (!roomId || !list.length) return list
        return list.filter(message => !isMessageHiddenForSelf(roomId, message))
    }

    function hideMessageForSelf(message, roomId = currentRoomId.value) {
        const msgId = getMessageIndexId(message)
        if (!roomId || !msgId) throw new Error('无法定位消息')
        if (message?.system) throw new Error('系统消息不能删除')

        const nextIds = Array.from(new Set([...(hiddenMessageMap[roomId] || []), String(msgId)]))
        hiddenMessageMap[roomId] = nextIds
        persistHiddenMessageState()

        if (roomId === currentRoomId.value) {
            messages.value = messages.value.filter(item => getMessageIndexId(item) !== msgId)
        }

        rebuildRoomPreview(roomId, '消息已删除')
        return true
    }

    function isCurrentUserHost(roomId = currentRoomId.value) {
        const memberId = getMemberId()
        if (!roomId || !memberId) return false

        const currentUser = (roomUsersMap[roomId] || []).find(user => user._id === String(memberId))
        return Boolean(currentUser?._raw?.isHost || currentUser?._raw?.role === 1)
    }

    function canRecallMessage(message) {
        if (!message || message.system || message.deleted) return false
        const currentMemberId = getMemberId()
        if (!currentMemberId) return false
        return String(message.senderId || '') === String(currentMemberId)
    }

    function canDeleteMessage(message, roomId = currentRoomId.value) {
        if (!message || message.system || message.deleted) return false
        return isCurrentUserHost(roomId)
    }

    // ================================================================
    //  消息
    // ================================================================

    async function fetchHistoryPage(roomId, cursor, pageSize, requestId) {
        let resp = await chatApi.getHistoryMessages(roomId, cursor, pageSize)

        while (
            resp &&
            (resp.list?.length || 0) === 0 &&
            resp.isLast === false &&
            resp.cursor
        ) {
            if (requestId !== messageLoadSeq || currentRoomId.value !== roomId) {
                return null
            }
            resp = await chatApi.getHistoryMessages(roomId, resp.cursor, pageSize)
        }

        return resp
    }

    async function loadMessages(roomId, options = {}) {
        if (!roomId) return
        const requestId = ++messageLoadSeq
        const shouldReset = options.reset === true || currentRoomId.value !== roomId
        currentRoomId.value = roomId
        sessionStorage.setItem('fc_last_room', roomId)

        if (shouldReset) {
            messages.value = []
            messagesLoaded.value = false
            delete msgCursorMap[roomId]
        }

        try {
            const cursor = msgCursorMap[roomId] || null
            const resp = await fetchHistoryPage(roomId, cursor, 20, requestId)
            if (requestId !== messageLoadSeq || currentRoomId.value !== roomId) return
            if (!resp) { messagesLoaded.value = true; return }

            const newMsgs = (resp.list || []).map(msg => transformMessage(msg, roomId))

            let ordered = newMsgs
            if (newMsgs.length >= 2) {
                const firstIdx = newMsgs[0].indexId || 0
                const lastIdx = newMsgs[newMsgs.length - 1].indexId || 0
                if (firstIdx > lastIdx) {
                    ordered = [...newMsgs].reverse()
                }
            }

            ordered = filterHiddenMessages(roomId, ordered)

            if (shouldReset) {
                messages.value = ordered
            } else {
                const ids = new Set(messages.value.map(m => m._id))
                const unique = ordered.filter(m => !ids.has(m._id))
                messages.value = [...unique, ...messages.value]
            }

            if (resp.cursor) msgCursorMap[roomId] = resp.cursor
            else delete msgCursorMap[roomId]

            syncMessagesWithRoomUsers(roomId)
            messagesLoaded.value = resp.isLast !== false || ordered.length === 0
            await doAck(roomId)
        } catch (e) {
            if (requestId !== messageLoadSeq || currentRoomId.value !== roomId) return
            console.error('[Chat] 加载消息失败', e)
            messagesLoaded.value = true
        }
    }

    /**
     * ★ 加载房间成员 — 头像统一解析
     *
     * 对于当前用户：使用本地已知的 avatarUrl（上传的头像）
     * 对于其他用户：使用后端返回的 avatar 字段（颜色值或 URL）
     */
    async function loadRoomUsers(roomId) {
        try {
            const members = await roomApi.getRoomMembers(roomId)
            const memberId = getMemberId()
            const currentUser = getCurrentUser()

            const users = (members || []).map(m => {
                const isSelf = memberId && String(m.accountId) === String(memberId)

                let avatarDisplay
                let usernameDisplay
                if (isSelf && currentUser) {
                    // ★ 当前用户：用本地完整信息（包含 avatarUrl）
                    avatarDisplay = resolveAvatar(currentUser.avatarUrl, currentUser.avatarColor, currentUser.nickname)
                    usernameDisplay = currentUser.nickname || m.nickname || '鍖垮悕'
                } else {
                    // 其他用户：用后端返回的 avatar 字段
                    avatarDisplay = resolveBackendAvatar(m.avatar, m.nickname)
                    usernameDisplay = m.nickname || '鍖垮悕'
                }

                return {
                    _id: String(m.accountId),
                    username: usernameDisplay,
                    avatar: avatarDisplay,
                    status: { state: m.isOnline ? 'online' : 'offline' },
                    _raw: {
                        ...m,
                        nickname: usernameDisplay,
                        avatar: avatarDisplay
                    }
                }
            })

            const mergedUsers = mergeCurrentUserIntoUsers(users)
            roomUsersMap[roomId] = mergedUsers

            // 确保当前用户在列表中
            if (memberId && !users.find(u => u._id === String(memberId))) {
                users.push({
                    _id: String(memberId),
                    username: getCurrentUserName(),
                    avatar: getCurrentUserAvatar()
                })
            }

            updateRoomInList(roomId, { users: mergedUsers })
            syncMessagesWithRoomUsers(roomId)
            return mergedUsers
        } catch (e) {
            console.error('[Chat] 加载成员失败', e)
            return []
        }
    }

    function getRoomUserDisplay(roomId, senderId) {
        if (!roomId || senderId == null) return null
        const users = roomUsersMap[roomId] || []
        return users.find(user => user._id === String(senderId)) || null
    }

    function syncMessagesWithRoomUsers(roomId) {
        if (!roomId || currentRoomId.value !== roomId || !messages.value.length) return

        const users = roomUsersMap[roomId] || []
        if (!users.length) return

        const userMap = new Map(
            users
                .filter(user => user && user._id && !String(user._id).startsWith('_placeholder_'))
                .map(user => [String(user._id), user])
        )

        let changed = false
        const nextMessages = messages.value.map(message => {
            if (message.system) return message

            const displayUser = userMap.get(String(message.senderId || ''))
            if (!displayUser) return message

            const nextUsername = displayUser.username || message.username
            const nextAvatar = displayUser.avatar || message.avatar
            if (message.username === nextUsername && message.avatar === nextAvatar) {
                return message
            }

            changed = true
            return {
                ...message,
                username: nextUsername,
                avatar: nextAvatar,
                _raw: {
                    ...(message._raw || {}),
                    username: nextUsername,
                    avatar: displayUser._raw?.avatar ?? message._raw?.avatar ?? nextAvatar
                }
            }
        })

        if (changed) {
            messages.value = nextMessages
        }
    }

    /**
     * ★ 消息转换 — 头像统一解析
     *
     * vue-advanced-chat 渲染消息时会用 users 数组匹配 senderId 来获取 avatar，
     * 所以消息上的 avatar 字段其实是备用值（users 匹配不到时才用）。
     * 修复 users 数组中的头像才是关键。
     */
    function transformMessage(msg, roomId = currentRoomId.value) {
        const ts = msg.timestamp || Date.now()
        const senderId = msg.senderId != null ? String(msg.senderId) : ''
        const currentMemberId = getMemberId()
        const isSelf = currentMemberId && senderId === String(currentMemberId)
        const roomUser = isSelf ? null : getRoomUserDisplay(roomId, senderId)
        const usernameDisplay = isSelf
            ? getCurrentUserName()
            : (roomUser?.username || msg.username || '匿名')
        const avatarDisplay = isSelf
            ? getCurrentUserAvatar()
            : (roomUser?.avatar || resolveBackendAvatar(msg.avatar, usernameDisplay))

        return {
            _id: msg._id || `msg-${msg.indexId || Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
            indexId: msg.indexId,
            content: msg.content || '',
            senderId: senderId,
            username: usernameDisplay,
            avatar: avatarDisplay,
            date: formatDate(ts),
            timestamp: formatTime(ts),
            system: msg.system || false,
            deleted: msg.deleted || false,
            files: msg.files || null,
            replyMessage: msg.replyMessage || null,
            reactions: msg.reactions || null,
            _raw: {
                ...msg,
                username: usernameDisplay,
                avatar: roomUser?._raw?.avatar ?? msg.avatar ?? avatarDisplay
            }
        }
    }

    // ================================================================
    //  发送消息
    // ================================================================

    async function sendMessage({ content, files, replyMessage }) {
        const roomId = currentRoomId.value
        if (!roomId) return

        try {
            let uploaded = null
            if (files?.length) {
                uploaded = await Promise.all(files.map(async f => {
                    const dto = await uploadFile(f.blob)
                    if (f.audio) dto.audio = true
                    if (f.duration) dto.duration = f.duration
                    return dto
                }))
            }

            await chatApi.sendMessage({
                roomId,
                content: content || '',
                files: uploaded,
                replyMsgId: replyMessage?.indexId || replyMessage?._raw?.indexId || null
            })
            return true
        } catch (e) {
            console.error('[Chat] 发送失败', e)
        }
    }

    // ================================================================
    //  ACK
    // ================================================================

    let ackTimer = null

    async function doAck(roomId) {
        if (!roomId) return
        const last = messages.value[messages.value.length - 1]
        const lastId = last?.indexId || last?._raw?.indexId
        if (!lastId) return

        clearTimeout(ackTimer)
        ackTimer = setTimeout(async () => {
            try {
                await chatApi.ackMessages({ roomId, lastMsgId: lastId })
                if (unreadMap[roomId]) {
                    unreadMap[roomId] = 0
                    updateRoomInList(roomId, { unreadCount: 0 })
                }
            } catch {}
        }, 1000)
    }

    // ================================================================
    //  WS 事件处理
    // ================================================================

    function onChatBroadcast(data, roomId) {
        const msg = transformMessage(data, roomId)

        if (!msg.system) {
            lastRealMsgMap[roomId] = {
                content: msg.content || '[文件]',
                username: msg.username,
                senderId: msg.senderId
            }
        }

        if (roomId === currentRoomId.value) {
            const dup = messages.value.some(m =>
                (m._id && m._id === msg._id) ||
                (m.indexId && msg.indexId && m.indexId === msg.indexId)
            )
            if (!dup) messages.value = [...messages.value, msg]
            doAck(roomId)
        } else {
            unreadMap[roomId] = (unreadMap[roomId] || 0) + 1
            updateRoomInList(roomId, { unreadCount: unreadMap[roomId] })
        }

        updateRoomInList(roomId, {
            lastMessage: {
                content: msg.system ? '系统消息' : (msg.content || '[文件]'),
                senderId: msg.senderId,
                username: msg.username,
                timestamp: msg.timestamp,
                saved: true, distributed: true,
                seen: roomId === currentRoomId.value,
                new: roomId !== currentRoomId.value
            }
        })
    }

    function onUserJoin(data, roomId) {
        loadRoomUsers(roomId)
        if (roomId === currentRoomId.value) {
            messages.value = [...messages.value, {
                _id: `sys-join-${Date.now()}`, system: true,
                content: `${data?.nickname || '某人'} 加入了房间`,
                date: formatDate(Date.now()), timestamp: formatTime(Date.now())
            }]
        }
    }

    function onUserLeave(data, roomId) {
        loadRoomUsers(roomId)
        if (roomId === currentRoomId.value) {
            messages.value = [...messages.value, {
                _id: `sys-leave-${Date.now()}`, system: true,
                content: `${data?.nickname || '某人'} 离开了房间`,
                date: formatDate(Date.now()), timestamp: formatTime(Date.now())
            }]
        }
    }

    function onYouMuted() {}
    function onYouUnmuted() {}

    function onYouKicked(_, roomId) {
        rooms.value = rooms.value.filter(r => r.roomId !== roomId)
        roomsRaw.value = roomsRaw.value.filter(r => r.roomId !== roomId)
        if (currentRoomId.value === roomId) { currentRoomId.value = null; messages.value = [] }
    }

    function onRoomExpiring(_, roomId) {
        const r = roomsRaw.value.find(x => x.roomId === roomId)
        if (r) r.status = 2
    }

    function onRoomGrace(_, roomId) {
        const r = roomsRaw.value.find(x => x.roomId === roomId)
        if (r) r.status = 3
    }

    function onRoomClosed(_, roomId) {
        rooms.value = rooms.value.filter(r => r.roomId !== roomId)
        roomsRaw.value = roomsRaw.value.filter(r => r.roomId !== roomId)
        if (currentRoomId.value === roomId) { currentRoomId.value = null; messages.value = [] }
    }

    function onUserOnline(_, roomId) { loadRoomUsers(roomId) }
    function onUserOffline(_, roomId) { loadRoomUsers(roomId) }

    function onMemberInfoChanged(data) {
        const accountId = data?.accountId != null ? String(data.accountId) : null
        if (!accountId) return currentRoomId.value ? (roomUsersMap[currentRoomId.value] || []) : []

        Object.keys(roomUsersMap).forEach(roomId => {
            const users = roomUsersMap[roomId] || []
            let changed = false

            roomUsersMap[roomId] = users.map(user => {
                if (user._id !== accountId) return user
                changed = true

                const nextUsername = data.nickname ?? user.username
                const rawAvatar = data.avatar ?? user._raw?.avatar ?? null
                const nextAvatar = resolveBackendAvatar(rawAvatar, nextUsername)

                return {
                    ...user,
                    username: nextUsername,
                    avatar: nextAvatar,
                    _raw: {
                        ...(user._raw || {}),
                        nickname: nextUsername,
                        avatar: rawAvatar
                    }
                }
            })

            if (changed) {
                updateRoomInList(roomId, { users: roomUsersMap[roomId] })
            }
        })

        Object.keys(lastRealMsgMap).forEach(roomId => {
            const lastMessage = lastRealMsgMap[roomId]
            if (String(lastMessage?.senderId || '') !== accountId) return
            lastRealMsgMap[roomId] = {
                ...lastMessage,
                username: data.nickname ?? lastMessage.username
            }
        })

        messages.value = messages.value.map(message => {
            if (message.senderId !== accountId) return message
            const nextUsername = data.nickname ?? message.username
            const rawAvatar = data.avatar ?? message._raw?.avatar ?? null
            const nextAvatar = resolveBackendAvatar(rawAvatar, nextUsername)

            return {
                ...message,
                username: nextUsername,
                avatar: nextAvatar,
                _raw: {
                    ...(message._raw || {}),
                    username: nextUsername,
                    avatar: rawAvatar
                }
            }
        })

        rooms.value = roomsRaw.value.map(room => transformRoom(room))
        return currentRoomId.value ? (roomUsersMap[currentRoomId.value] || []) : []
    }

    function onMsgRecalled(data, roomId) {
        if (roomId !== currentRoomId.value) return
        const targetId = data?.indexId
        if (!targetId) return
        messages.value = messages.value.map(m => {
            if (m.indexId === targetId) {
                return { ...m, deleted: true, content: '此消息已撤回', files: null, replyMessage: null }
            }
            return m
        })
    }

    function onMsgDeleted(data, roomId) {
        if (roomId !== currentRoomId.value) return
        const targetId = data?.indexId
        if (!targetId) return
        messages.value = messages.value.filter(m => m.indexId !== targetId)
    }

    function onRoomExtended(data, roomId) {
        const r = roomsRaw.value.find(x => x.roomId === roomId)
        if (r && data) {
            if (data.newExpireTime) r.expireTime = data.newExpireTime
            if (data.status != null) r.status = data.status
        }
        refreshRoomCountdowns()
    }

    function onMsgReactionUpdate(data, roomId) {
        if (roomId !== currentRoomId.value) return
        const targetId = data?.indexId
        if (!targetId) return
        messages.value = messages.value.map(m => {
            if (m.indexId === targetId) {
                return { ...m, reactions: data.reactions || null }
            }
            return m
        })
    }

    async function sendMessage({ content, files, replyMessage }) {
        const roomId = currentRoomId.value
        if (!roomId) return false

        try {
            let uploaded = null
            if (files?.length) {
                uploaded = await Promise.all(files.map(async f => {
                    const dto = await uploadFile(f.blob)
                    if (f.audio) dto.audio = true
                    if (f.duration) dto.duration = f.duration
                    return dto
                }))
            }

            await chatApi.sendMessage({
                roomId,
                content: content || '',
                files: uploaded,
                replyMsgId: replyMessage?.indexId || replyMessage?._raw?.indexId || null
            })
            return true
        } catch (e) {
            console.error('[Chat] send message failed', e)
            return false
        }
    }

    function onChatBroadcast(data, roomId) {
        const msg = transformMessage(data, roomId)
        if (isMessageHiddenForSelf(roomId, msg)) return

        if (!msg.system) {
            rememberLastMessage(roomId, msg)
            setTypingUser(roomId, msg.senderId, false)
        }

        if (roomId === currentRoomId.value) {
            const dup = messages.value.some(m =>
                (m._id && m._id === msg._id) ||
                (m.indexId && msg.indexId && m.indexId === msg.indexId)
            )
            if (!dup) messages.value = [...messages.value, msg]
            doAck(roomId)
        } else {
            unreadMap[roomId] = (unreadMap[roomId] || 0) + 1
        }

        refreshRoomCountdowns()
    }

    function onUserJoin(data, roomId) {
        loadRoomUsers(roomId)
        if (roomId === currentRoomId.value) {
            messages.value = [...messages.value, {
                _id: `sys-join-${Date.now()}`,
                system: true,
                content: `${data?.nickname || '某人'} 加入了房间`,
                date: formatDate(Date.now()),
                timestamp: formatTime(Date.now())
            }]
        }
    }

    function onUserLeave(data, roomId) {
        loadRoomUsers(roomId)
        if (roomId === currentRoomId.value) {
            messages.value = [...messages.value, {
                _id: `sys-leave-${Date.now()}`,
                system: true,
                content: `${data?.nickname || '某人'} 离开了房间`,
                date: formatDate(Date.now()),
                timestamp: formatTime(Date.now())
            }]
        }
    }

    function onYouKicked(_, roomId) {
        removeRoomState(roomId)
        rooms.value = rooms.value.filter(r => r.roomId !== roomId)
        roomsRaw.value = roomsRaw.value.filter(r => r.roomId !== roomId)
        if (currentRoomId.value === roomId) {
            currentRoomId.value = null
            messages.value = []
        }
    }

    function onRoomExpiring(_, roomId) {
        const room = roomsRaw.value.find(x => x.roomId === roomId)
        if (room) room.status = 2
        refreshRoomCountdowns()
    }

    function onRoomGrace(_, roomId) {
        const room = roomsRaw.value.find(x => x.roomId === roomId)
        if (room) room.status = 3
        refreshRoomCountdowns()
    }

    function onRoomClosed(_, roomId) {
        removeRoomState(roomId)
        rooms.value = rooms.value.filter(r => r.roomId !== roomId)
        roomsRaw.value = roomsRaw.value.filter(r => r.roomId !== roomId)
        if (currentRoomId.value === roomId) {
            currentRoomId.value = null
            messages.value = []
        }
    }

    function onUserOnline(_, roomId) {
        loadRoomUsers(roomId)
    }

    function onUserOffline(data, roomId) {
        if (data?.userId != null) {
            setTypingUser(roomId, data.userId, false)
        }
        loadRoomUsers(roomId)
    }

    function onMsgRecalled(data, roomId) {
        const targetId = data?.indexId
        if (!roomId || !targetId) return

        if (roomId === currentRoomId.value) {
            messages.value = messages.value.map(message => {
                if (message.indexId !== targetId) return message
                return {
                    ...message,
                    deleted: true,
                    content: '此消息已撤回',
                    files: null,
                    replyMessage: null,
                    reactions: null,
                    _raw: {
                        ...(message._raw || {}),
                        deleted: true
                    }
                }
            })
        }

        const previewFallback = '此消息已撤回'
        if (lastRealMsgMap[roomId]?.indexId === targetId || roomId === currentRoomId.value) {
            rebuildRoomPreview(roomId, previewFallback)
        }
    }

    function onMsgDeleted(data, roomId) {
        const targetId = data?.indexId
        if (!roomId || !targetId) return

        if (roomId === currentRoomId.value) {
            messages.value = messages.value.filter(message => message.indexId !== targetId)
        }

        if (lastRealMsgMap[roomId]?.indexId === targetId || roomId === currentRoomId.value) {
            rebuildRoomPreview(roomId, '消息已被删除')
        }
    }

    function onRoomExtended(data, roomId) {
        const room = roomsRaw.value.find(x => x.roomId === roomId)
        if (room && data) {
            if (data.newExpireTime) room.expireTime = data.newExpireTime
            if (data.status != null) room.status = data.status
            if (data.statusDesc) room.statusDesc = data.statusDesc
        }

        if (roomId === currentRoomId.value && data?.durationDesc) {
            messages.value = [...messages.value, {
                _id: `sys-extend-${Date.now()}`,
                system: true,
                content: `房间已延期 ${data.durationDesc}`,
                date: formatDate(Date.now()),
                timestamp: formatTime(Date.now())
            }]
        }

        refreshRoomCountdowns()
    }

    function onTypingStatus(data, roomId) {
        const userId = data?.userId != null ? String(data.userId) : ''
        if (!roomId || !userId) return

        const currentUserId = getMemberId()
        if (currentUserId && userId === String(currentUserId)) return

        setTypingUser(roomId, userId, Boolean(data.typing))
    }

    async function recallMessage(message, roomId = currentRoomId.value) {
        const msgId = getMessageIndexId(message)
        if (!roomId || !msgId) throw new Error('无法定位消息')
        if (!canRecallMessage(message)) throw new Error('只能撤回自己的消息')

        await chatApi.recallMsg({ roomId, msgId })
        onMsgRecalled({ indexId: msgId }, roomId)
        return true
    }

    async function deleteMessage(message, roomId = currentRoomId.value) {
        const msgId = getMessageIndexId(message)
        if (!roomId || !msgId) throw new Error('无法定位消息')
        if (!canDeleteMessage(message, roomId)) throw new Error('只有房主可以删除消息')

        await chatApi.deleteMsg({ roomId, msgId })
        onMsgDeleted({ indexId: msgId }, roomId)
        return true
    }

    // ================================================================
    //  房间操作
    // ================================================================

    async function createRoom(data) {
        const room = await roomApi.createRoom(data)
        await loadRooms(room?.roomId || null)
        return room
    }

    async function joinRoom(roomId) {
        const room = await roomApi.joinRoom({ roomId })
        await loadRooms(room?.roomId || roomId)
        return room
    }

    async function leaveRoom(roomId) {
        await roomApi.leaveRoom({ roomId })
        removeRoomState(roomId)
        rooms.value = rooms.value.filter(r => r.roomId !== roomId)
        roomsRaw.value = roomsRaw.value.filter(r => r.roomId !== roomId)
        if (currentRoomId.value === roomId) { currentRoomId.value = null; messages.value = [] }
    }

    async function closeRoom(roomId) {
        await roomApi.closeRoom({ roomId })
    }

    function syncCurrentUserIdentity() {
        const memberId = getMemberId()
        if (!memberId) return []

        const myId = String(memberId)
        const myName = getCurrentUserName()
        const myAvatar = getCurrentUserAvatar()

        Object.keys(roomUsersMap).forEach(roomId => {
            roomUsersMap[roomId] = mergeCurrentUserIntoUsers(roomUsersMap[roomId] || []).map(user => {
                if (user._id !== myId) return user
                return {
                    ...user,
                    username: myName,
                    avatar: myAvatar
                }
            })
        })

        Object.keys(lastRealMsgMap).forEach(roomId => {
            const lastMessage = lastRealMsgMap[roomId]
            if (String(lastMessage?.senderId || '') !== myId) return
            lastRealMsgMap[roomId] = {
                ...lastMessage,
                username: myName
            }
        })

        messages.value = messages.value.map(message => {
            if (message.senderId !== myId) return message
            return {
                ...message,
                username: myName,
                avatar: myAvatar
            }
        })

        rooms.value = roomsRaw.value.map(room => transformRoom(room))
        return currentRoomId.value ? (roomUsersMap[currentRoomId.value] || []) : []
    }

    async function refreshAllRoomUsers() {
        await Promise.all(
            roomsRaw.value.map(room => loadRoomUsers(room.roomId))
        )
        rooms.value = roomsRaw.value.map(r => transformRoom(r))

        // 修复消息列表中当前用户的头像和昵称
        const memberId = getMemberId()
        if (!memberId) return
        const myIdStr = String(memberId)
        const myAvatar = getCurrentUserAvatar()
        const myName = getCurrentUserName()

        const hasChange = messages.value.some(m =>
            m.senderId === myIdStr && (m.avatar !== myAvatar || m.username !== myName)
        )

        if (hasChange) {
            messages.value = messages.value.map(m => {
                if (m.senderId === myIdStr) {
                    return { ...m, avatar: myAvatar, username: myName }
                }
                return m
            })
        }

        return currentRoomId.value ? (roomUsersMap[currentRoomId.value] || []) : []
    }

    function isRecallPlaceholder(content) {
        return String(content || '').trim() === '此消息已撤回'
    }

    function getMessagePreviewContent(message) {
        if (!message) return ''
        if (message.recalled) return message.content || '此消息已撤回'
        if (message.deleted) return message.content || '消息已被删除'
        return message.content || '[文件]'
    }

    function transformMessage(msg, roomId = currentRoomId.value) {
        const ts = msg.timestamp || Date.now()
        const senderId = msg.senderId != null ? String(msg.senderId) : ''
        const currentMemberId = getMemberId()
        const isSelf = currentMemberId && senderId === String(currentMemberId)
        const roomUser = isSelf ? null : getRoomUserDisplay(roomId, senderId)
        const content = msg.content || ''
        const recalled = Boolean(msg.deleted) && isRecallPlaceholder(content)
        const deleted = Boolean(msg.deleted) && !recalled
        const usernameDisplay = isSelf
            ? getCurrentUserName()
            : (roomUser?.username || msg.username || '匿名')
        const avatarDisplay = isSelf
            ? getCurrentUserAvatar()
            : (roomUser?.avatar || resolveBackendAvatar(msg.avatar, usernameDisplay))

        return {
            _id: msg._id || `msg-${msg.indexId || Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
            indexId: msg.indexId,
            content: recalled ? '此消息已撤回' : content,
            senderId,
            username: usernameDisplay,
            avatar: avatarDisplay,
            date: formatDate(ts),
            timestamp: formatTime(ts),
            system: msg.system || false,
            deleted,
            recalled,
            files: recalled || deleted ? null : (msg.files || null),
            replyMessage: recalled || deleted ? null : (msg.replyMessage || null),
            reactions: recalled || deleted ? null : (msg.reactions || null),
            _raw: {
                ...msg,
                username: usernameDisplay,
                avatar: roomUser?._raw?.avatar ?? msg.avatar ?? avatarDisplay,
                recalled
            }
        }
    }

    function onMsgRecalled(data, roomId) {
        const targetId = data?.indexId
        if (!roomId || !targetId) return

        if (roomId === currentRoomId.value) {
            messages.value = messages.value.map(message => {
                if (message.indexId !== targetId) return message
                return {
                    ...message,
                    deleted: false,
                    recalled: true,
                    content: '此消息已撤回',
                    files: null,
                    replyMessage: null,
                    reactions: null,
                    _raw: {
                        ...(message._raw || {}),
                        deleted: true,
                        recalled: true
                    }
                }
            })
        }

        const previewFallback = '此消息已撤回'
        if (lastRealMsgMap[roomId]?.indexId === targetId || roomId === currentRoomId.value) {
            rebuildRoomPreview(roomId, previewFallback)
        }
    }

    function getMessagePreviewContent(message) {
        if (!message) return ''
        if (message.deleted) return '消息已删除'
        return message.content || '[文件]'
    }

    function transformMessage(msg, roomId = currentRoomId.value) {
        const ts = msg.timestamp || Date.now()
        const senderId = msg.senderId != null ? String(msg.senderId) : ''
        const currentMemberId = getMemberId()
        const isSelf = currentMemberId && senderId === String(currentMemberId)
        const roomUser = isSelf ? null : getRoomUserDisplay(roomId, senderId)
        const content = msg.content || ''
        const deleted = Boolean(msg.deleted)
        const usernameDisplay = isSelf
            ? getCurrentUserName()
            : (roomUser?.username || msg.username || '匿名')
        const avatarDisplay = isSelf
            ? getCurrentUserAvatar()
            : (roomUser?.avatar || resolveBackendAvatar(msg.avatar, usernameDisplay))

        return {
            _id: msg._id || `msg-${msg.indexId || Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
            indexId: msg.indexId,
            content: deleted ? '' : content,
            senderId,
            username: usernameDisplay,
            avatar: avatarDisplay,
            date: formatDate(ts),
            timestamp: formatTime(ts),
            system: msg.system || false,
            deleted,
            files: deleted ? null : (msg.files || null),
            replyMessage: deleted ? null : (msg.replyMessage || null),
            reactions: deleted ? null : (msg.reactions || null),
            _raw: {
                ...msg,
                username: usernameDisplay,
                avatar: roomUser?._raw?.avatar ?? msg.avatar ?? avatarDisplay
            }
        }
    }

    function onMsgRecalled(data, roomId) {
        const targetId = data?.indexId
        if (!roomId || !targetId) return

        if (roomId === currentRoomId.value) {
            messages.value = messages.value.map(message => {
                if (message.indexId !== targetId) return message
                return {
                    ...message,
                    deleted: true,
                    content: '',
                    files: null,
                    replyMessage: null,
                    reactions: null,
                    _raw: {
                        ...(message._raw || {}),
                        deleted: true
                    }
                }
            })
        }

        const previewFallback = '消息已删除'
        if (lastRealMsgMap[roomId]?.indexId === targetId || roomId === currentRoomId.value) {
            rebuildRoomPreview(roomId, previewFallback)
        }
    }

    async function deleteMessageForAll(message, roomId = currentRoomId.value) {
        const msgId = getMessageIndexId(message)
        if (!roomId || !msgId) throw new Error('无法定位消息')
        if (!canDeleteMessage(message, roomId)) throw new Error('只有房主可以为所有人删除消息')

        await chatApi.deleteMsg({ roomId, msgId })
        onMsgDeleted({ indexId: msgId }, roomId)
        return true
    }

    async function deleteMessage(message, roomId = currentRoomId.value) {
        return deleteMessageForAll(message, roomId)
    }

    return {
        rooms, roomsRaw, messages, currentRoomId,
        loadingRooms, roomsLoaded, messagesLoaded, unreadMap,
        loadRooms, loadMessages, loadRoomUsers, sendMessage,
        createRoom, joinRoom, leaveRoom, closeRoom,
        doAck, refreshRoomCountdowns, refreshAllRoomUsers,
        isCurrentUserHost, canRecallMessage, canDeleteMessage,
        hideMessageForSelf, recallMessage, deleteMessage, deleteMessageForAll, onTypingStatus,
        onChatBroadcast, onUserJoin, onUserLeave,
        onYouMuted, onYouUnmuted, onYouKicked,
        onRoomExpiring, onRoomGrace, onRoomClosed,
        onUserOnline, onUserOffline, onMemberInfoChanged,
        onMsgRecalled, onMsgDeleted, onRoomExtended, onMsgReactionUpdate
    }
}
