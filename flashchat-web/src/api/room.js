import request from './request'

export function createRoom(data) {
    return request.post('/room/create', data)
}

export function joinRoom(data) {
    return request.post('/room/join', data)
}

export function leaveRoom(data) {
    return request.post('/room/leave', data)
}

export function closeRoom(data) {
    return request.post('/room/close', data)
}

export function getMyRooms() {
    return request.get('/room/my-rooms')
}

export function getRoomMembers(roomId) {
    return request.get(`/room/members/${roomId}`)
}

export function kickMember(data) {
    return request.post('/room/kick', data)
}

export function muteMember(data) {
    return request.post('/room/mute', data)
}

export function unmuteMember(data) {
    return request.post('/room/unmute', data)
}

export function listPublicRooms(params = {}) {
    return request.get('/room/public', { params })
}

export function getRoomPricing() {
    return request.get('/room/pricing')
}

export function extendRoom(data) {
    return request.post('/room/extend', data)
}

export function resizeRoom(data) {
    return request.post('/room/resize', data)
}

export function updateRoomAvatar(data) {
    return request.post('/room/avatar', data)
}

export function getShareUrl(roomId) {
    return request.get(`/room/${roomId}/share`)
}

export function previewRoom(roomId) {
    return request.get(`/room/preview/${roomId}`)
}
