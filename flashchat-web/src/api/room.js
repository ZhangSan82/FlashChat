/**
 * 房间接口
 * 对齐后端 RoomController（/api/FlashChat/v1/room）
 *
 * 核心改动：所有方法移除 accountId 参数
 * 后端通过 UserContext.getRequiredLoginId() 从 SaToken Session 获取当前用户
 */
import request from './request'

/**
 * 创建房间
 * @param {{ title: string, duration?: string, maxMembers?: number, isPublic?: number }} data
 */
export function createRoom(data) {
    return request.post('/room/create', data)
}

/**
 * 加入房间
 * @param {{ roomId: string }} data
 */
export function joinRoom(data) {
    return request.post('/room/join', data)
}

/**
 * 离开房间
 * @param {{ roomId: string }} data
 */
export function leaveRoom(data) {
    return request.post('/room/leave', data)
}

/**
 * 关闭房间（仅房主）
 * @param {{ roomId: string }} data
 */
export function closeRoom(data) {
    return request.post('/room/close', data)
}

/**
 * 获取我加入的所有房间
 * 后端从 token 识别用户，无需传参
 */
export function getMyRooms() {
    return request.get('/room/my-rooms')
}

/**
 * 获取房间成员列表
 * @param {string} roomId
 */
export function getRoomMembers(roomId) {
    return request.get(`/room/members/${roomId}`)
}

/**
 * 踢人（仅房主）
 * @param {{ roomId: string, targetAccountId: number }} data
 */
export function kickMember(data) {
    return request.post('/room/kick', data)
}

/**
 * 禁言（仅房主）
 * @param {{ roomId: string, targetAccountId: number }} data
 */
export function muteMember(data) {
    return request.post('/room/mute', data)
}

/**
 * 解除禁言（仅房主）
 * @param {{ roomId: string, targetAccountId: number }} data
 */
export function unmuteMember(data) {
    return request.post('/room/unmute', data)
}

/**
 * 公开房间列表（无需 token，在放行列表中）
 * @param {{ page?: number, size?: number, sort?: string }} params
 */
export function listPublicRooms(params = {}) {
    return request.get('/room/public', { params })
}

/**
 * 房间定价列表（无需 token，在放行列表中）
 */
export function getRoomPricing() {
    return request.get('/room/pricing')
}

/**
 * 房间延期（仅房主）
 * @param {{ roomId: string, duration?: string }} data
 */
export function extendRoom(data) {
    return request.post('/room/extend', data)
}

/**
 * 房间扩容（仅房主）
 * @param {{ roomId: string, newMaxMembers: number }} data
 */
export function resizeRoom(data) {
    return request.post('/room/resize', data)
}

/**
 * 获取分享链接
 * @param {string} roomId
 */
export function getShareUrl(roomId) {
    return request.get(`/room/${roomId}/share`)
}

/**
 * 房间预览（无需加入即可查看基本信息）
 * @param {string} roomId
 * @returns {Promise<RoomInfoRespDTO>}
 */
export function previewRoom(roomId) {
    return request.get(`/room/preview/${roomId}`)
}