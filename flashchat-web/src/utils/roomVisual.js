import { generateAvatarUrl } from './formatter'

const ROOM_IMAGE_KEYS = [
  'avatarUrl',
  'roomAvatarUrl',
  'coverUrl',
  'imageUrl',
  'roomImageUrl',
  'roomCoverUrl',
  'posterUrl',
  'iconUrl',
  'avatar',
  'cover',
  'image'
]

const ROOM_COLOR_KEYS = [
  'avatarColor',
  'coverColor',
  'themeColor'
]

function pickFirstString(source, keys) {
  for (const key of keys) {
    const value = source?.[key]
    if (typeof value === 'string' && value.trim().length > 0) {
      return value.trim()
    }
  }
  return ''
}

function isImageLike(value) {
  return value.startsWith('http://')
    || value.startsWith('https://')
    || value.startsWith('/')
    || value.startsWith('data:image/')
}

export function getRoomDisplayName(room) {
  return room?.title?.trim?.()
    || room?.roomName?.trim?.()
    || room?._raw?.title?.trim?.()
    || room?._raw?.roomName?.trim?.()
    || room?.roomId
    || '未命名房间'
}

export function getRoomVisualUrl(room) {
  const image = pickFirstString(room, ROOM_IMAGE_KEYS)
  if (image && isImageLike(image)) {
    return image
  }

  const color = pickFirstString(room, ROOM_COLOR_KEYS)
  return generateAvatarUrl(getRoomDisplayName(room), color || '#B68450')
}
