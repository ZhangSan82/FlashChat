import axios from 'axios'

const request = axios.create({
  baseURL: '/api/FlashChat/v1',
  timeout: 10000,
  headers: { 'Content-Type': 'application/json' }
})

request.interceptors.request.use(c => c, e => Promise.reject(e))

request.interceptors.response.use(
  (resp) => {
    const res = resp.data
    if (res.code === '200') return res.data
    return Promise.reject(new Error(res.message || `业务错误[${res.code}]`))
  },
  (err) => {
    if (err.code === 'ERR_NETWORK') console.error('[API] 后端未启动')
    return Promise.reject(err)
  }
)

export default request
