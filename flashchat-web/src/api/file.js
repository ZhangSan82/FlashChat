import request from './request'

export function uploadFile(file) {
  const fd = new FormData()
  fd.append('file', file)
  return request.post('/file/upload', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 30000
  })
}
