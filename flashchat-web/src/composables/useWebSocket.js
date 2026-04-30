/**
 * WebSocket composable
 *
 * 连接方式：ws://host:8090/?token=xxx
 * 后端 HttpHeadersHandler 读取 token 参数
 * 后端 NettyWebSocketServerHandler 用 StpUtil.getLoginIdByToken(token) 验证
 *
 * 重连策略：
 *   指数退避，最多重试 10 次
 *   每次重连从 storage 读最新 token（防止 token 刷新后用旧值）
 *   无 token 时停止重连（用户已登出）
 */
import { ref, readonly } from 'vue'
import { loadToken } from '@/utils/storage'

const WS_BASE = import.meta.env.VITE_WS_URL
    || (typeof window !== 'undefined' && window.location.protocol === 'https:'
        ? `wss://${window.location.host}/ws`
        : `ws://${window.location.hostname}:8090`)

/**
 * 后端 WsRespDTOTypeEnum 对齐
 * 值必须和后端枚举的 type 字段完全一致
 */
export const WS_TYPE = {
    LOGIN_SUCCESS:      0,
    CHAT_BROADCAST:     1,
    USER_JOIN:          2,
    USER_LEAVE:         3,
    YOU_MUTED:          4,
    YOU_UNMUTED:        5,
    YOU_KICKED:         6,
    ROOM_EXPIRING:      7,
    ROOM_GRACE:         8,
    ROOM_CLOSED:        9,
    SYSTEM_MSG:         10,
    MSG_REJECTED:       11,
    MSG_RECALLED:       12,
    USER_ONLINE:        13,
    USER_OFFLINE:       14,
    MSG_DELETED:        15,
    ROOM_EXTENDED:      16,
    MSG_REACTION_UPDATE:17,
    TYPING_STATUS:      18,
    MEMBER_INFO_CHANGED:19
}

export const WS_REQ_TYPE = {
    HEARTBEAT: 1,
    SEND_MSG: 2,
    TYPING: 3
}

const isConnected = ref(false)
let ws = null
let hbTimer = null
let rcTimer = null
let rcAttempts = 0
const handlers = new Map()

export function useWebSocket() {

    /**
     * 注册事件处理器
     * @param {number} type - WS_TYPE 枚举值
     * @param {Function} fn - (data, roomId) => void
     */
    function on(type, fn) {
        if (!handlers.has(type)) handlers.set(type, [])
        handlers.get(type).push(fn)
    }

    function off(type, fn) {
        const arr = handlers.get(type)
        if (arr) {
            const i = arr.indexOf(fn)
            if (i > -1) arr.splice(i, 1)
        }
    }

    /**
     * 建立 WebSocket 连接
     * @param {string} token - SaToken 令牌
     */
    function connect(token) {
        if (!token) {
            console.warn('[WS] 无 token，放弃连接')
            return
        }
        if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
            return
        }

        const url = `${WS_BASE}/?token=${encodeURIComponent(token)}`
        console.log('[WS] 正在连接...')
        ws = new WebSocket(url)

        ws.onopen = () => {
            console.log('[WS] 连接成功')
            isConnected.value = true
            rcAttempts = 0
            startHeartbeat()
        }

        ws.onmessage = (e) => {
            if (e.data === 'pong') return
            try {
                dispatch(JSON.parse(e.data))
            } catch (err) {
                console.warn('[WS] 消息解析失败', err)
            }
        }

        ws.onclose = () => {
            console.log('[WS] 连接关闭')
            isConnected.value = false
            stopHeartbeat()
            scheduleReconnect()
        }

        ws.onerror = (err) => {
            console.error('[WS] 连接错误', err)
        }
    }

    /**
     * 分发消息到注册的 handlers
     */
    function dispatch(msg) {
        const list = handlers.get(msg.type)
        if (list) {
            list.forEach(fn => {
                try {
                    fn(msg.data, msg.roomId)
                } catch (e) {
                    console.error('[WS handler error]', e)
                }
            })
        }
    }

    // ==================== 心跳 ====================

    function startHeartbeat() {
        stopHeartbeat()
        hbTimer = setInterval(() => {
            if (ws?.readyState === WebSocket.OPEN) {
                ws.send('ping')
            }
        }, 25000)
    }

    function stopHeartbeat() {
        if (hbTimer) {
            clearInterval(hbTimer)
            hbTimer = null
        }
    }

    // ==================== 重连 ====================

    /**
     * 调度重连
     * 每次重连从 storage 动态读 token，解决：
     *   1. token 在其他标签页刷新后，重连用旧 token 的问题
     *   2. 用户登出后，WS 仍尝试重连的问题
     */
    function scheduleReconnect() {
        if (rcAttempts >= 10) {
            console.warn('[WS] 达到最大重连次数，停止重连')
            return
        }
        const delay = 2000 * Math.pow(1.5, rcAttempts++)
        console.log(`[WS] ${Math.round(delay / 1000)}s 后第 ${rcAttempts} 次重连`)

        rcTimer = setTimeout(() => {
            const token = loadToken()
            if (token) {
                connect(token)
            } else {
                console.warn('[WS] 无 token，停止重连')
                rcAttempts = 999
            }
        }, delay)
    }

    // ==================== 断开 ====================

    function disconnect() {
        rcAttempts = 999 // 阻止重连
        clearTimeout(rcTimer)
        stopHeartbeat()
        if (ws) {
            ws.close()
            ws = null
        }
        isConnected.value = false
    }

    // ==================== 发送 ====================

    /**
     * 发送 WS 消息（目前仅用于打字状态）
     * @param {Object} msg - { type, data }
     */
    function send(msg) {
        if (ws?.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify(msg))
            return true
        }
        return false
    }

    function sendTyping(roomId, typing) {
        if (!roomId) return false
        return send({
            type: WS_REQ_TYPE.TYPING,
            data: {
                roomId,
                typing: !!typing
            }
        })
    }

    return {
        isConnected: readonly(isConnected),
        connect,
        disconnect,
        on,
        off,
        send,
        sendTyping
    }
}
