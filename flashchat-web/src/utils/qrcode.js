/**
 * QR 码生成工具（纯前端，零网络依赖）
 *
 * 使用 qrcode 库生成 Data URL，离线可用，无隐私风险
 */
import QRCode from 'qrcode'

/**
 * 生成 QR 码 Data URL
 * @param {string} text - 编码内容（如分享链接）
 * @param {number} size - 图片尺寸（像素）
 * @returns {Promise<string|null>} base64 Data URL，失败返回 null
 */
export async function generateQRCodeDataUrl(text, size = 200) {
    if (!text) return null
    try {
        return await QRCode.toDataURL(text, {
            width: size,
            margin: 2,
            color: {
                dark: '#2C2825',
                light: '#FFFFFF'
            },
            errorCorrectionLevel: 'M'
        })
    } catch (e) {
        console.warn('[QRCode] 生成失败', e)
        return null
    }
}