import { reactive } from 'vue'
import * as gameApi from '@/api/game'
import { buildEndedResultFromHistory, buildRoundResultsFromHistory } from '@/utils/gameHistory'
import { normalizeGameText } from '@/utils/gameText'

export const GAME_WS_TYPE = Object.freeze({
  GAME_CREATED: 20,
  GAME_PLAYER_JOINED: 21,
  GAME_STARTED: 22,
  GAME_ROLE_ASSIGNED: 23,
  GAME_TURN_START: 24,
  GAME_DESCRIPTION: 25,
  GAME_AI_THINKING: 26,
  GAME_VOTE_PHASE: 27,
  GAME_VOTE_RESULT: 28,
  GAME_ENDED: 29,
  GAME_PLAYER_LEFT: 30,
  GAME_CANCELLED: 31,
  GAME_PLAYER_DISCONNECTED: 32,
  GAME_PLAYER_RECONNECTED: 33
})

const ACTIVE_GAME_CACHE_KEY = 'flashchat_active_game_v1'
const AI_PROVIDER = 'KIMI'
const VOTE_RESULT_DISPLAY_MS = 2000
const DEFAULT_CONFIG = Object.freeze({
  enableBlank: false,
  describeTimeout: 60,
  voteTimeout: 30,
  maxPlayers: 10,
  minPlayers: 4,
  maxAiPlayers: 4
})

function createDefaultConfig(config = {}) {
  return {
    enableBlank: Boolean(config?.enableBlank),
    describeTimeout: Number(config?.describeTimeout ?? DEFAULT_CONFIG.describeTimeout),
    voteTimeout: Number(config?.voteTimeout ?? DEFAULT_CONFIG.voteTimeout),
    maxPlayers: Number(config?.maxPlayers ?? DEFAULT_CONFIG.maxPlayers),
    minPlayers: Number(config?.minPlayers ?? DEFAULT_CONFIG.minPlayers),
    maxAiPlayers: Number(config?.maxAiPlayers ?? DEFAULT_CONFIG.maxAiPlayers)
  }
}

function createEmptySession() {
  return {
    gameId: '',
    roomId: '',
    creatorId: null,
    gameStatus: null,
    currentPhase: null,
    currentRound: 0,
    myRole: '',
    myWord: '',
    myVoted: false,
    currentSpeakerAccountId: null,
    currentSpeakerNickname: '',
    currentSpeakerOrder: null,
    turnDeadline: null,
    timerRemaining: 0,
    players: [],
    descriptions: [],
    votableTargets: [],
    voteResult: null,
    roundResults: [],
    result: null,
    history: null,
    config: createDefaultConfig(),
    aiThinkingAccountId: null
  }
}

function normalizePlayers(players = []) {
  return [...(players || [])]
    .map(player => ({
      accountId: player?.accountId ?? null,
      nickname: normalizeGameText(player?.nickname || '匿名玩家'),
      avatar: player?.avatar || '',
      playerType: player?.playerType || 'HUMAN',
      aiProvider: player?.aiProvider || '',
      aiPersona: player?.aiPersona || '',
      status: player?.status || 'ALIVE',
      playerOrder: player?.playerOrder ?? null,
      role: player?.role || player?.revealedRole || '',
      word: normalizeGameText(player?.word || '')
    }))
    .sort((left, right) => {
      const leftOrder = left.playerOrder ?? Number.MAX_SAFE_INTEGER
      const rightOrder = right.playerOrder ?? Number.MAX_SAFE_INTEGER
      if (leftOrder !== rightOrder) return leftOrder - rightOrder
      return String(left.accountId ?? '').localeCompare(String(right.accountId ?? ''))
    })
}

function normalizeDescriptions(descriptions = []) {
  return [...(descriptions || [])].map(item => ({
    speakerAccountId: item?.speakerAccountId ?? null,
    speakerNickname: normalizeGameText(item?.speakerNickname || '匿名玩家'),
    content: normalizeGameText(item?.content || ''),
    isSkipped: Boolean(item?.isSkipped),
    roundNumber: Number(item?.roundNumber || 0)
  }))
}

function normalizeVoteTargets(targets = [], selfAccountId = null) {
  return [...(targets || [])]
    .map(target => ({
      accountId: target?.accountId ?? null,
      nickname: normalizeGameText(target?.nickname || '匿名玩家'),
      status: target?.status || 'ALIVE'
    }))
    .filter(target => selfAccountId == null || String(target.accountId) !== String(selfAccountId))
}

function normalizeRoundResults(roundResults = []) {
  return [...(roundResults || [])]
    .map(item => ({
      roundNumber: Number(item?.roundNumber || 0),
      eliminatedAccountId: item?.eliminatedAccountId ?? null,
      eliminatedNickname: normalizeGameText(item?.eliminatedNickname || ''),
      eliminatedRole: item?.eliminatedRole || '',
      isTie: Boolean(item?.isTie)
    }))
    .filter(item => item.roundNumber > 0)
    .sort((left, right) => Number(left.roundNumber) - Number(right.roundNumber))
}

