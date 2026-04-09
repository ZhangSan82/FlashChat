export function formatTime(ts) {
  if (!ts) return ''
  return new Date(ts).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
}

export function formatDate(ts) {
  if (!ts) return ''
  const d = new Date(ts), now = new Date()
  if (d.toDateString() === now.toDateString()) return '今天'
  const y = new Date(now); y.setDate(y.getDate() - 1)
  if (d.toDateString() === y.toDateString()) return '昨天'
  return `${d.getMonth() + 1}月${d.getDate()}日`
}

export function formatCountdown(ms) {
  if (!ms || ms <= 0) return '已过期'
  const m = Math.floor(ms / 60000), h = Math.floor(m / 60), d = Math.floor(h / 24)
  if (d > 0) return `${d}d ${h % 24}h left`
  if (h > 0) return `${h}h ${m % 60}m left`
  if (m > 0) return `${m}m left`
  return '< 1m'
}

export function formatCountdownShort(ms) {
  if (!ms || ms <= 0) return '过期'
  const m = Math.floor(ms / 60000), h = Math.floor(m / 60), d = Math.floor(h / 24)
  if (d > 0) return `${d}d${h % 24}h`
  if (h > 0) return `${h}h${m % 60}m`
  return `${m}m`
}

export function calcProgress(expireTime, createTime) {
  if (!expireTime) return 1
  const now = Date.now(), exp = new Date(expireTime).getTime(), crt = new Date(createTime).getTime()
  const total = exp - crt, remain = exp - now
  if (total <= 0) return 0
  return Math.max(0, Math.min(1, remain / total))
}

export function getCountdownColor(ms) {
  if (!ms || ms <= 0) return '#D4736C'
  const m = ms / 60000
  if (m > 30) return '#7BAF6E'
  if (m > 5) return '#D4A84C'
  return '#D4736C'
}

export function generateAvatarUrl(name, color) {
  const ch = (name || '?').charAt(0).toUpperCase()
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="80" height="80"><rect width="80" height="80" rx="40" fill="${color || '#C8956C'}"/><text x="40" y="40" dy=".35em" text-anchor="middle" font-family="Inter,system-ui,sans-serif" font-size="32" font-weight="600" fill="white">${ch}</text></svg>`
  return `data:image/svg+xml;base64,${btoa(unescape(encodeURIComponent(svg)))}`
}
