import { ref, readonly } from 'vue'

const WS_BASE = import.meta.env.VITE_WS_URL || `ws://${window.location.hostname}:8090`

export const WS_TYPE = {
  LOGIN_SUCCESS: 0, CHAT_BROADCAST: 1, USER_JOIN: 2, USER_LEAVE: 3,
  YOU_MUTED: 4, YOU_UNMUTED: 5, YOU_KICKED: 6, ROOM_EXPIRING: 7,
  ROOM_GRACE: 8, ROOM_CLOSED: 9, SYSTEM_MSG: 10, MSG_REJECTED: 11,
  MSG_RECALLED: 12, USER_ONLINE: 13, USER_OFFLINE: 14
}

const isConnected = ref(false)
let ws = null, hbTimer = null, rcTimer = null, rcAttempts = 0
const handlers = new Map()

export function useWebSocket() {
  function on(type, fn) {
    if (!handlers.has(type)) handlers.set(type, [])
    handlers.get(type).push(fn)
  }

  function off(type, fn) {
    const arr = handlers.get(type)
    if (arr) { const i = arr.indexOf(fn); if (i > -1) arr.splice(i, 1) }
  }

  function connect(accountId) {
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return
    const url = `${WS_BASE}/?accountId=${encodeURIComponent(accountId)}`
    console.log('[WS] 正在连接...', url)
    ws = new WebSocket(url)

    ws.onopen = () => {
      console.log('[WS] 连接成功')
      isConnected.value = true; rcAttempts = 0; startHB()
    }
    ws.onmessage = (e) => {
      if (e.data === 'pong') return
      try { dispatch(JSON.parse(e.data)) } catch {}
    }
    ws.onclose = () => {
      isConnected.value = false; stopHB(); scheduleRC(accountId)
    }
    ws.onerror = () => {}
  }

  function dispatch(msg) {
    const list = handlers.get(msg.type)
    if (list) list.forEach(fn => { try { fn(msg.data, msg.roomId) } catch (e) { console.error(e) } })
  }

  function startHB() { stopHB(); hbTimer = setInterval(() => { if (ws?.readyState === WebSocket.OPEN) ws.send('ping') }, 25000) }
  function stopHB() { clearInterval(hbTimer) }

  function scheduleRC(accountId) {
    if (rcAttempts >= 10) return
    const delay = 2000 * Math.pow(1.5, rcAttempts++)
    rcTimer = setTimeout(() => connect(accountId), delay)
  }

  function disconnect() {
    rcAttempts = 999; clearTimeout(rcTimer); stopHB()
    if (ws) { ws.close(); ws = null }
    isConnected.value = false
  }

  return { isConnected: readonly(isConnected), connect, disconnect, on, off }
}