function normalizeActiveGame(info) {
  if (!info?.gameId) return null
  return {
    gameId: info.gameId,
    roomId: info.roomId || '',
    creatorId: info.creatorId ?? null,
    gameStatus: info.gameStatus || 'WAITING',
    currentRound: Number(info.currentRound || 0),
    playerCount: Number(info.playerCount ?? info.players?.length ?? 0),
    maxPlayers: Number(info.maxPlayers ?? info.config?.maxPlayers ?? DEFAULT_CONFIG.maxPlayers),
    minPlayers: Number(info.minPlayers ?? info.config?.minPlayers ?? DEFAULT_CONFIG.minPlayers),
    config: createDefaultConfig(info.config),
    players: normalizePlayers(info.players || [])
  }
}

function normalizeVoteResult(data) {
  return {
    roundNumber: Number(data?.roundNumber || 0),
    eliminatedAccountId: data?.eliminatedAccountId ?? null,
    eliminatedNickname: normalizeGameText(data?.eliminatedNickname || ''),
    eliminatedRole: data?.eliminatedRole || '',
    isTie: Boolean(data?.isTie),
    voteDetails: Object.entries(data?.voteDetails || {}).map(([targetAccountId, votes]) => ({
      targetAccountId,
      votes: [...(votes || [])].map(vote => {
        if (vote && typeof vote === 'object') {
          return {
            voterAccountId: vote.voterAccountId ?? null,
            isAuto: Boolean(vote.isAuto)
          }
        }
        return {
          voterAccountId: vote,
          isAuto: false
        }
      })
    }))
  }
}

function normalizeEndedResult(data) {
  return {
    gameId: data?.gameId || '',
    winnerSide: data?.winnerSide || '',
    endReason: data?.endReason || '',
    civilianWord: normalizeGameText(data?.civilianWord || ''),
    spyWord: normalizeGameText(data?.spyWord || ''),
    playerRoles: normalizePlayers(data?.playerRoles || [])
  }
}

