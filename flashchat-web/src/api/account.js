/**
 * 账号接口
 * 对齐后端 AccountController（/api/FlashChat/v1/account）
 *
 * 重要：所有需要身份的接口由 request.js 自动注入 satoken Header
 *       后端通过 UserContext.getRequiredLoginId() 获取当前用户
 *       前端不需要传 accountId 参数
 */
import request from './request'

// ==================== Phase 1 需要的接口 ====================

/**
 * 匿名自动注册
 * 无需 token（在 SaToken 放行列表中）
 * @returns {Promise<AuthRespDTO>} { token, accountId, nickname, avatarColor, avatarUrl, isRegistered }
 */
export function autoRegister() {
    return request.post('/account/auto-register')
}

/**
 * 检查登录状态（需要 token）
 * 用于页面刷新后恢复身份
 * 401 = token 失效，由 request.js 拦截器自动处理
 * @returns {Promise<AuthRespDTO>}
 */
export function checkLogin() {
    return request.get('/account/check')
}

/**
 * accountId + 密码登录
 * 无需 token（在 SaToken 放行列表中）
 * @param {{ accountId: string, password: string }} data
 * @returns {Promise<AuthRespDTO>}
 */
export function login(data) {
    return request.post('/account/login', data)
}

/**
 * 邮箱 + 密码登录
 * 无需 token（在 SaToken 放行列表中）
 * 仅限已注册（绑定邮箱 + 设了密码）的用户
 * @param {{ email: string, password: string }} data
 * @returns {Promise<AuthRespDTO>}
 */
export function loginByEmail(data) {
    return request.post('/account/login-by-email', data)
}

/**
 * 登出当前用户
 * @returns {Promise<void>}
 */
export function logout() {
    return request.post('/account/logout')
}

/**
 * 查询账号公开信息（通过业务 ID）
 * @param {string} accountId - 如 "FC-8A3D7K"
 */
export function getAccountInfo(accountId) {
    return request.get(`/account/info/${accountId}`)
}

// ==================== Phase 3 需要的接口（预留） ====================

/** 获取当前登录用户的完整信息 */
export function getMyAccount() {
    return request.get('/account/me')
}

/** 修改个人资料 @param {{ nickname?, avatarColor?, avatarUrl? }} data */
export function updateProfile(data) {
    return request.put('/account/profile', data)
}

/** 首次设置密码 @param {{ password, confirmPassword }} data */
export function setPassword(data) {
    return request.post('/account/set-password', data)
}

/** 修改密码 @param {{ oldPassword, newPassword, confirmNewPassword }} data */
export function changePassword(data) {
    return request.post('/account/change-password', data)
}

/** 匿名用户升级为注册用户 @param {{ password, confirmPassword, email, inviteCode? }} data */
export function upgradeAccount(data) {
    return request.post('/account/upgrade', data)
}

/** 注销账号 */
export function deleteAccount() {
    return request.delete('/account/delete')
}

/** 每日签到 */
export function dailyCheckIn() {
    return request.post('/account/daily-check-in')
}

/** 查询积分余额 */
export function getCreditBalance() {
    return request.get('/account/credits/balance')
}

/** 查询积分流水 */
export function getCreditTransactions(page = 1, size = 20) {
    return request.get('/account/credits/transactions', { params: { page, size } })
}

/** 查询邀请码列表 */
export function getInviteCodes() {
    return request.get('/account/invite-codes')
}