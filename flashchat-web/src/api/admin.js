import request from './request'

export function searchAdminAccounts(params) {
    return request.get('/admin/accounts', { params })
}

export function getAdminAccountDetail(accountId) {
    return request.get(`/admin/accounts/${accountId}`)
}

export function banAdminAccount(accountId, data) {
    return request.post(`/admin/accounts/${accountId}/ban`, data)
}

export function unbanAdminAccount(accountId, data) {
    return request.post(`/admin/accounts/${accountId}/unban`, data)
}

export function kickoutAdminAccount(accountId, data) {
    return request.post(`/admin/accounts/${accountId}/kickout`, data)
}

export function adjustAdminCredits(accountId, data) {
    return request.post(`/admin/accounts/${accountId}/credits/adjust`, data)
}

export function grantAdminRole(accountId, data) {
    return request.post(`/admin/accounts/${accountId}/grant-admin`, data)
}

export function revokeAdminRole(accountId, data) {
    return request.post(`/admin/accounts/${accountId}/revoke-admin`, data)
}

export function searchAdminLogs(params) {
    return request.get('/admin/logs', { params })
}

export function searchAdminRooms(params) {
    return request.get('/admin/rooms', { params })
}

export function getAdminRoomDetail(roomId) {
    return request.get(`/admin/rooms/${roomId}`)
}

export function getAdminRoomMembers(roomId) {
    return request.get(`/admin/rooms/${roomId}/members`)
}

export function closeAdminRoom(roomId, data) {
    return request.post(`/admin/rooms/${roomId}/close`, data)
}

export function kickAdminRoomMember(roomId, accountId, data) {
    return request.post(`/admin/rooms/${roomId}/members/${accountId}/kick`, data)
}

export function muteAdminRoomMember(roomId, accountId, data) {
    return request.post(`/admin/rooms/${roomId}/members/${accountId}/mute`, data)
}

export function unmuteAdminRoomMember(roomId, accountId, data) {
    return request.post(`/admin/rooms/${roomId}/members/${accountId}/unmute`, data)
}
