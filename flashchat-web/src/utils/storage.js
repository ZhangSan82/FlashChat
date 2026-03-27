const KEY = 'flashchat_identity'

export function saveIdentity(data) {
  try { localStorage.setItem(KEY, JSON.stringify(data)) } catch {}
}

export function loadIdentity() {
  try {
    const raw = localStorage.getItem(KEY)
    return raw ? JSON.parse(raw) : null
  } catch { return null }
}

export function clearIdentity() {
  localStorage.removeItem(KEY)
}
