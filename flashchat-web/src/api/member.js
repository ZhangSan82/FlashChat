import request from './request'

export const autoRegister = () => request.post('/member/auto-register')
export const getMemberInfo = (id) => request.get(`/member/info/${id}`)
export const getMemberFull = (id) => request.get(`/member/full/${id}`)
