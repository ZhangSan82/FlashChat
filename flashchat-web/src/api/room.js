import request from './request'

export const createRoom = (data) => request.post('/room/create', data)
export const joinRoom = (data) => request.post('/room/join', data)
export const leaveRoom = (data) => request.post('/room/leave', data)
export const getRoomMembers = (roomId) => request.get(`/room/members/${roomId}`)
export const getMyRooms = (accountId) => request.get('/room/my-rooms', { params: { accountId } })
export const kickMember = (data) => request.post('/room/kick', data)
export const muteMember = (data) => request.post('/room/mute', data)
export const unmuteMember = (data) => request.post('/room/unmute', data)
export const closeRoom = (data) => request.post('/room/close', data)
