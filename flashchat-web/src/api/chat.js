/**
 * 聊天接口
 * 对齐后端 ChatController（/api/FlashChat/v1/chat）
 *
 * 核心改动：所有方法移除 accountId 参数
 * 后端通过 UserContext.getRequiredLoginId() 获取当前用户
 */
import request from './request'

/**
 * 发送消息
 * @param {{ roomId: string, content?: string, files?: Array, replyMsgId?: number }} data
 */
export function sendMessage(data) {
    return request.post('/chat/msg', data)
}

/**
 * 查询历史消息（游标分页，倒序翻页）
 * @param {string} roomId
 * @param {string|null} cursor - 上一页返回的 cursor，首次不传
 * @param {number} pageSize
 */
export function getHistoryMessages(roomId, cursor = null, pageSize = 20) {
    const params = { roomId, pageSize }
    if (cursor) params.cursor = cursor
    return request.get('/chat/history', { params })
}

/**
 * 消息确认 ACK
 * 语义："该房间中 lastMsgId 及之前的消息我都看到了"
 * @param {{ roomId: string, lastMsgId: number }} data
 */
export function ackMessages(data) {
    return request.post('/chat/ack', data)
}

/**
 * 拉取新消息（断线重连场景）
 * 后端自动从 t_room_member.last_ack_msg_id 读取已读位置
 * @param {string} roomId
 */
export function getNewMessages(roomId) {
    return request.get('/chat/new', { params: { roomId } })
}

/**
 * 查询所有房间的未读消息数
 * @returns {Promise<Object>} { roomId: count, ... }
 */
export function getUnreadCounts() {
    return request.get('/chat/unread')
}

/**
 * 撤回消息（仅发送者，2分钟内）
 * @param {{ roomId: string, msgId: number }} data
 */
export function recallMsg(data) {
    return request.post('/chat/recall', data)
}

/**
 * 删除消息（仅房主）
 * @param {{ roomId: string, msgId: number }} data
 */
export function deleteMsg(data) {
    return request.post('/chat/delete', data)
}

/**
 * 消息表情回应（toggle：点过取消，没点添加）
 * @param {{ roomId: string, msgId: number, emoji: string }} data
 */
export function toggleReaction(data) {
    return request.post('/chat/reaction', data)
}