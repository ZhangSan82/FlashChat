import request from './request'

export function createGame(data) {
  return request.post('/game/create', data)
}

export function getActiveGame(roomId) {
  return request.get(`/game/active/${roomId}`)
}

export function joinGame(data) {
  return request.post('/game/join', data)
}

export function leaveGame(data) {
  return request.post('/game/leave', data)
}

export function cancelGame(data) {
  return request.post('/game/cancel', data)
}

export function addAiPlayer(data) {
  return request.post('/game/ai/add', data)
}

export function startGame(data) {
  return request.post('/game/start', data)
}

export function submitDescription(data) {
  return request.post('/game/describe', data)
}

export function submitVote(data) {
  return request.post('/game/vote', data)
}

export function getGameState(gameId) {
  return request.get(`/game/state/${gameId}`)
}

export function getGameHistory(gameId) {
  return request.get(`/game/history/${gameId}`)
}
