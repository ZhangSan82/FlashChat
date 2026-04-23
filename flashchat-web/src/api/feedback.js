import request from './request'

export function submitFeedback(data) {
    return request.post('/feedback', data)
}

export function searchAdminFeedbacks(params) {
    return request.get('/admin/feedbacks', { params })
}

export function getAdminFeedbackDetail(feedbackId) {
    return request.get(`/admin/feedbacks/${feedbackId}`)
}

export function processAdminFeedback(feedbackId, data) {
    return request.post(`/admin/feedbacks/${feedbackId}/process`, data)
}
