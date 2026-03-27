import request from './request'

export function sendMessage(data) {
  return request.post('/chat/msg', data)
}

export function getHistoryMessages(roomId, cursor = null, pageSize = 20) {
  const params = { roomId, pageSize }
  if (cursor) params.cursor = cursor
  return request.get('/chat/history', { params })
}

export function ackMessages(data) {
  return request.post('/chat/ack', data)
}

export function getNewMessages(roomId, accountId) {
  return request.get('/chat/new', { params: { roomId, accountId } })
}

export function getUnreadCounts(accountId) {
  return request.get('/chat/unread', { params: { accountId } })
}
