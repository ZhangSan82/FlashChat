import { ref, reactive } from 'vue'
import * as chatApi from '@/api/chat'
import * as roomApi from '@/api/room'
import { uploadFile } from '@/api/file'
import { formatTime, formatDate, formatCountdownShort, generateAvatarUrl } from '@/utils/formatter'

export function useChat(getAccountId, getMemberId) {

    const rooms = ref([])
    const roomsRaw = ref([])
    const messages = ref([])
    const currentRoomId = ref(null)
    const loadingRooms = ref(false)
    const roomsLoaded = ref(false)
    const messagesLoaded = ref(false)
    const unreadMap = reactive({})
    const roomUsersMap = reactive({})
    const msgCursorMap = reactive({})
    const lastRealMsgMap = reactive({})

    // ================================================================
    //  房间
    // ================================================================
    async function loadRooms() {
        const accountId = getAccountId()
        if (!accountId) return
        loadingRooms.value = true
        try {
            const [list, unreads] = await Promise.all([
                roomApi.getMyRooms(accountId),
                chatApi.getUnreadCounts(accountId)
            ])
            roomsRaw.value = list || []
            Object.assign(unreadMap, unreads || {})
            rooms.value = roomsRaw.value.map(r => transformRoom(r))
            roomsLoaded.value = true
            // ★ 刷新后恢复上次的房间
            const savedRoom = sessionStorage.getItem('fc_last_room')
            if (savedRoom && roomsRaw.value.find(r => r.roomId === savedRoom)) {
                currentRoomId.value = savedRoom
            }
        } catch (e) {
            console.error('[Chat] 加载房间失败', e)
        } finally {
            loadingRooms.value = false
        }
    }

    /**
     * ★ transformRoom — users 必须包含 current-user-id
     */
    function transformRoom(room) {
        const memberId = getMemberId()

        // 确保 users 包含当前用户
        let users = roomUsersMap[room.roomId] || []
        if (memberId && !users.find(u => u._id === String(memberId))) {
            users = [
                ...users,
                {
                    _id: String(memberId),
                    username: '我',
                    avatar: generateAvatarUrl('我', '#C8956C')
                }
            ]
        }
        // ★ 确保 users >= 3 个，否则组件认为是私聊不显示用户名
        // 加入两个隐藏的占位用户
        while (users.length < 3) {
            users = [...users, {
                _id: `_placeholder_${users.length}`,
                username: '',
                avatar: generateAvatarUrl('?', '#B5B0A8')
            }]
        }

        // 倒计时
        let countdown = ''
        if (room.expireTime) {
            const remain = new Date(room.expireTime).getTime() - Date.now()
            countdown = remain > 0 ? '⏳ ' + formatCountdownShort(remain) : '已过期'
        } else {
            countdown = '∞'
        }

        // 消息预览
        const lastReal = lastRealMsgMap[room.roomId]
        const preview = lastReal
            ? (lastReal.username ? `${lastReal.username}: ${lastReal.content}` : lastReal.content)
            : (countdown !== '∞' ? countdown : '')

        return {
            roomId: room.roomId,
            roomName: room.title || room.roomId,
            avatar: generateAvatarUrl(room.title || '?', '#C8956C'),
            unreadCount: unreadMap[room.roomId] || 0,
            index: room.createTime ? new Date(room.createTime).getTime() : 0,
            users,
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
            rooms.value = [...rooms.value.slice(0, idx), { ...rooms.value[idx], ...updater }, ...rooms.value.slice(idx + 1)]
        }
    }

    function refreshRoomCountdowns() {
        rooms.value = roomsRaw.value.map(r => transformRoom(r))
    }

    // ================================================================
    //  消息
    // ================================================================
    async function loadMessages(roomId) {
        if (!roomId) return
        const isNew = currentRoomId.value !== roomId
        currentRoomId.value = roomId
        sessionStorage.setItem('fc_last_room', roomId)

        if (isNew) {
            messages.value = []
            messagesLoaded.value = false
            delete msgCursorMap[roomId]
        }

        try {
            const cursor = msgCursorMap[roomId] || null
            const resp = await chatApi.getHistoryMessages(roomId, cursor, 20)
            if (!resp) { messagesLoaded.value = true; return }

            const newMsgs = (resp.list || []).map(transformMessage)

            // ★ 后端返回的是从新到旧，需要反转成从旧到新
            // 但如果后端已经是从旧到新，就不需要反转
            // 通过检查第一条和最后一条的 indexId 判断顺序
            let ordered = newMsgs
            if (newMsgs.length >= 2) {
                const firstIdx = newMsgs[0].indexId || 0
                const lastIdx = newMsgs[newMsgs.length - 1].indexId || 0
                if (firstIdx > lastIdx) {
                    // 从新到旧 → 反转
                    ordered = [...newMsgs].reverse()
                }
                // 否则已经是从旧到新，不反转
            }

            if (isNew) {
                messages.value = ordered
            } else {
                // 加载更多（拼到前面）
                const ids = new Set(messages.value.map(m => m._id))
                const unique = ordered.filter(m => !ids.has(m._id))
                messages.value = [...unique, ...messages.value]
            }

            if (resp.cursor) msgCursorMap[roomId] = resp.cursor
            messagesLoaded.value = resp.isLast === true
            await doAck(roomId)
        } catch (e) {
            console.error('[Chat] 加载消息失败', e)
            messagesLoaded.value = true
        }
    }

    async function loadRoomUsers(roomId) {
        try {
            const members = await roomApi.getRoomMembers(roomId)
            const users = (members || []).map(m => ({
                // ★ _id 必须是 String，和 senderId 匹配
                _id: String(m.memberId),
                username: m.nickname || '匿名',
                avatar: m.avatar?.startsWith('#')
                    ? generateAvatarUrl(m.nickname, m.avatar)
                    : (m.avatar || generateAvatarUrl(m.nickname, '#C8956C')),
                status: { state: m.isOnline ? 'online' : 'offline' },
                _raw: m
            }))
            roomUsersMap[roomId] = users
            // ★ 确保当前用户也在列表中
            const memberId = getMemberId()
            if (memberId && !users.find(u => u._id === String(memberId))) {
                users.push({
                    _id: String(memberId),
                    username: '我',
                    avatar: generateAvatarUrl('我', '#C8956C')
                })
            }
            updateRoomInList(roomId, { users })
            return users
        } catch (e) {
            console.error('[Chat] 加载成员失败', e)
            return []
        }
    }

    function transformMessage(msg) {
        const ts = msg.timestamp || Date.now()
        // ★ senderId 统一为 String，确保和 users._id 匹配，组件才能显示用户名
        const senderId = msg.senderId != null ? String(msg.senderId) : ''
        return {
            _id: msg._id || `msg-${msg.indexId || Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
            indexId: msg.indexId,
            content: msg.content || '',
            senderId: senderId,
            username: msg.username || '匿名',
            avatar: msg.avatar?.startsWith('#')
                ? generateAvatarUrl(msg.username, msg.avatar)
                : (msg.avatar || generateAvatarUrl(msg.username, '#C8956C')),
            date: formatDate(ts),
            timestamp: formatTime(ts),
            system: msg.system || false,
            deleted: msg.deleted || false,
            files: msg.files || null,
            replyMessage: msg.replyMessage || null,
            _raw: msg
        }
    }

    // ================================================================
    //  发送消息
    // ================================================================
    async function sendMessage({ content, files, replyMessage }) {
        const accountId = getAccountId(), roomId = currentRoomId.value
        if (!accountId || !roomId) return
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
                roomId, accountId,
                content: content || '',
                files: uploaded,
                replyMsgId: replyMessage?.indexId || replyMessage?._raw?.indexId || null
            })
        } catch (e) { console.error('[Chat] 发送失败', e) }
    }

    // ================================================================
    //  ACK
    // ================================================================
    let ackTimer = null
    async function doAck(roomId) {
        const accountId = getAccountId()
        if (!accountId || !roomId) return
        const last = messages.value[messages.value.length - 1]
        const lastId = last?.indexId || last?._raw?.indexId
        if (!lastId) return
        clearTimeout(ackTimer)
        ackTimer = setTimeout(async () => {
            try {
                await chatApi.ackMessages({ roomId, accountId, lastMsgId: lastId })
                if (unreadMap[roomId]) { unreadMap[roomId] = 0; updateRoomInList(roomId, { unreadCount: 0 }) }
            } catch {}
        }, 1000)
    }

    // ================================================================
    //  WS 事件
    // ================================================================
    function onChatBroadcast(data, roomId) {
        const msg = transformMessage(data)

        if (!msg.system) {
            lastRealMsgMap[roomId] = { content: msg.content || '[文件]', username: msg.username, senderId: msg.senderId }
        }

        if (roomId === currentRoomId.value) {
            const dup = messages.value.some(m =>
                (m._id && m._id === msg._id) || (m.indexId && msg.indexId && m.indexId === msg.indexId)
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
                senderId: msg.senderId, username: msg.username, timestamp: msg.timestamp,
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
        if (currentRoomId.value === roomId) { currentRoomId.value = null; messages.value = [] }
    }
    function onUserOnline(_, r) { loadRoomUsers(r) }
    function onUserOffline(_, r) { loadRoomUsers(r) }

    // ================================================================
    //  房间操作
    // ================================================================
    async function createRoom(data) { await roomApi.createRoom(data); await loadRooms() }
    async function joinRoom(roomId) {
        await roomApi.joinRoom({ roomId, accountId: getAccountId() }); await loadRooms()
    }
    async function leaveRoom(roomId) {
        await roomApi.leaveRoom({ roomId, accountId: getAccountId() })
        rooms.value = rooms.value.filter(r => r.roomId !== roomId)
        if (currentRoomId.value === roomId) { currentRoomId.value = null; messages.value = [] }
    }
    async function closeRoom(roomId) {
        await roomApi.closeRoom({ roomId, accountId: getAccountId() })
    }

    return {
        rooms, roomsRaw, messages, currentRoomId,
        loadingRooms, roomsLoaded, messagesLoaded, unreadMap,
        loadRooms, loadMessages, loadRoomUsers, sendMessage,
        createRoom, joinRoom, leaveRoom, closeRoom,
        doAck, refreshRoomCountdowns,
        onChatBroadcast, onUserJoin, onUserLeave,
        onYouMuted, onYouUnmuted, onYouKicked,
        onRoomExpiring, onRoomGrace, onRoomClosed,
        onUserOnline, onUserOffline
    }
}