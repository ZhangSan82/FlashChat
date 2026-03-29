/**
 * 统一头像解析器
 *
 * 所有需要显示头像的地方必须通过此函数
 * 优先级：上传的图片 URL > 颜色生成的 SVG > 默认
 */
import { generateAvatarUrl } from './formatter'

/**
 * 解析应该显示的头像
 * @param {string|null} avatarUrl   - 上传的头像图片 URL（如 http://xxx/uploads/xxx.jpg）
 * @param {string|null} avatarColor - 头像背景色（如 #FF6B6B）
 * @param {string|null} nickname    - 昵称（取首字生成 SVG 头像）
 * @returns {string} 可直接赋给 <img src> 或 vue-advanced-chat user.avatar 的值
 */
export function resolveAvatar(avatarUrl, avatarColor, nickname) {
    // 优先级 1：用户上传的头像图片
    if (avatarUrl && avatarUrl.length > 0) {
        return avatarUrl
    }

    // 优先级 2：用颜色 + 首字符生成 SVG
    const color = avatarColor?.startsWith('#') ? avatarColor : '#C8956C'
    const name = nickname || '?'
    return generateAvatarUrl(name, color)
}

/**
 * 从后端 avatar 字段解析（后端 avatar 字段可能是颜色值或 URL）
 * 后端 RoomMemberRespDTO.avatar 的值：
 *   - "#FF6B6B" → 颜色值，需要生成 SVG
 *   - "http://xxx/uploads/xxx.jpg" → 已上传的图片 URL，直接用
 *
 * @param {string|null} backendAvatar - 后端返回的 avatar 字段
 * @param {string|null} nickname
 * @returns {string}
 */
export function resolveBackendAvatar(backendAvatar, nickname) {
    if (!backendAvatar || backendAvatar.length === 0) {
        return generateAvatarUrl(nickname || '?', '#C8956C')
    }

    // 颜色值 → 生成 SVG
    if (backendAvatar.startsWith('#')) {
        return generateAvatarUrl(nickname || '?', backendAvatar)
    }

    // 否则当作图片 URL 直接使用
    return backendAvatar
}