export function useGame({ getCurrentRoomId, getMemberId, getIdentity } = {}) {
  const state = reactive({
    currentRoomId: '',
    roomActiveInfo: null,
    ignoredActiveGameId: '',
    session: createEmptySession(),
    loadingActive: false,
    actionPending: false,
    historyLoading: false,
    collapsed: false,
    notice: '',
    noticeTone: 'info'
  })

  let countdownTimer = null
  let noticeTimer = null
  let voteResultTimer = null
  let activeFetchSeq = 0
  let restoreStateSeq = 0

  function invalidateActiveFetches() {
    activeFetchSeq += 1
    state.loadingActive = false
  }

  function getSelfAccountId() {
    return getMemberId?.() ?? null
  }

  function getAvatarValue() {
    const identity = getIdentity?.()
    return identity?.avatarUrl || identity?.avatarColor || '#C8956C'
  }

  function isSelf(accountId) {
    const selfAccountId = getSelfAccountId()
    return selfAccountId != null && String(selfAccountId) === String(accountId)
  }

  function setNotice(message, tone = 'info') {
    if (!message) return
    state.notice = message
    state.noticeTone = tone
    clearTimeout(noticeTimer)
    noticeTimer = setTimeout(() => {
      if (state.notice === message) {
        state.notice = ''
      }
    }, 4200)
  }

  function stopCountdown() {
    if (countdownTimer) {
      clearInterval(countdownTimer)
      countdownTimer = null
    }
    state.session.timerRemaining = 0
  }

  function clearVoteResultDisplay() {
    if (voteResultTimer) {
      clearTimeout(voteResultTimer)
      voteResultTimer = null
    }
    state.session.voteResult = null
  }

  function startCountdown(deadline, timeoutSeconds = null) {
    stopCountdown()
    state.session.turnDeadline = deadline ?? null
    const durationMs = Number.isFinite(timeoutSeconds)
      ? Math.max(0, Math.round(timeoutSeconds * 1000))
      : Math.max(0, Number(deadline || 0) - Date.now())

    if (durationMs <= 0) {
      state.session.timerRemaining = 0
      return
    }

    const localEnd = Date.now() + durationMs
    const update = () => {
      const remaining = Math.max(0, Math.ceil((localEnd - Date.now()) / 1000))
      state.session.timerRemaining = remaining
      if (remaining <= 0 && countdownTimer) {
        clearInterval(countdownTimer)
        countdownTimer = null
      }
    }

    update()
    countdownTimer = setInterval(update, 200)
  }

  function persistSession(gameId, roomId) {
    if (!gameId) return
    try {
      sessionStorage.setItem(ACTIVE_GAME_CACHE_KEY, JSON.stringify({
        gameId,
        roomId,
        cachedAt: Date.now()
      }))
    } catch {
      // ignore storage errors
    }
  }

  function readPersistedSession() {
    try {
      const raw = sessionStorage.getItem(ACTIVE_GAME_CACHE_KEY)
      return raw ? JSON.parse(raw) : null
    } catch {
      return null
    }
  }

  function clearPersistedSession(gameId = null) {
    try {
      const cached = readPersistedSession()
      if (!cached) return
      if (!gameId || cached.gameId === gameId) {
        sessionStorage.removeItem(ACTIVE_GAME_CACHE_KEY)
      }
    } catch {
      // ignore storage errors
    }
  }

  function resetSession({ keepResult = false } = {}) {
    const preservedResult = keepResult ? state.session.result : null
    stopCountdown()
    clearVoteResultDisplay()
    Object.assign(state.session, createEmptySession())
    state.session.result = preservedResult
    state.collapsed = false
  }

  function buildRoomActiveFromSession() {
    if (!state.session.gameId || !state.session.roomId) return null
    return {
      gameId: state.session.gameId,
      roomId: state.session.roomId,
      creatorId: state.session.creatorId,
      gameStatus: state.session.gameStatus || 'WAITING',
      currentRound: state.session.currentRound || 0,
      playerCount: state.session.players.length,
      maxPlayers: state.session.config.maxPlayers,
      minPlayers: state.session.config.minPlayers,
      config: createDefaultConfig(state.session.config),
      players: normalizePlayers(state.session.players)
    }
  }

  function syncRoomActiveFromSession() {
    const fromSession = buildRoomActiveFromSession()
    if (!fromSession) return
    if (state.currentRoomId && state.currentRoomId !== fromSession.roomId) return
    state.roomActiveInfo = fromSession
  }

  function isPlayerInList(players = []) {
    return players.some(player => isSelf(player.accountId))
  }

  function patchSessionFromActiveInfo(info, { preserveRuntime = false } = {}) {
    const normalized = normalizeActiveGame(info)
    if (!normalized) return null

    const previous = preserveRuntime ? {
      myRole: state.session.myRole,
      myWord: state.session.myWord,
      myVoted: state.session.myVoted,
      currentPhase: state.session.currentPhase,
      currentSpeakerAccountId: state.session.currentSpeakerAccountId,
      currentSpeakerNickname: state.session.currentSpeakerNickname,
      currentSpeakerOrder: state.session.currentSpeakerOrder,
      turnDeadline: state.session.turnDeadline,
      timerRemaining: state.session.timerRemaining,
      descriptions: [...state.session.descriptions],
      votableTargets: [...state.session.votableTargets],
      voteResult: state.session.voteResult,
      roundResults: [...state.session.roundResults],
      result: state.session.result,
      history: state.session.history,
      aiThinkingAccountId: state.session.aiThinkingAccountId
    } : {}

    Object.assign(state.session, createEmptySession(), previous, {
      gameId: normalized.gameId,
      roomId: normalized.roomId,
      creatorId: normalized.creatorId,
      gameStatus: normalized.gameStatus,
      currentRound: normalized.currentRound,
      players: normalized.players,
      config: normalized.config
    })

    state.ignoredActiveGameId = ''
    persistSession(state.session.gameId, state.session.roomId)
    syncRoomActiveFromSession()
    return normalized
  }

  function patchSessionFromStatePayload(payload) {
    if (!payload?.gameId) return
    const selfAccountId = getSelfAccountId()
    const restoredRoundResults = Array.isArray(payload.roundResults)
      ? normalizeRoundResults(payload.roundResults)
      : state.session.roundResults

    Object.assign(state.session, createEmptySession(), {
      gameId: payload.gameId,
      roomId: payload.roomId || state.session.roomId,
      creatorId: state.session.creatorId || state.roomActiveInfo?.creatorId || null,
      gameStatus: payload.gameStatus || state.session.gameStatus,
      currentPhase: payload.currentPhase || null,
      currentRound: Number(payload.currentRound || 0),
      myRole: payload.myRole || '',
      myWord: normalizeGameText(payload.myWord || ''),
      myVoted: Boolean(payload.myVoted),
      currentSpeakerAccountId: payload.currentSpeakerAccountId ?? null,
      currentSpeakerNickname: normalizeGameText(payload.currentSpeakerNickname || ''),
      currentSpeakerOrder: payload.currentSpeakerOrder ?? null,
      turnDeadline: payload.turnDeadline ?? null,
      timerRemaining: state.session.timerRemaining,
      players: normalizePlayers(payload.players || []),
      descriptions: normalizeDescriptions(payload.currentRoundDescriptions || []),
      votableTargets: normalizeVoteTargets(payload.votableTargets || [], selfAccountId),
      config: createDefaultConfig(payload.config),
      roundResults: restoredRoundResults,
      history: state.session.history,
      result: state.session.result,
      aiThinkingAccountId: null
    })

    if (state.session.currentPhase === 'DESCRIBING' || state.session.currentPhase === 'VOTING') {
      startCountdown(payload.turnDeadline, null)
    } else {
      stopCountdown()
    }

    persistSession(state.session.gameId, state.session.roomId)
    syncRoomActiveFromSession()
  }

  function upsertPlayers(target, patches = [], { replaceAll = false } = {}) {
    const existing = replaceAll ? [] : [...(target || [])]
    const mapped = new Map(existing.map(player => [String(player.accountId), { ...player }]))

    for (const patch of patches) {
      if (patch?.accountId == null) continue
      const key = String(patch.accountId)
      mapped.set(key, {
        ...(mapped.get(key) || {}),
        ...patch
      })
    }

    return normalizePlayers([...mapped.values()])
  }

  function removePlayerFromLists(accountId) {
    state.session.players = state.session.players.filter(player => String(player.accountId) !== String(accountId))
    if (state.roomActiveInfo?.players) {
      state.roomActiveInfo.players = state.roomActiveInfo.players
        .filter(player => String(player.accountId) !== String(accountId))
      state.roomActiveInfo.playerCount = state.roomActiveInfo.players.length
    }
  }

  function updatePlayerStatus(accountId, status, patch = {}) {
    state.session.players = upsertPlayers(state.session.players, [{
      accountId,
      status,
      ...patch
    }])

    if (state.roomActiveInfo?.gameId === state.session.gameId) {
      state.roomActiveInfo.players = upsertPlayers(state.roomActiveInfo.players, [{
        accountId,
        status,
        ...patch
      }])
    }
  }

  function updateRoomActiveInfo(info) {
    const normalized = normalizeActiveGame(info)
    if (!normalized || (state.ignoredActiveGameId && String(normalized.gameId) !== String(state.ignoredActiveGameId))) {
      state.ignoredActiveGameId = ''
    }
    state.roomActiveInfo = normalized
    return state.roomActiveInfo
  }

  function dismissRoomActivePrompt(gameId = state.roomActiveInfo?.gameId) {
    if (!gameId) return
    state.ignoredActiveGameId = String(gameId)
  }

  function ensureSessionPresence({ gameId = '', roomId = '', gameStatus = 'WAITING' } = {}) {
    if (!state.session.gameId) {
      Object.assign(state.session, createEmptySession(), {
        gameId: gameId || state.roomActiveInfo?.gameId || '',
        roomId: roomId || state.roomActiveInfo?.roomId || state.currentRoomId || '',
        creatorId: state.roomActiveInfo?.creatorId || null,
        gameStatus
      })
    }
  }

  function clearRoomActiveIfMatches(gameId) {
    if (state.roomActiveInfo?.gameId && String(state.roomActiveInfo.gameId) === String(gameId)) {
      state.roomActiveInfo = null
    }
    if (state.ignoredActiveGameId && String(state.ignoredActiveGameId) === String(gameId)) {
      state.ignoredActiveGameId = ''
    }
  }

  function getEndedNotice(result = {}) {
    if (result?.winnerSide === 'SPY') return '卧底获胜'
    if (result?.winnerSide === 'CIVILIAN') return '平民获胜'
    if (result?.endReason === 'NO_HUMANS_LEFT') return '真人玩家已全部出局，本局结束'
    if (result?.endReason === 'ALL_DISCONNECTED') return '所有真人玩家离线，本局结束'
    if (result?.endReason === 'CANCELLED') return '本局已取消'
    return '本局已结束'
  }

  function getEndedNoticeTone(result = {}) {
    if (result?.winnerSide) return 'success'
    if (result?.endReason === 'ALL_DISCONNECTED') return 'warning'
    return 'info'
  }

  function applyEndedResult(result, { history = state.session.history, silent = false } = {}) {
    if (!result?.gameId) return

    clearRoomActiveIfMatches(result.gameId)
    clearPersistedSession(result.gameId)
    clearVoteResultDisplay()
    stopCountdown()

    state.session.result = result
    state.session.history = history
    state.session.gameStatus = 'ENDED'
    state.session.currentPhase = null
    state.session.currentSpeakerAccountId = null
    state.session.currentSpeakerNickname = ''
    state.session.currentSpeakerOrder = null
    state.session.turnDeadline = null
    state.session.descriptions = []
    state.session.votableTargets = []
    state.session.voteResult = null
    state.session.myVoted = false
    state.session.aiThinkingAccountId = null
    state.session.players = upsertPlayers(state.session.players, result.playerRoles, { replaceAll: true })

    if (history?.rounds) {
      state.session.roundResults = buildRoundResultsFromHistory(history.rounds)
    }

    state.collapsed = false

    if (!silent) {
      setNotice(getEndedNotice(result), getEndedNoticeTone(result))
    }
  }

  async function recoverEndedSession(roomId = state.currentRoomId, { silent = true } = {}) {
    const gameId = state.session.gameId
    const sessionRoomId = state.session.roomId || roomId
    if (!gameId || state.session.result || !sessionRoomId) return false
    if (roomId && String(sessionRoomId) !== String(roomId)) return false

    try {
      const history = await gameApi.getGameHistory(gameId)
      if (!history?.gameId) return false
      applyEndedResult(buildEndedResultFromHistory(history), { history, silent })
      return true
    } catch {
      return false
    }
  }

  function isRecoveryError(message = '') {
    return String(message).includes('恢复') || String(message).includes('不支持')
  }

  function isWaitingStageConflict(message = '') {
    return String(message).includes('当前游戏不处于等待阶段')
  }

  async function refreshActiveGame(roomId = state.currentRoomId, { silent = false } = {}) {
    if (!roomId) {
      state.roomActiveInfo = null
      return null
    }

    const requestId = ++activeFetchSeq
    state.loadingActive = true

    try {
      const info = await gameApi.getActiveGame(roomId)
      if (requestId !== activeFetchSeq) return state.roomActiveInfo

      const normalized = updateRoomActiveInfo(info)

      if (normalized && isPlayerInList(normalized.players)) {
        const preserveRuntime = Boolean(state.session.gameId) &&
          String(state.session.gameId) === String(normalized.gameId)
        patchSessionFromActiveInfo(normalized, { preserveRuntime })
        if (normalized.gameStatus === 'PLAYING') {
          await restoreGameState(normalized.gameId, { silent: true })
        }
      } else if (!normalized) {
        const recoveredEndedSession = await recoverEndedSession(roomId, { silent })
        if (!recoveredEndedSession && !state.session.gameId && !state.session.result) {
          resetSession()
          clearPersistedSession()
        }
      }

      return normalized
    } catch (error) {
      if (!silent) {
        setNotice(error?.message || '加载游戏失败', 'error')
      }
      return null
    } finally {
      if (requestId === activeFetchSeq) {
        state.loadingActive = false
      }
    }
  }

  async function restoreGameState(gameId, { silent = false } = {}) {
    if (!gameId) return null
    const requestSeq = ++restoreStateSeq

    try {
      const payload = await gameApi.getGameState(gameId)
      if (requestSeq !== restoreStateSeq) {
        return payload
      }
      patchSessionFromStatePayload(payload)
      if (!state.roomActiveInfo && state.session.roomId === state.currentRoomId) {
        syncRoomActiveFromSession()
      }
      return payload
    } catch (error) {
      clearPersistedSession(gameId)
      if (isRecoveryError(error?.message)) {
        resetSession()
        if (!silent) {
          setNotice('游戏因服务维护已结束，请重新创建', 'warning')
        }
        if (state.currentRoomId) {
          await refreshActiveGame(state.currentRoomId, { silent: true })
        }
        return null
      }

      if (!silent) {
        setNotice(error?.message || '恢复游戏状态失败', 'error')
      }
      return null
    }
  }

  async function syncAfterWaitingStageConflict(gameId, roomId = state.currentRoomId) {
    const restored = await restoreGameState(gameId, { silent: true })
    if (!restored && roomId) {
      await refreshActiveGame(roomId, { silent: true })
    }
    return restored
  }

  function scheduleStateRestore(gameId = state.session.gameId || state.roomActiveInfo?.gameId) {
    if (!gameId) return
    queueMicrotask(() => {
      void restoreGameState(gameId, { silent: true })
    })
  }

  async function withAction(task) {
    if (state.actionPending) return null
    state.actionPending = true
    try {
      return await task()
    } catch (error) {
      if (isRecoveryError(error?.message)) {
        resetSession()
        clearPersistedSession()
        setNotice('游戏因服务维护已结束，请重新创建', 'warning')
      } else {
        setNotice(error?.message || '操作失败', 'error')
      }
      return null
    } finally {
      state.actionPending = false
    }
  }

  async function enterRoom(roomId) {
    const nextRoomId = roomId || ''
    const switchedAwayFromSession = Boolean(state.session.gameId) &&
      Boolean(state.session.roomId) &&
      Boolean(nextRoomId) &&
      String(state.session.roomId) !== String(nextRoomId)

    state.currentRoomId = nextRoomId
    if (!nextRoomId) {
      state.roomActiveInfo = null
      return null
    }

    if (switchedAwayFromSession) {
      resetSession()
      state.roomActiveInfo = null
    }

    return refreshActiveGame(nextRoomId, { silent: true })
  }

  async function handleSocketConnected() {
    const cached = readPersistedSession()
    if (cached?.gameId) {
      if (state.currentRoomId && cached.roomId && String(state.currentRoomId) !== String(cached.roomId)) {
        await refreshActiveGame(state.currentRoomId, { silent: true })
        return
      }
      await restoreGameState(cached.gameId, { silent: true })
      const roomToRefresh = state.currentRoomId || cached.roomId
      if (roomToRefresh) {
        await refreshActiveGame(roomToRefresh, { silent: true })
      }
      return
    }

    if (state.currentRoomId) {
      await refreshActiveGame(state.currentRoomId, { silent: true })
    }
  }

  async function createGame(config = {}) {
    return withAction(async () => {
      invalidateActiveFetches()
      const roomId = getCurrentRoomId?.() || state.currentRoomId
      const identity = getIdentity?.() || {}
      const payload = {
        roomId,
        nickname: identity.nickname || '我',
        avatar: getAvatarValue(),
        config: {
          minPlayers: Number(config.minPlayers ?? DEFAULT_CONFIG.minPlayers),
          maxPlayers: Number(config.maxPlayers ?? DEFAULT_CONFIG.maxPlayers),
          describeTimeout: Number(config.describeTimeout ?? DEFAULT_CONFIG.describeTimeout),
          voteTimeout: Number(config.voteTimeout ?? DEFAULT_CONFIG.voteTimeout),
          maxAiPlayers: Number(config.maxAiPlayers ?? DEFAULT_CONFIG.maxAiPlayers)
        }
      }

      const info = await gameApi.createGame(payload)
      updateRoomActiveInfo(info)
      patchSessionFromActiveInfo(info)
      setNotice('游戏已创建，等待玩家加入', 'success')
      return info
    })
  }

  async function joinGame(gameId = state.roomActiveInfo?.gameId) {
    return withAction(async () => {
      invalidateActiveFetches()
      if (!gameId) return null
      const identity = getIdentity?.() || {}
      const info = await gameApi.joinGame({
        gameId,
        nickname: identity.nickname || '我',
        avatar: getAvatarValue()
      })
      updateRoomActiveInfo(info)
      patchSessionFromActiveInfo(info)
      setNotice('已加入游戏', 'success')
      return info
    })
  }

  async function leaveGame(gameId = state.session.gameId || state.roomActiveInfo?.gameId) {
    return withAction(async () => {
      invalidateActiveFetches()
      if (!gameId) return null
      const leavingRoomId = state.session.roomId || state.roomActiveInfo?.roomId || state.currentRoomId
      await gameApi.leaveGame({ gameId })
      resetSession()
      clearPersistedSession(gameId)
      await refreshActiveGame(leavingRoomId, { silent: true })
      setNotice('你已退出当前游戏', 'success')
      return true
    })
  }

  async function cancelGame(gameId = state.session.gameId || state.roomActiveInfo?.gameId) {
    return withAction(async () => {
      invalidateActiveFetches()
      if (!gameId) return null
      const currentRoom = state.session.roomId || state.roomActiveInfo?.roomId || state.currentRoomId
      try {
        await gameApi.cancelGame({ gameId })
      } catch (error) {
        if (isWaitingStageConflict(error?.message)) {
          const restored = await syncAfterWaitingStageConflict(gameId, currentRoom)
          if (restored?.gameStatus === 'PLAYING') {
            setNotice('这局游戏已经开始，不能再取消，已同步到最新进度', 'warning')
            return null
          }
        }
        throw error
      }
      resetSession()
      clearPersistedSession(gameId)
      state.roomActiveInfo = null
      if (currentRoom) {
        await refreshActiveGame(currentRoom, { silent: true })
      }
      setNotice('游戏已取消', 'warning')
      return true
    })
  }

  async function addAiPlayer(persona = 'CAUTIOUS') {
    return withAction(async () => {
      invalidateActiveFetches()
      if (!state.session.gameId) return null
      let info
      try {
        info = await gameApi.addAiPlayer({
          gameId: state.session.gameId,
          provider: AI_PROVIDER,
          persona
        })
      } catch (error) {
        if (isWaitingStageConflict(error?.message)) {
          const restored = await syncAfterWaitingStageConflict(state.session.gameId, state.currentRoomId)
          if (restored?.gameStatus === 'PLAYING') {
            setNotice('游戏已经开始，不能再添加 AI，已同步到最新进度', 'warning')
            return null
          }
        }
        throw error
      }
      updateRoomActiveInfo(info)
      patchSessionFromActiveInfo(info)
      setNotice('AI 玩家已加入', 'success')
      return info
    })
  }

  async function startGame() {
    return withAction(async () => {
      invalidateActiveFetches()
      if (!state.session.gameId) return null
      try {
        await gameApi.startGame({ gameId: state.session.gameId })
      } catch (error) {
        if (isWaitingStageConflict(error?.message)) {
          const restored = await syncAfterWaitingStageConflict(state.session.gameId, state.currentRoomId)
          if (restored?.gameStatus === 'PLAYING') {
            setNotice('游戏已经开始，已为你同步最新状态', 'info')
            return true
          }
        }
        throw error
      }
      await restoreGameState(state.session.gameId, { silent: true })
      setNotice('游戏开始中...', 'info')
      return true
    })
  }

  async function submitDescription(content) {
    return withAction(async () => {
      const text = String(content || '').trim()
      if (!text || !state.session.gameId) return null
      await gameApi.submitDescription({
        gameId: state.session.gameId,
        content: text
      })
      setNotice('发言已提交', 'success')
      return true
    })
  }

  async function submitVote(targetAccountId) {
    return withAction(async () => {
      if (!state.session.gameId || targetAccountId == null) return null
      await gameApi.submitVote({
        gameId: state.session.gameId,
        targetAccountId
      })
      state.session.myVoted = true
      setNotice('投票已提交', 'success')
      return true
    })
  }

  async function loadHistory() {
    if (!state.session.gameId || state.historyLoading) return null
    state.historyLoading = true
    try {
      const history = await gameApi.getGameHistory(state.session.gameId)
      state.session.history = history
      return history
    } catch (error) {
      setNotice(error?.message || '加载回放失败', 'error')
      return null
    } finally {
      state.historyLoading = false
    }
  }

  function dismissEndedState() {
    const roomId = state.session.roomId || state.currentRoomId
    resetSession()
    clearPersistedSession()
    if (roomId && roomId === state.currentRoomId) {
      refreshActiveGame(roomId, { silent: true })
    }
  }

  function toggleCollapsed() {
    state.collapsed = !state.collapsed
  }

  function handlePlayerJoined(data, roomId) {
    if (!data?.gameId) return

    const playerPatch = {
      accountId: data.accountId,
      nickname: data.nickname,
      avatar: data.avatar,
      playerType: data.playerType,
      status: 'ALIVE'
    }

    if (state.roomActiveInfo?.gameId === data.gameId) {
      state.roomActiveInfo.players = upsertPlayers(state.roomActiveInfo.players, [playerPatch])
      state.roomActiveInfo.playerCount = Number(data.playerCount ?? state.roomActiveInfo.players.length)
    }

    if (state.session.gameId === data.gameId || (state.session.roomId === roomId && state.session.gameStatus === 'WAITING')) {
      ensureSessionPresence({ gameId: data.gameId, roomId, gameStatus: 'WAITING' })
      state.session.players = upsertPlayers(state.session.players, [playerPatch])
      state.session.gameStatus = state.session.gameStatus || 'WAITING'
      syncRoomActiveFromSession()
    }
  }

  function handlePlayerLeft(data) {
    if (!data?.gameId) return

    if (state.roomActiveInfo?.gameId === data.gameId) {
      state.roomActiveInfo.players = state.roomActiveInfo.players
        .filter(player => String(player.accountId) !== String(data.accountId))
      state.roomActiveInfo.playerCount = Number(data.playerCount ?? state.roomActiveInfo.players.length)
    }

    if (state.session.gameId === data.gameId) {
      state.session.players = state.session.players
        .filter(player => String(player.accountId) !== String(data.accountId))
      syncRoomActiveFromSession()
    }
  }

  function handleGameStarted(data, roomId) {
    if (!data?.gameId) return

    ensureSessionPresence({ gameId: data.gameId, roomId, gameStatus: 'PLAYING' })
    clearVoteResultDisplay()
    state.session.gameStatus = 'PLAYING'
    state.session.currentPhase = null
    state.session.currentRound = Number(state.session.currentRound || 1)
    state.session.players = upsertPlayers(state.session.players, normalizePlayers(data.players || []))
    state.session.descriptions = []
    state.session.votableTargets = []
    state.session.voteResult = null
    state.session.roundResults = []
    state.session.aiThinkingAccountId = null
    persistSession(state.session.gameId, state.session.roomId)
    syncRoomActiveFromSession()
  }

  function handleRoleAssigned(data) {
    ensureSessionPresence({ gameId: state.roomActiveInfo?.gameId, roomId: state.roomActiveInfo?.roomId, gameStatus: 'PLAYING' })
    state.session.myRole = data?.role || ''
    state.session.myWord = normalizeGameText(data?.word || '')
  }

  function handleRoundTransition(roundNumber) {
    const nextRound = Number(roundNumber || 0)
    if (!nextRound) return false
    if (nextRound !== state.session.currentRound) {
      state.session.currentRound = nextRound
      state.session.descriptions = []
      state.session.votableTargets = []
      state.session.myVoted = false
      return true
    }
    return false
  }

  function handleTurnLikeEvent(data, phase) {
    ensureSessionPresence({ gameId: state.session.gameId || state.roomActiveInfo?.gameId, roomId: state.session.roomId || state.roomActiveInfo?.roomId, gameStatus: 'PLAYING' })
    const roundChanged = handleRoundTransition(data?.roundNumber)
    state.session.gameStatus = 'PLAYING'
    state.session.currentPhase = phase
    state.session.currentSpeakerAccountId = data?.currentSpeakerAccountId ?? null
    state.session.currentSpeakerNickname = normalizeGameText(data?.currentSpeakerNickname || '')
    state.session.currentSpeakerOrder = data?.currentSpeakerOrder ?? null
    state.session.aiThinkingAccountId = phase === 'DESCRIBING' && data?.type === GAME_WS_TYPE.GAME_AI_THINKING
      ? data?.currentSpeakerAccountId ?? null
      : null
    startCountdown(data?.deadline, Number(data?.timeoutSeconds || 0))
    syncRoomActiveFromSession()
    if (roundChanged) {
      scheduleStateRestore(data?.gameId || state.session.gameId || state.roomActiveInfo?.gameId)
    }
  }

  function handleDescription(data) {
    if (!data?.speakerAccountId) return
    handleRoundTransition(data?.roundNumber)
    state.session.currentPhase = 'DESCRIBING'
    const description = {
      speakerAccountId: data.speakerAccountId,
      speakerNickname: normalizeGameText(data.speakerNickname || ''),
      content: normalizeGameText(data.content || ''),
      isSkipped: Boolean(data.isSkipped),
      roundNumber: Number(data.roundNumber || state.session.currentRound || 0)
    }
    const existingIndex = state.session.descriptions.findIndex(item =>
      String(item.speakerAccountId) === String(description.speakerAccountId)
    )
    if (existingIndex >= 0) {
      state.session.descriptions.splice(existingIndex, 1, description)
    } else {
      state.session.descriptions.push(description)
    }
    state.session.aiThinkingAccountId = null
  }

  function handleVotePhase(data) {
    ensureSessionPresence({ gameId: state.session.gameId || state.roomActiveInfo?.gameId, roomId: state.session.roomId || state.roomActiveInfo?.roomId, gameStatus: 'PLAYING' })
    const roundChanged = handleRoundTransition(data?.roundNumber)
    clearVoteResultDisplay()
    state.session.currentPhase = 'VOTING'
    state.session.currentSpeakerAccountId = null
    state.session.currentSpeakerNickname = ''
    state.session.currentSpeakerOrder = null
    state.session.aiThinkingAccountId = null
    state.session.votableTargets = normalizeVoteTargets(data?.votableTargets || [], getSelfAccountId())
    state.session.myVoted = false
    startCountdown(data?.deadline, Number(data?.timeoutSeconds || 0))
    if (roundChanged) {
      scheduleStateRestore(data?.gameId || state.session.gameId || state.roomActiveInfo?.gameId)
    }
  }

  function handleVoteResult(data) {
    state.session.currentPhase = 'RESULT'
    clearVoteResultDisplay()
    const normalizedVoteResult = normalizeVoteResult(data)
    const eliminatedPlayer = normalizedVoteResult.eliminatedAccountId == null
      ? null
      : state.session.players.find(player => String(player.accountId) === String(normalizedVoteResult.eliminatedAccountId))
    const resolvedEliminatedNickname = normalizedVoteResult.eliminatedNickname || eliminatedPlayer?.nickname || ''
    const resolvedEliminatedRole = normalizedVoteResult.eliminatedRole || eliminatedPlayer?.role || ''
    state.session.voteResult = {
      ...normalizedVoteResult,
      eliminatedNickname: resolvedEliminatedNickname,
      eliminatedRole: resolvedEliminatedRole
    }
    stopCountdown()

    const roundResult = {
      roundNumber: Number(normalizedVoteResult.roundNumber || state.session.currentRound || 0),
      isTie: Boolean(normalizedVoteResult.isTie),
      eliminatedAccountId: normalizedVoteResult.eliminatedAccountId ?? null,
      eliminatedNickname: resolvedEliminatedNickname,
      eliminatedRole: resolvedEliminatedRole
    }
    const existingIndex = state.session.roundResults.findIndex(item => Number(item.roundNumber) === roundResult.roundNumber)
    if (existingIndex >= 0) {
      state.session.roundResults.splice(existingIndex, 1, roundResult)
    } else {
      state.session.roundResults.push(roundResult)
      state.session.roundResults.sort((left, right) => Number(left.roundNumber) - Number(right.roundNumber))
    }

    if (normalizedVoteResult.isTie) {
      setNotice('本轮投票平票，无人淘汰', 'warning')
    } else if (resolvedEliminatedNickname) {
      setNotice(`本轮淘汰：${resolvedEliminatedNickname}`, 'info')
    }

    if (!normalizedVoteResult.isTie && normalizedVoteResult.eliminatedAccountId != null) {
      updatePlayerStatus(normalizedVoteResult.eliminatedAccountId, 'ELIMINATED', {
        role: resolvedEliminatedRole
      })
    }

    if (voteResultTimer) {
      clearTimeout(voteResultTimer)
    }
    voteResultTimer = setTimeout(() => {
      state.session.voteResult = null
      voteResultTimer = null
    }, VOTE_RESULT_DISPLAY_MS)
  }

  function handleEnded(data) {
    const matchesSession = state.session.gameId && String(state.session.gameId) === String(data?.gameId)
    const matchesRoomCard = state.roomActiveInfo?.gameId && String(state.roomActiveInfo.gameId) === String(data?.gameId)

    clearRoomActiveIfMatches(data?.gameId)
    clearPersistedSession(data?.gameId)

    if (!matchesSession) {
      if (matchesRoomCard) {
        setNotice('本房间的游戏已经结束', 'info')
      }
      return
    }

    applyEndedResult(normalizeEndedResult(data), { silent: true })
    setNotice(getEndedNotice(state.session.result), getEndedNoticeTone(state.session.result))
  }

  function handleCancelled(data) {
    const matchesSession = state.session.gameId && String(state.session.gameId) === String(data?.gameId)
    clearRoomActiveIfMatches(data?.gameId)
    if (!matchesSession) return
    resetSession()
    clearPersistedSession(data?.gameId)
    setNotice(data?.message || '游戏已取消', 'warning')
  }

  function handlePlayerPresence(data, connected) {
    if (!data?.accountId) return
    updatePlayerStatus(data.accountId, connected ? 'ALIVE' : 'DISCONNECTED')
    if (data?.message) {
      setNotice(data.message, connected ? 'success' : 'warning')
    }
  }

  function handleWsEvent(type, data, roomId) {
    switch (type) {
      case GAME_WS_TYPE.GAME_CREATED: {
        if (roomId === state.currentRoomId) {
          updateRoomActiveInfo(data)
        }
        if (data?.players && isPlayerInList(data.players)) {
          patchSessionFromActiveInfo(data)
        }
        break
      }
      case GAME_WS_TYPE.GAME_PLAYER_JOINED:
        handlePlayerJoined(data, roomId)
        break
      case GAME_WS_TYPE.GAME_PLAYER_LEFT:
        handlePlayerLeft(data)
        break
      case GAME_WS_TYPE.GAME_STARTED:
        handleGameStarted(data, roomId)
        break
      case GAME_WS_TYPE.GAME_ROLE_ASSIGNED:
        handleRoleAssigned(data)
        break
      case GAME_WS_TYPE.GAME_TURN_START:
        handleTurnLikeEvent(data, 'DESCRIBING')
        break
      case GAME_WS_TYPE.GAME_AI_THINKING:
        handleTurnLikeEvent({ ...data, type }, 'DESCRIBING')
        state.session.aiThinkingAccountId = data?.currentSpeakerAccountId ?? null
        break
      case GAME_WS_TYPE.GAME_DESCRIPTION:
        handleDescription(data)
        break
      case GAME_WS_TYPE.GAME_VOTE_PHASE:
        handleVotePhase(data)
        break
      case GAME_WS_TYPE.GAME_VOTE_RESULT:
        handleVoteResult(data)
        break
      case GAME_WS_TYPE.GAME_ENDED:
        handleEnded(data)
        break
      case GAME_WS_TYPE.GAME_CANCELLED:
        handleCancelled(data)
        break
      case GAME_WS_TYPE.GAME_PLAYER_DISCONNECTED:
        handlePlayerPresence(data, false)
        break
      case GAME_WS_TYPE.GAME_PLAYER_RECONNECTED:
        handlePlayerPresence(data, true)
        break
      default:
        break
    }
  }

  return {
    state,
    enterRoom,
    refreshActiveGame,
    handleSocketConnected,
    handleWsEvent,
    createGame,
    joinGame,
    leaveGame,
    cancelGame,
    addAiPlayer,
    startGame,
    submitDescription,
    submitVote,
    loadHistory,
    dismissEndedState,
    toggleCollapsed,
    dismissRoomActivePrompt
  }
}
