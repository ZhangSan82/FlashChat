const ROOM_PREVIEW_CACHE_KEY = 'flashchat_room_preview_messages_v1'
const DEFAULT_LIMIT = 5
const CONTENT_LIMIT = 88

function isBrowser() {
  return typeof window !== 'undefined' && typeof window.localStorage !== 'undefined'
}

function normalizeRoomId(roomId) {
  return String(roomId || '').trim()
}

function normalizeContent(content) {
  const text = String(content || '').replace(/\s+/g, ' ').trim()
  if (!text) return '[文件]'
  if (text.length <= CONTENT_LIMIT) return text
  return `${text.slice(0, CONTENT_LIMIT)}...`
}

function normalizeTimestamp(value) {
  if (value == null) return Date.now()
  const numeric = Number(value)
  if (Number.isFinite(numeric)) return numeric
  const parsed = new Date(value).getTime()
  return Number.isFinite(parsed) ? parsed : Date.now()
}

function normalizeMessage(input = {}) {
  return {
    indexId: input.indexId ?? null,
    senderId: input.senderId ?? '',
    username: String(input.username || '').trim(),
    content: normalizeContent(input.content),
    timestamp: normalizeTimestamp(input.timestamp)
  }
}

function readCacheMap() {
  if (!isBrowser()) return {}
  try {
    const raw = window.localStorage.getItem(ROOM_PREVIEW_CACHE_KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    return {}
  }
}

function writeCacheMap(map) {
  if (!isBrowser()) return
  try {
    window.localStorage.setItem(ROOM_PREVIEW_CACHE_KEY, JSON.stringify(map || {}))
  } catch {
    // ignore
  }
}

function clampLimit(limit) {
  const max = Number(limit)
  if (!Number.isFinite(max) || max <= 0) return DEFAULT_LIMIT
  return Math.min(20, Math.floor(max))
}

export function getRoomPreviewMessages(roomId, limit = DEFAULT_LIMIT) {
  const id = normalizeRoomId(roomId)
  if (!id) return []
  const map = readCacheMap()
  const list = Array.isArray(map[id]) ? map[id] : []
  return list.slice(0, clampLimit(limit))
}

export function appendRoomPreviewMessage(roomId, message, limit = DEFAULT_LIMIT) {
  const id = normalizeRoomId(roomId)
  if (!id || !message) return
  const map = readCacheMap()
  const list = Array.isArray(map[id]) ? map[id] : []
  const normalized = normalizeMessage(message)

  const next = [normalized, ...list.filter(item => {
    if (!item) return false
    if (normalized.indexId == null || item.indexId == null) return true
    return String(item.indexId) !== String(normalized.indexId)
  })]

  map[id] = next.slice(0, clampLimit(limit))
  writeCacheMap(map)
}

export function setRoomPreviewMessages(roomId, messages = [], limit = DEFAULT_LIMIT) {
  const id = normalizeRoomId(roomId)
  if (!id) return
  const map = readCacheMap()
  const max = clampLimit(limit)
  const next = Array.isArray(messages)
    ? messages
      .filter(Boolean)
      .slice(0, max)
      .map(item => normalizeMessage(item))
    : []
  map[id] = next
  writeCacheMap(map)
}

export function updateRoomPreviewTopContent(roomId, content) {
  const id = normalizeRoomId(roomId)
  if (!id) return
  const map = readCacheMap()
  const list = Array.isArray(map[id]) ? map[id] : []
  if (!list.length) return
  list[0] = {
    ...list[0],
    content: normalizeContent(content),
    timestamp: Date.now()
  }
  map[id] = list
  writeCacheMap(map)
}
