import request from './request'

export function uploadFile(file, filename = '') {
    const fd = new FormData()
    if (filename) {
        fd.append('file', file, filename)
    } else {
        fd.append('file', file)
    }
    return request.post('/file/upload', fd, { timeout: 30000 })
}
