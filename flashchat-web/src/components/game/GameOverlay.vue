<template>
  <div v-if="visible" class="game-layer">
    <!-- Notice toast -->
    <div v-if="store.notice" class="g-notice" :class="`is-${store.noticeTone}`">{{ store.notice }}</div>

    <!-- ═══ Dock — collapsed mini bar ═══ -->
    <div v-if="hasSession && store.collapsed" class="g-dock">
      <span class="g-dock-dot" :class="stageToneClass"></span>
      <span class="g-dock-label">{{ phaseTitle }}</span>
      <span class="g-dock-meta">第 {{ session.currentRound || 1 }} 轮</span>
      <span v-if="session.timerRemaining > 0" class="g-dock-timer">{{ session.timerRemaining }}s</span>
      <button v-if="needsRoomSwitch" class="g-btn ghost sm" type="button" @click="switchToRoom">返回</button>
      <button class="g-btn sm" type="button" @click="game.toggleCollapsed()">展开</button>
    </div>

    <!-- ═══ Shell — full game overlay ═══ -->
    <div v-else-if="hasSession" class="g-shell">
      <!-- Header -->
      <header class="g-header">
        <div class="g-header-left">
          <h2 class="g-title">谁是卧底</h2>
          <div class="g-phase-pill" :class="stageToneClass">
            <span class="g-phase-dot"></span>
            {{ phaseTitle }} · 第 {{ session.currentRound || 1 }} 轮
          </div>
        </div>
        <div class="g-header-actions">
          <button v-if="needsRoomSwitch" class="g-btn" type="button" @click="switchToRoom">回到房间</button>
          <button class="g-btn" type="button" @click="game.toggleCollapsed()">折叠</button>
        </div>
      </header>

      <!-- Steps indicator -->
      <div class="g-steps">
        <div v-for="(step, idx) in stageFlow" :key="step.key" class="g-step" :class="step.state">
          <span class="g-step-num">{{ idx + 1 }}</span>
          <span class="g-step-label">{{ step.label }}</span>
        </div>
      </div>

      <!-- ═══ RESULT ═══ -->
      <section v-if="session.result" class="g-result">
        <div class="g-result-badge">Game Over</div>
        <h3 class="g-result-title">{{ winnerLabel }}</h3>
        <p class="g-result-desc">{{ endReasonLabel }}</p>

        <div class="g-result-words">
          <div class="g-result-word">
            <div class="g-result-word-label">平民词</div>
            <div class="g-result-word-text">{{ session.result.civilianWord || '未公开' }}</div>
          </div>
          <div class="g-result-word spy">
            <div class="g-result-word-label">卧底词</div>
            <div class="g-result-word-text">{{ session.result.spyWord || '未公开' }}</div>
          </div>
        </div>

        <div class="g-result-players">
          <div v-for="player in resultPlayers" :key="player.accountId" class="g-result-player" :class="{ spy: player.role === 'SPY', eliminated: player.status === 'ELIMINATED' }">
            <img class="g-result-avatar" :src="avatarOf(player)" :alt="player.nickname" />
            <div class="g-result-info">
              <strong>{{ player.nickname }}</strong>
              <span>{{ roleLabel(player.role) }} · {{ statusLabel(player.status) }}</span>
            </div>
            <em class="g-result-word-sm">{{ player.word || '' }}</em>
          </div>
        </div>

        <!-- Replay -->
        <div v-if="historyOpen" class="g-history">
          <div v-for="round in historyRounds" :key="round.roundNumber" class="g-history-round">
            <strong>第 {{ round.roundNumber }} 轮</strong>
            <span>{{ round.isTie ? '平票' : round.eliminatedNickname ? `淘汰 ${round.eliminatedNickname}` : '无淘汰' }}</span>
            <ul>
              <li v-for="item in round.descriptions || []" :key="`${round.roundNumber}-${item.speakerAccountId}`">
                {{ item.speakerNickname }}: {{ item.isSkipped ? '跳过发言' : item.content }}
              </li>
            </ul>
          </div>
          <div v-if="!historyRounds.length" class="g-empty">暂无回放数据</div>
        </div>

        <div class="g-result-actions">
          <button class="g-btn accent" type="button" :disabled="store.historyLoading" @click="toggleHistory">
            {{ historyOpen ? '收起回放' : store.historyLoading ? '加载中...' : '查看回放' }}
          </button>
          <button class="g-btn" type="button" @click="dismissEnded">返回聊天</button>
        </div>
      </section>

      <!-- ═══ WAITING LOBBY ═══ -->
      <section v-else-if="session.gameStatus === 'WAITING'" class="g-lobby">
        <h3 class="g-lobby-title">等待玩家加入</h3>
        <p class="g-lobby-desc">当前 {{ players.length }} / {{ session.config.maxPlayers }} 人，至少 {{ session.config.minPlayers }} 人开局</p>

        <!-- Player avatars -->
        <div class="g-lobby-avatars">
          <div v-for="player in players" :key="player.accountId" class="g-lobby-avatar">
            <img :src="avatarOf(player)" :alt="player.nickname" />
          </div>
          <div v-for="n in Math.max(0, (session.config.minPlayers || 4) - players.length)" :key="`empty-${n}`" class="g-lobby-avatar empty">+</div>
        </div>

        <!-- Progress -->
        <div class="g-lobby-meter">
          <div class="g-lobby-meter-track">
            <span :style="{ width: `${lobbyReadyProgress}%` }"></span>
          </div>
          <div class="g-lobby-meter-info">
            <span>{{ players.length }} / {{ session.config.minPlayers || 4 }} 人</span>
            <span>{{ lobbyProgressLabel }}</span>
          </div>
        </div>

        <!-- AI Personas -->
        <div v-if="isCreator" class="g-lobby-personas">
          <button v-for="persona in personas" :key="persona.code" class="g-persona" type="button" :disabled="store.actionPending || players.length >= session.config.maxPlayers" @click="game.addAiPlayer(persona.code)">
            <em>{{ personaEmoji(persona.code) }}</em>
            <strong>{{ persona.label }}</strong>
            <span>{{ persona.desc }}</span>
          </button>
        </div>

        <!-- Actions -->
        <div class="g-lobby-actions">
          <button v-if="isCreator" class="g-btn accent" type="button" :disabled="!canStart || store.actionPending" @click="game.startGame()">
            {{ canStart ? '开始游戏' : `至少 ${session.config.minPlayers} 人` }}
          </button>
          <button v-if="isCreator" class="g-btn" type="button" :disabled="store.actionPending" @click="game.cancelGame()">取消游戏</button>
          <button v-else class="g-btn" type="button" :disabled="store.actionPending" @click="game.leaveGame()">退出游戏</button>
        </div>
      </section>

      <!-- ═══ PLAYING ═══ -->
      <section v-else class="g-playing">
        <div class="g-play-grid">
          <!-- Left column: Word + Ring -->
          <div class="g-play-left">
            <!-- Word card — NO ROLE REVEALED -->
            <div class="g-word-card">
              <div class="g-word-kicker">你的词语</div>
              <div class="g-word-text">{{ session.myWord || '等待分配' }}</div>
              <div class="g-word-hint">描述这个词，但不要直接说出来</div>
              <div v-if="session.timerRemaining > 0 || session.currentSpeakerNickname" class="g-word-status">
                <span class="g-word-dot" :class="stageToneClass"></span>
                <span v-if="session.timerRemaining > 0">{{ session.timerRemaining }}s</span>
                <span v-if="session.currentSpeakerNickname">{{ isMyTurn ? '轮到你发言' : `${session.currentSpeakerNickname} 发言中` }}</span>
              </div>
            </div>

            <!-- Player ring -->
            <div class="g-ring">
              <div class="g-ring-center">
                <strong>{{ phaseTitle }}</strong>
                <span>第 {{ session.currentRound || 1 }} 轮</span>
                <span v-if="session.timerRemaining > 0" class="g-ring-timer">{{ session.timerRemaining }}s</span>
              </div>
              <div class="g-ring-orbit" :class="arenaPhaseClass">
                <div v-for="(player, index) in players" :key="player.accountId" class="g-seat" :class="seatClass(player)" :style="seatStyle(index, players.length)">
                  <div class="g-seat-inner">
                    <div class="g-seat-avatar-wrap">
                      <img class="g-seat-avatar" :src="avatarOf(player)" :alt="player.nickname" />
                      <span v-if="String(player.accountId) === String(props.memberId ?? '')" class="g-seat-badge you">你</span>
                      <span v-else-if="isLatestEliminated(player)" class="g-seat-badge out">淘汰</span>
                      <span v-else-if="isEliminated(player)" class="g-seat-badge out">淘汰</span>
                      <div v-if="session.aiThinkingAccountId && String(session.aiThinkingAccountId) === String(player.accountId)" class="g-seat-thinking">
                        <span></span><span></span><span></span>
                      </div>
                    </div>
                    <div class="g-seat-name">{{ player.nickname }}</div>
                  </div>
                </div>
              </div>
            </div>

            <!-- Round results (compact) -->
            <div v-if="roundResults.length" class="g-rounds">
              <div v-for="item in roundResults" :key="`rl-${item.roundNumber}`" class="g-round-item" :class="{ tie: item.isTie }">
                <strong>第 {{ item.roundNumber }} 轮</strong>
                <span>{{ item.isTie ? '平票' : `淘汰 ${eliminatedNicknameOf(item) || '未知'}` }}</span>
              </div>
            </div>
          </div>

          <!-- Right column: Feed + Actions -->
          <div class="g-play-right">
            <!-- Vote result banner -->
            <div v-if="session.voteResult" class="g-vote-result">
              <strong>{{ session.voteResult.isTie ? '本轮平票，无人淘汰' : `${voteEliminatedNickname} 出局` }}</strong>
              <div v-if="voteEntries.length" class="g-vote-breakdown">
                <span v-for="entry in voteEntries" :key="entry.targetAccountId">{{ entry.nickname }} {{ entry.count }}票</span>
              </div>
            </div>

            <!-- Vote stage -->
            <div v-if="session.currentPhase === 'VOTING' && !session.myVoted" class="g-vote-section">
              <div class="g-vote-head">
                <h4>选择你怀疑的对象</h4>
                <span class="g-vote-count">{{ voteTargets.length }} 名候选</span>
              </div>
              <div class="g-vote-grid">
                <button
                  v-for="target in voteTargets"
                  :key="`vote-${target.accountId}`"
                  class="g-vote-card"
                  :class="{ selected: selectedVoteTarget === String(target.accountId) }"
                  type="button"
                  :disabled="store.actionPending"
                  @click="selectedVoteTarget = String(target.accountId)"
                >
                  <div class="g-vote-card-head">
                    <img class="g-vote-avatar" :src="avatarOf(voteTargetPlayer(target))" :alt="target.nickname" />
                    <div>
                      <strong>{{ target.nickname }}</strong>
                      <span>{{ voteTargetLabel(target) }}</span>
                    </div>
                  </div>
                  <p class="g-vote-quote">{{ speechSummary(target.accountId) }}</p>
                </button>
              </div>
              <div class="g-vote-submit">
                <button class="g-btn accent" type="button" :disabled="!selectedVoteTarget || store.actionPending" @click="game.submitVote(selectedVoteTarget)">确认投票</button>
              </div>
            </div>
            <div v-else-if="session.currentPhase === 'VOTING' && session.myVoted" class="g-vote-waited">
              <span class="g-vote-waited-icon">✓</span>
              投票已提交，等待其他玩家
            </div>

            <!-- Feed -->
            <div class="g-feed">
              <div class="g-feed-head">
                <h4>发言记录</h4>
                <span class="g-feed-count">{{ session.descriptions.length }} 条</span>
              </div>
              <div class="g-feed-body">
                <div v-for="item in session.descriptions" :key="`${session.currentRound}-${item.speakerAccountId}`" class="g-msg">
                  <img class="g-msg-avatar" :src="avatarOf(findPlayer(item.speakerAccountId) || {})" :alt="item.speakerNickname" />
                  <div class="g-msg-body">
                    <strong>{{ item.speakerNickname }}</strong>
                    <span>{{ item.isSkipped ? '跳过发言' : item.content }}</span>
                  </div>
                </div>
                <div v-if="!session.descriptions.length" class="g-empty">本轮还没有公开发言</div>
              </div>

              <!-- Input area -->
              <div v-if="isMyTurn" class="g-action-bar">
                <textarea v-model="descriptionDraft" class="g-action-input" maxlength="200" placeholder="描述你的词语，但不要直接说出来..." rows="1"></textarea>
                <button class="g-btn accent" type="button" :disabled="!descriptionDraft.trim() || store.actionPending" @click="submitDescription">发言</button>
              </div>
              <div v-else class="g-action-bar passive">
                <span>{{ phaseCopy }}</span>
              </div>
            </div>
          </div>
        </div>
      </section>
    </div>

    <!-- ═══ Sidecard — room has active game ═══ -->
    <div v-else-if="roomActiveVisible" class="g-sidecard">
      <div class="g-sidecard-body">
        <div class="g-sidecard-copy">
          <strong>{{ roomActive.gameStatus === 'PLAYING' ? '本房间有一局游戏正在进行' : '本房间有人创建了游戏' }}</strong>
          <span>{{ roomActive.playerCount }} / {{ roomActive.maxPlayers }} 人 · {{ roomActive.gameStatus === 'PLAYING' ? '进行中' : '等待中' }}</span>
        </div>
        <div class="g-sidecard-actions">
          <button class="g-btn sm" type="button" @click="game.dismissRoomActivePrompt(roomActive.gameId)">忽略</button>
          <button class="g-btn accent sm" type="button" :disabled="!canJoin || store.actionPending" @click="game.joinGame(roomActive.gameId)">
            {{ canJoin ? '加入' : '已满' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { resolveBackendAvatar } from '@/utils/avatar'

const props = defineProps({
  game: { type: Object, required: true },
  currentRoomId: { type: String, default: '' },
  memberId: { type: [String, Number], default: null }
})

const emit = defineEmits(['switch-room'])
const game = props.game
const currentRoomId = computed(() => props.currentRoomId)

const historyOpen = ref(false)
const descriptionDraft = ref('')
const selectedVoteTarget = ref('')
const roomActiveExpanded = ref(false)
const personas = [
  { code: 'CAUTIOUS', label: '谨慎型', desc: '偏稳妥的 AI', emoji: '🐢' },
  { code: 'AGGRESSIVE', label: '激进型', desc: '敢下判断的 AI', emoji: '⚡' },
  { code: 'MASTER', label: '老手型', desc: '擅长伪装的 AI', emoji: '🎭' }
]

const store = computed(() => props.game.state)
const session = computed(() => store.value.session || {})
const roomActive = computed(() => store.value.roomActiveInfo || null)
const players = computed(() => session.value.players || [])
const hasSession = computed(() => Boolean(session.value.gameId) || Boolean(session.value.result))
const roomActiveVisible = computed(() =>
  Boolean(roomActive.value) &&
  String(roomActive.value?.gameId ?? '') !== String(store.value.ignoredActiveGameId ?? '')
)
const visible = computed(() => hasSession.value || roomActiveVisible.value)
const isCreator = computed(() => String(session.value.creatorId ?? roomActive.value?.creatorId ?? '') === String(props.memberId ?? ''))
const canStart = computed(() => session.value.gameStatus === 'WAITING' && isCreator.value && players.value.length >= (session.value.config?.minPlayers || 4))
const isMyTurn = computed(() => session.value.currentPhase === 'DESCRIBING' && String(session.value.currentSpeakerAccountId ?? '') === String(props.memberId ?? ''))
const needsRoomSwitch = computed(() => Boolean(session.value.roomId) && Boolean(props.currentRoomId) && String(session.value.roomId) !== String(props.currentRoomId))
const voteTargets = computed(() => session.value.votableTargets || [])
const roundResults = computed(() => [...(session.value.roundResults || [])].sort((left, right) => Number(right.roundNumber) - Number(left.roundNumber)))
const latestRoundResult = computed(() => roundResults.value[0] || null)
const voteEliminatedPlayer = computed(() => {
  const voteResult = session.value.voteResult
  if (!voteResult || voteResult.isTie) return null
  if (voteResult.eliminatedAccountId != null) {
    const byId = findPlayer(voteResult.eliminatedAccountId)
    if (byId) return byId
  }
  if (voteResult.eliminatedNickname) {
    return players.value.find(player => player.nickname === voteResult.eliminatedNickname) || null
  }
  return null
})
const voteEliminatedNickname = computed(() =>
  session.value.voteResult?.eliminatedNickname || voteEliminatedPlayer.value?.nickname || '未知玩家'
)
const historyRounds = computed(() => session.value.history?.rounds || [])
const resultPlayers = computed(() => session.value.result?.playerRoles || players.value)
const selectedVotePlayer = computed(() => selectedVoteTarget.value ? findPlayer(selectedVoteTarget.value) : null)
const canJoin = computed(() => Boolean(roomActive.value) && roomActive.value.gameStatus === 'WAITING' && roomActive.value.playerCount < roomActive.value.maxPlayers)
const alivePlayersCount = computed(() => players.value.filter(player => String(player.status || '') === 'ALIVE').length)
const aiPlayersCount = computed(() => players.value.filter(player => player.playerType === 'AI').length)
const humanPlayersCount = computed(() => players.value.filter(player => player.playerType !== 'AI').length)
const lobbyReadyGap = computed(() => Math.max((session.value.config?.minPlayers || 4) - players.value.length, 0))
const lobbyReadyProgress = computed(() => {
  const minimum = Math.max(session.value.config?.minPlayers || 4, 1)
  return Math.min(100, Math.round((players.value.length / minimum) * 100))
})
const roomActiveProgress = computed(() => {
  const maxPlayers = Math.max(roomActive.value?.maxPlayers || 1, 1)
  return Math.min(100, Math.round(((roomActive.value?.playerCount || 0) / maxPlayers) * 100))
})
const phaseTitle = computed(() => {
  if (session.value.result) return '结算阶段'
  if (session.value.voteResult) return '投票结果展示'
  if (session.value.gameStatus === 'WAITING') return '等待阶段'
  if (session.value.currentPhase === 'VOTING') return '投票阶段'
  if (session.value.currentPhase === 'DESCRIBING') return '发言阶段'
  return '游戏进行中'
})
const sessionHeading = computed(() => session.value.result ? '本局已经结束' : session.value.gameStatus === 'WAITING' ? '等待玩家加入' : '谁是卧底')
const winnerLabel = computed(() => session.value.result?.winnerSide === 'SPY' ? '卧底阵营获胜' : session.value.result?.winnerSide === 'CIVILIAN' ? '平民阵营获胜' : '本局结束')
const endReasonLabel = computed(() => {
  const reason = session.value.result?.endReason
  if (reason === 'ALL_DISCONNECTED') return '所有真人玩家离线，系统终止了本局。'
  if (reason === 'CANCELLED') return '房主在等待阶段取消了这局游戏。'
  return '可以查看角色公开和逐轮回放。'
})
const phaseCopy = computed(() => {
  if (session.value.currentPhase === 'VOTING') return session.value.myVoted ? '你的投票已提交，等待其他玩家。' : '选择一个你怀疑的目标，提交后不可更改。'
  if (session.value.currentPhase === 'DESCRIBING') return session.value.currentSpeakerNickname ? `等待 ${session.value.currentSpeakerNickname} 发言。` : '等待服务端推进到下一位发言者。'
  return '服务端会继续推进轮次，前端只负责展示状态。'
})
const actionPanelTitle = computed(() => {
  if (session.value.voteResult) return '投票结果'
  if (session.value.currentPhase === 'VOTING') return '阶段提示'
  if (isMyTurn.value) return '发言区'
  return '阶段提示'
})
const actionPanelCopy = computed(() => {
  if (session.value.voteResult) {
    return session.value.voteResult.isTie
      ? '本轮没有玩家出局，稍后继续下一阶段。'
      : `${voteEliminatedNickname.value} 已离场，系统会继续推进流程。`
  }
  if (session.value.currentPhase === 'VOTING') {
    return '投票界面已经在中间舞台展开，右侧现在专心负责展示消息。'
  }
  if (isMyTurn.value) {
    return '先看上面的公开发言，再在这里输入一句描述。'
  }
  return phaseCopy.value
})
const voteEntries = computed(() => (session.value.voteResult?.voteDetails || []).map(entry => {
  const target = findPlayer(entry.targetAccountId)
  return {
    targetAccountId: entry.targetAccountId,
    nickname: target?.nickname || `玩家 ${entry.targetAccountId}`,
    count: entry.votes.length,
    voters: entry.votes.map(vote => `${findPlayer(vote.voterAccountId)?.nickname || vote.voterAccountId}${vote.isAuto ? '·托管' : ''}`).join('、')
  }
}).sort((a, b) => b.count - a.count))
const latestRoundSummary = computed(() => {
  if (!latestRoundResult.value) return ''
  return latestRoundResult.value.isTie
    ? `第 ${latestRoundResult.value.roundNumber} 轮平票`
    : `第 ${latestRoundResult.value.roundNumber} 轮淘汰 ${eliminatedNicknameOf(latestRoundResult.value) || '未知玩家'}`
})
const latestRoundHeadline = computed(() => {
  if (!latestRoundResult.value) return '暂无淘汰记录'
  return latestRoundResult.value.isTie
    ? `第 ${latestRoundResult.value.roundNumber} 轮平票`
    : `第 ${latestRoundResult.value.roundNumber} 轮淘汰 ${eliminatedNicknameOf(latestRoundResult.value) || '未知玩家'}`
})
const myRoleTone = computed(() => session.value.myRole === 'SPY' ? 'spy' : session.value.myRole === 'CIVILIAN' ? 'civilian' : 'quiet')
const myRoleLabel = computed(() => {
  if (session.value.myRole === 'SPY') return '卧底视角'
  if (session.value.myRole === 'CIVILIAN') return '平民视角'
  return '身份保密'
})
const privateBriefCopy = computed(() => {
  if (session.value.myRole === 'SPY') return '你的目标不是说出这个词，而是让描述看起来像真的知道它。'
  if (session.value.myRole === 'CIVILIAN') return '不要直接说词本身，用语境、用途和气味把队友带过去。'
  return '身份和词语都还在发牌中，先观察整桌气氛。'
})
const privateBriefTags = computed(() => {
  const tags = []
  if (session.value.myRole === 'SPY') tags.push('伪装比准确更重要')
  if (session.value.myRole === 'CIVILIAN') tags.push('描述要像真线索')
  if (session.value.myWord) tags.push(`${String(session.value.myWord).length} 字词语`)
  if (session.value.currentPhase === 'VOTING') tags.push('发言已结束')
  if (session.value.currentPhase === 'DESCRIBING') tags.push('保持含蓄')
  return tags
})
const voteSelectionCopy = computed(() => {
  if (session.value.myVoted) return '你的投票已提交，等待其他玩家。'
  if (!selectedVoteTarget.value) return '选择一位你怀疑的玩家。'
  return `已选择 ${findPlayer(selectedVoteTarget.value)?.nickname || '目标玩家'}`
})
const stageToneClass = computed(() => {
  if (session.value.voteResult) return 'result'
  if (session.value.currentPhase === 'VOTING') return 'alert'
  if (session.value.currentPhase === 'DESCRIBING') return 'live'
  return 'quiet'
})
const arenaPhaseClass = computed(() => {
  if (session.value.voteResult) return 'phase-result'
  if (session.value.currentPhase === 'VOTING') return 'phase-voting'
  if (session.value.currentPhase === 'DESCRIBING') return 'phase-describing'
  return 'phase-idle'
})
const stageNarrative = computed(() => {
  if (session.value.voteResult) return '判定结果已生成，舞台进入短暂停顿。'
  if (session.value.currentPhase === 'VOTING') return '全员进入表决时刻，怀疑会在这一拍落下。'
  if (session.value.currentPhase === 'DESCRIBING') return '线索正在桌面上传递，每一句描述都在改变风向。'
  return '房间正在等待下一次阶段推进。'
})
const stageSpotlight = computed(() => {
  if (session.value.voteResult) {
    return session.value.voteResult.isTie ? '这一轮无人离场' : `${voteEliminatedNickname.value} 离场`
  }
  if (session.value.currentPhase === 'VOTING') {
    return session.value.myVoted ? '你的票已经落下' : '请做出你的判断'
  }
  if (session.value.currentPhase === 'DESCRIBING') {
    return session.value.currentSpeakerNickname ? `${session.value.currentSpeakerNickname} 正在发言` : '等待下一位发言'
  }
  return '保持观察'
})
const headerNarrative = computed(() => {
  if (session.value.result) return '这一局已经收牌，舞台现在交给复盘与揭晓。'
  if (session.value.gameStatus === 'WAITING') return '让房间先热起来，再把第一轮的气氛拉满。'
  if (session.value.currentPhase === 'VOTING') return '所有线索都会在这一拍收束，判断要稳，动作要快。'
  if (session.value.currentPhase === 'DESCRIBING') return '这不是抢答局，而是一场靠气味和语境互相试探的对话。'
  return '桌面正在推进，观察、节奏和表情同样重要。'
})
const stageFlow = computed(() => {
  const currentIndex = session.value.result
    ? 3
    : session.value.gameStatus === 'WAITING'
      ? 0
      : session.value.currentPhase === 'VOTING'
        ? 2
        : 1

  const steps = [
    { key: 'lobby', short: '01', label: '集结', desc: '等人到齐' },
    { key: 'describe', short: '02', label: '发言', desc: '线索铺开' },
    { key: 'vote', short: '03', label: '投票', desc: '判断落子' },
    { key: 'reveal', short: '04', label: '揭晓', desc: '结果公开' }
  ]

  return steps.map((step, index) => ({
    ...step,
    state: index < currentIndex ? 'done' : index === currentIndex ? 'active' : 'idle'
  }))
})
const liveFacts = computed(() => [
  { label: '存活', value: `${alivePlayersCount.value}/${players.value.length || 0}` },
  { label: '真人', value: `${humanPlayersCount.value}` },
  { label: 'AI', value: `${aiPlayersCount.value}` }
])
const lobbyProgressLabel = computed(() => {
  if (canStart.value) return '已经达到开局门槛'
  return lobbyReadyGap.value > 0 ? `还差 ${lobbyReadyGap.value} 人就能开局` : '等待房主启动这一局'
})
const lobbyFacts = computed(() => [
  { label: '真人玩家', value: `${humanPlayersCount.value}` },
  { label: 'AI 队友', value: `${aiPlayersCount.value}` },
  { label: '上限座位', value: `${session.value.config?.maxPlayers || 10}` }
])
const lobbyNotes = computed(() => {
  const notes = [
    '前两轮先建立气味，不要急着把词讲得太实。',
    '看谁在抢定义，谁在绕定义，这通常比句子本身更有信息。',
    '人不够时别着急，等桌风成型再开局，体验会更好。'
  ]
  if (isCreator.value) notes.unshift('你可以先用 AI 把节奏垫起来，再等真人补位。')
  return notes
})
const waitingAudienceCopy = computed(() =>
  canStart.value ? '人数已经够了，等房主拍板后就会立刻进入发言阶段。' : '先观察谁在控场、谁在热身，真正好玩的局往往从等待区就开始了。'
)
const spotlightTimerLabel = computed(() =>
  session.value.timerRemaining > 0 ? `${session.value.timerRemaining}s` : '等待推进'
)
const spotlightSupportLabel = computed(() => {
  if (session.value.voteResult) return session.value.voteResult.isTie ? '本轮平票' : '结果已出'
  if (session.value.currentPhase === 'VOTING') return session.value.myVoted ? '你的票已交' : '轮到你判断'
  if (session.value.currentPhase === 'DESCRIBING') return isMyTurn.value ? '轮到你发言' : '请观察别人'
  return '下一拍将至'
})
const arenaHint = computed(() => {
  if (session.value.voteResult) return '先读结果，再回看这一轮谁在刻意带方向。'
  if (session.value.currentPhase === 'VOTING') return selectedVotePlayer.value ? `把票交给 ${selectedVotePlayer.value.nickname} 前，再确认一次他的发言是否自洽。` : '不要只看一句话，连上前后节奏再做判断。'
  if (isMyTurn.value) return '说得像在描述，但不要像在定义。最好的线索永远留一点空白。'
  return session.value.currentSpeakerNickname ? `现在重点听 ${session.value.currentSpeakerNickname} 的措辞和停顿。` : '这一拍先别抢结论，观察桌面怎么走。'
})
const descriptionHints = computed(() => [
  '别直接说词',
  '优先说感受或用途',
  '一句话就够'
])
const descriptionTips = computed(() => [
  '用场景、气味、质地、关系去靠近这个词，而不是定义它。',
  '如果你是卧底，重点是“像知道”，不是“讲得全对”。',
  '保持一句话的力度，给下一位留出可接的空间。'
])
const descriptionPlaceholder = computed(() => '描述你的词语，但不要直接说出来...')
const phaseTips = computed(() => {
  if (session.value.voteResult) {
    return [
      '结果已经出来了，留意这一轮是谁在推动共识、谁在临时改票。',
      '短暂停顿的阶段很适合快速复盘刚才的语气和站位。'
    ]
  }
  if (session.value.currentPhase === 'VOTING') {
    return [
      '先对比整轮节奏，再看单句内容，很多破绽出在“急”上。',
      '你的票一旦提交就不能修改，最后一眼优先看不自然的解释欲。'
    ]
  }
  if (isMyTurn.value) {
    return descriptionTips.value
  }
  return [
    '现在最有价值的不是说话，而是记住每个人的表达角度。',
    '谁在模糊，谁在下定义，谁在跟风，这些都会在投票时回头找你。'
  ]
})
const roomActiveProgressLabel = computed(() => {
  if (!roomActive.value) return ''
  if (roomActive.value.gameStatus === 'PLAYING') return '这一桌已经开演'
  return roomActive.value.playerCount >= roomActive.value.minPlayers ? '人数够了，可以随时开局' : `还差 ${Math.max((roomActive.value.minPlayers || 4) - roomActive.value.playerCount, 0)} 人开局`
})
const roomActiveTips = computed(() => [
  '如果你刚进房间，这是一张正在热起来的桌子。',
  '加入前先看人数和配置，合适的桌风比盲进更重要。'
])
const statusRail = computed(() => {
  const items = [
    {
      label: '阶段',
      value: phaseTitle.value,
      tone: session.value.currentPhase === 'VOTING' ? 'alert' : session.value.voteResult ? 'result' : 'live'
    },
    {
      label: '轮次',
      value: `第 ${session.value.currentRound || 1} 轮`,
      tone: 'quiet'
    }
  ]

  if (session.value.currentPhase === 'DESCRIBING') {
    items.push({
      label: '当前',
      value: session.value.currentSpeakerNickname ? `${session.value.currentSpeakerNickname} 发言中` : '等待下一位',
      tone: 'live'
    })
  } else if (session.value.currentPhase === 'VOTING') {
    items.push({
      label: '投票',
      value: session.value.myVoted ? '你的票已提交' : '等待你的选择',
      tone: session.value.myVoted ? 'quiet' : 'alert'
    })
  } else if (session.value.voteResult) {
    items.push({
      label: '结果',
      value: session.value.voteResult.isTie ? '本轮平票' : `${voteEliminatedNickname.value} 离场`,
      tone: 'result'
    })
  }

  if (session.value.timerRemaining > 0) {
    items.push({
      label: '倒计时',
      value: `${session.value.timerRemaining}s`,
      tone: 'clock'
    })
  }

  items.push({
    label: '上轮',
    value: latestRoundHeadline.value,
    tone: latestRoundResult.value?.isTie ? 'quiet' : 'result'
  })

  if (needsRoomSwitch.value) {
    items.push({
      label: '位置',
      value: '当前不在游戏房间',
      tone: 'alert'
    })
  }

  return items.slice(0, 5)
})

watch(() => session.value.currentPhase, phase => {
  if (phase !== 'VOTING') selectedVoteTarget.value = ''
})
watch(() => roomActive.value?.gameId, () => {
  roomActiveExpanded.value = false
})


function avatarOf(player) {
  return resolveBackendAvatar(player?.avatar, player?.nickname || '?')
}

function voteTargetPlayer(target) {
  return findPlayer(target?.accountId) || target || {}
}

function voteTargetLabel(target) {
  const player = voteTargetPlayer(target)
  if (player?.playerOrder) return `#${player.playerOrder}`
  return statusLabel(target?.status)
}

function roleLabel(role) {
  return role === 'SPY' ? '卧底' : role === 'CIVILIAN' ? '平民' : '未公开'
}

function statusLabel(status) {
  return status === 'DISCONNECTED' ? '掉线' : status === 'ELIMINATED' ? '淘汰' : '在线'
}

function isEliminated(player) {
  return String(player?.status || '') === 'ELIMINATED'
}

function isLatestEliminated(player) {
  if (!latestRoundResult.value || latestRoundResult.value.isTie) return false
  if (latestRoundResult.value.eliminatedAccountId != null) {
    return String(player?.accountId ?? '') === String(latestRoundResult.value.eliminatedAccountId)
  }
  return Boolean(eliminatedNicknameOf(latestRoundResult.value)) &&
    String(player?.nickname ?? '') === String(eliminatedNicknameOf(latestRoundResult.value))
}

function eliminatedNicknameOf(result) {
  if (!result) return ''
  if (result.eliminatedNickname) return result.eliminatedNickname
  if (result.eliminatedAccountId != null) {
    return findPlayer(result.eliminatedAccountId)?.nickname || ''
  }
  return ''
}

function seatCaption(player) {
  if (isLatestEliminated(player)) return '刚刚离场'
  if (isEliminated(player)) return '已被淘汰'
  if (String(player?.accountId ?? '') === String(session.value.currentSpeakerAccountId ?? '')) return '正在发言'
  if (String(player?.accountId ?? '') === String(props.memberId ?? '')) return '你的棋子'
  if (String(player?.status || '') === 'DISCONNECTED') return '暂时离线'
  return player?.playerType === 'AI' ? 'AI 棋子' : '保持观察'
}

function personaEmoji(code) {
  const p = personas.find(x => x.code === code)
  return p?.emoji || '🤖'
}
function personaGlyph(code) {
  if (code === 'CAUTIOUS') return '01'
  if (code === 'AGGRESSIVE') return '02'
  if (code === 'MASTER') return '03'
  return 'AI'
}

function configTags(config = {}) {
  return [`${config.minPlayers || 4}-${config.maxPlayers || 10} 人`, `发言 ${config.describeTimeout || 60}s`, `投票 ${config.voteTimeout || 30}s`, `AI 上限 ${config.maxAiPlayers ?? 4}`]
}

function seatStyle(index, total) {
  const count = Math.max(total || 1, 1)
  const angle = -90 + (360 / count) * index
  const radius = count <= 5 ? 154 : count <= 8 ? 184 : 210
  return {
    '--seat-angle': `${angle}deg`,
    '--seat-radius': `${radius}px`,
    '--seat-index': index
  }
}

function seatClass(player) {
  return {
    active: String(player.accountId) === String(session.value.currentSpeakerAccountId ?? ''),
    self: String(player.accountId) === String(props.memberId ?? ''),
    muted: player.status !== 'ALIVE',
    eliminated: isEliminated(player),
    latest: isLatestEliminated(player),
    thinking: session.value.aiThinkingAccountId && String(session.value.aiThinkingAccountId) === String(player.accountId)
  }
}

function findPlayer(accountId) {
  return [...players.value, ...(roomActive.value?.players || [])].find(player => String(player.accountId) === String(accountId))
}

function speechSummary(accountId) {
  const item = (session.value.descriptions || []).find(description => String(description.speakerAccountId) === String(accountId))
  if (!item) return '本轮还没有发言'
  return item.isSkipped ? '本轮跳过了发言' : item.content
}

async function submitDescription() {
  const success = await props.game.submitDescription(descriptionDraft.value)
  if (success) descriptionDraft.value = ''
}

async function toggleHistory() {
  if (!historyOpen.value && !session.value.history) {
    const history = await props.game.loadHistory()
    if (!history) return
  }
  historyOpen.value = !historyOpen.value
}

function dismissEnded() {
  historyOpen.value = false
  props.game.dismissEndedState()
}

function switchToRoom() {
  if (session.value.roomId) emit('switch-room', session.value.roomId)
}
</script>


<style scoped>
/* ═══ Layer ═══ */
.game-layer {
  position: absolute;
  inset: 12px;
  z-index: 100;
  pointer-events: none;
}
.game-layer > * { pointer-events: auto; }

/* ═══ Dock — collapsed mini bar ═══ */
.g-dock {
  position: absolute;
  left: 50%; top: 0;
  transform: translateX(-50%);
  display: inline-flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px 8px 16px;
  border-radius: 999px;
  background: var(--fc-surface);
  border: 1px solid var(--fc-border);
  box-shadow: 0 4px 24px rgba(28,23,20,0.06);
}
.g-dock-dot {
  width: 8px; height: 8px;
  border-radius: 50%;
  background: var(--fc-accent, #C08840);
  flex-shrink: 0;
  animation: g-dot-breathe 2s ease-in-out infinite;
}
.g-dock-dot.alert { background: #B45B51; animation: g-dot-pulse 1.2s ease-in-out infinite; }
.g-dock-label { font-size: 13px; font-weight: 500; color: var(--fc-text); white-space: nowrap; }
.g-dock-meta { font-size: 12px; color: var(--fc-text-muted); white-space: nowrap; }
.g-dock-timer { font-size: 13px; font-weight: 600; color: var(--fc-accent, #C08840); font-variant-numeric: tabular-nums; }

/* ═══ Shell — full overlay ═══ */
.g-shell {
  position: absolute; inset: 0;
  padding: 24px;
  border-radius: 24px;
  background: var(--fc-surface);
  border: 1px solid var(--fc-border);
  box-shadow: 0 4px 24px rgba(28,23,20,0.06);
  overflow: auto;
}

/* ═══ Header ═══ */
.g-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}
.g-header-left {
  display: flex;
  align-items: center;
  gap: 14px;
}
.g-title {
  font-family: var(--fc-font-display);
  font-size: 22px;
  font-weight: 600;
  color: var(--fc-text);
  margin: 0;
}
.g-phase-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 16px;
  border-radius: 999px;
  background: var(--fc-bg);
  border: 1px solid var(--fc-border);
  font-size: 13px;
  font-weight: 500;
  color: var(--fc-text-sec);
}
.g-phase-dot {
  width: 8px; height: 8px;
  border-radius: 50%;
  background: var(--fc-accent, #C08840);
  animation: g-dot-breathe 2s ease-in-out infinite;
}
.g-phase-pill.alert .g-phase-dot { background: #B45B51; animation: g-dot-pulse 1.2s ease-in-out infinite; }
.g-header-actions { display: flex; gap: 8px; }

/* ═══ Buttons ═══ */
.g-btn {
  border: 1px solid var(--fc-border);
  border-radius: 999px;
  padding: 8px 18px;
  background: var(--fc-surface);
  color: var(--fc-text-sec);
  font: inherit;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: border-color 0.2s, color 0.2s;
  white-space: nowrap;
}
.g-btn:hover { border-color: var(--fc-border-strong); color: var(--fc-text); }
.g-btn.accent {
  background: var(--fc-accent, #C08840);
  color: #fff;
  border-color: var(--fc-accent, #C08840);
}
.g-btn.accent:hover { opacity: 0.9; }
.g-btn.ghost { background: transparent; }
.g-btn.sm { padding: 5px 14px; font-size: 12px; }
.g-btn:disabled { opacity: 0.4; cursor: not-allowed; }

/* ═══ Steps ═══ */
.g-steps {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 20px;
}
.g-step {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border-radius: 12px;
  transition: background 0.3s;
}
.g-step-num {
  width: 26px; height: 26px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  color: var(--fc-text-muted);
  border: 1px solid var(--fc-border);
  background: var(--fc-surface);
  transition: all 0.3s;
  flex-shrink: 0;
}
.g-step-label {
  font-size: 13px;
  color: var(--fc-text-muted);
  transition: color 0.3s;
}
.g-step.done .g-step-num { background: var(--fc-text); color: #fff; border-color: var(--fc-text); }
.g-step.done .g-step-label { color: var(--fc-text-sec); }
.g-step.active { background: rgba(192,136,64,0.08); }
.g-step.active .g-step-num {
  background: var(--fc-accent, #C08840);
  color: #fff;
  border-color: var(--fc-accent, #C08840);
  animation: g-step-pulse 2s ease-in-out infinite;
  box-shadow: 0 0 0 2px rgba(192,136,64,0.22), 0 4px 10px rgba(192,136,64,0.20);
}
.g-step.active .g-step-label { color: var(--fc-text); font-weight: 600; }

/* ═══ Word Card ═══ */
.g-word-card {
  padding: 28px;
  border-radius: 20px;
  background: var(--fc-surface);
  border: 1px solid var(--fc-border);
  text-align: center;
  position: relative;
  overflow: hidden;
  animation: g-word-enter 0.6s cubic-bezier(0.22,0.68,0,1);
}
.g-word-card::after {
  content: '';
  position: absolute;
  inset: -1px;
  border-radius: inherit;
  border: 2px solid transparent;
  background: linear-gradient(135deg, rgba(192,136,64,0.14), transparent 60%) border-box;
  -webkit-mask: linear-gradient(#fff 0 0) padding-box, linear-gradient(#fff 0 0);
  -webkit-mask-composite: xor;
  mask-composite: exclude;
  pointer-events: none;
  opacity: 0;
  animation: g-word-glow 3s ease-in-out 0.6s infinite;
}
.g-word-kicker {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
  margin-bottom: 12px;
}
.g-word-text {
  font-family: var(--fc-font-display);
  font-size: 32px;
  font-weight: 600;
  color: var(--fc-text);
  letter-spacing: 0.04em;
  animation: g-word-reveal 0.8s cubic-bezier(0.22,0.68,0,1);
}
.g-word-hint {
  margin-top: 10px;
  font-size: 13px;
  color: var(--fc-text-muted);
}
.g-word-status {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
  padding: 6px 16px;
  border-radius: 999px;
  background: var(--fc-bg);
  border: 1px solid var(--fc-border);
  font-size: 13px;
  font-weight: 500;
  color: var(--fc-text-sec);
  font-variant-numeric: tabular-nums;
}
.g-word-dot {
  width: 6px; height: 6px;
  border-radius: 50%;
  background: var(--fc-accent, #C08840);
  animation: g-dot-breathe 2s ease-in-out infinite;
}
.g-word-dot.alert { background: #B45B51; }

/* ═══ Player Ring ═══ */
.g-ring {
  position: relative;
  height: 380px;
  margin-top: 16px;
  border-radius: 20px;
  background: var(--fc-bg);
  border: 1px solid var(--fc-border);
  overflow: hidden;
}
.g-ring-center {
  position: absolute;
  left: 50%; top: 50%;
  transform: translate(-50%, -50%);
  width: 110px; height: 110px;
  border-radius: 50%;
  background: var(--fc-surface);
  border: 1px solid var(--fc-border);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  gap: 2px;
  z-index: 2;
  box-shadow: 0 4px 24px rgba(28,23,20,0.06);
}
.g-ring-center strong {
  font-family: var(--fc-font-display);
  font-size: 14px;
  font-weight: 600;
  color: var(--fc-text);
}
.g-ring-center span { font-size: 12px; color: var(--fc-text-muted); }
.g-ring-timer {
  font-size: 18px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--fc-accent, #C08840);
  margin-top: 2px;
}
.g-ring-orbit {
  position: absolute;
  inset: 0;
  animation: g-ring-drift 40s linear infinite;
}
.g-ring-orbit.phase-voting { animation-duration: 50s; }
.g-ring-orbit.phase-result { animation: g-ring-sway 6s ease-in-out infinite; }
.g-seat {
  position: absolute;
  left: 50%; top: 50%;
  width: 80px;
  transform: translate(-50%,-50%) rotate(var(--seat-angle)) translateY(calc(var(--seat-radius) * -1));
}
.g-seat-inner {
  transform: rotate(calc(var(--seat-angle) * -1));
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  animation: g-seat-counter 40s linear infinite;
}
.g-ring-orbit.phase-voting .g-seat-inner { animation-duration: 50s; }
.g-ring-orbit.phase-result .g-seat-inner { animation: g-seat-counter-sway 6s ease-in-out infinite; }
.g-seat-avatar-wrap {
  position: relative;
}
.g-seat-avatar {
  width: 44px; height: 44px;
  border-radius: 50%;
  object-fit: cover;
  border: 2px solid var(--fc-surface);
  box-shadow: 0 2px 12px rgba(28,23,20,0.08);
  transition: border-color 0.3s, box-shadow 0.3s;
}
.g-seat.active .g-seat-avatar {
  border-color: var(--fc-accent, #C08840);
  box-shadow: 0 0 0 3px rgba(192,136,64,0.18), 0 2px 12px rgba(28,23,20,0.08);
  animation: g-speaking-pulse 2s ease-in-out infinite;
}
.g-seat.self .g-seat-avatar { border-color: var(--fc-accent, #C08840); }
.g-seat.eliminated .g-seat-avatar,
.g-seat.muted .g-seat-avatar { opacity: 0.35; filter: grayscale(0.5); }
.g-seat-name {
  font-size: 12px;
  font-weight: 500;
  color: var(--fc-text);
  white-space: nowrap;
  max-width: 80px;
  overflow: hidden;
  text-overflow: ellipsis;
  text-align: center;
}
.g-seat.eliminated .g-seat-name { color: var(--fc-text-muted); text-decoration: line-through; }
.g-seat-badge {
  position: absolute;
  bottom: -2px; right: -4px;
  padding: 1px 6px;
  border-radius: 999px;
  font-size: 9px;
  font-weight: 600;
  border: 2px solid var(--fc-surface);
  background: var(--fc-accent, #C08840);
  color: #fff;
}
.g-seat-badge.you { background: var(--fc-text); }
.g-seat-badge.out { background: #B45B51; }
.g-seat-thinking {
  position: absolute;
  top: -8px; left: 50%;
  transform: translateX(-50%);
  display: flex; gap: 3px;
}
.g-seat-thinking span {
  width: 4px; height: 4px;
  border-radius: 50%;
  background: var(--fc-accent, #C08840);
  animation: g-thinking 1.2s ease-in-out infinite;
}
.g-seat-thinking span:nth-child(2) { animation-delay: 0.15s; }
.g-seat-thinking span:nth-child(3) { animation-delay: 0.3s; }

/* ═══ Rounds (compact) ═══ */
.g-rounds {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}
.g-round-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-radius: 999px;
  background: var(--fc-bg);
  border: 1px solid var(--fc-border);
  font-size: 12px;
}
.g-round-item strong { color: var(--fc-text); font-weight: 600; }
.g-round-item span { color: var(--fc-text-muted); }
.g-round-item:not(.tie) { border-color: rgba(180,91,81,0.12); }
.g-round-item:not(.tie) strong { color: #B45B51; }

/* ═══ Playing Layout ═══ */
.g-playing { margin-top: 4px; }
.g-play-grid {
  display: grid;
  grid-template-columns: minmax(320px, 1fr) minmax(360px, 1.2fr);
  gap: 20px;
  align-items: start;
}
.g-play-left { display: grid; gap: 0; }
.g-play-right { display: grid; gap: 16px; align-content: start; }

/* ═══ Feed ═══ */
.g-feed {
  border: 1px solid var(--fc-border);
  border-radius: 20px;
  background: var(--fc-surface);
  overflow: hidden;
}
.g-feed-head {
  padding: 14px 20px;
  border-bottom: 1px solid var(--fc-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.g-feed-head h4 {
  margin: 0;
  font-family: var(--fc-font-display);
  font-size: 15px;
  font-weight: 600;
  color: var(--fc-text);
}
.g-feed-count {
  font-size: 12px;
  color: var(--fc-text-muted);
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--fc-bg);
  border: 1px solid var(--fc-border);
}
.g-feed-body {
  padding: 16px 20px;
  display: grid;
  gap: 12px;
  max-height: 320px;
  overflow: auto;
}
.g-msg {
  display: flex;
  gap: 10px;
  animation: g-msg-enter 0.35s cubic-bezier(0.22,0.68,0,1);
}
.g-msg-avatar {
  width: 32px; height: 32px;
  border-radius: 50%;
  object-fit: cover;
  flex-shrink: 0;
  border: 1px solid var(--fc-border);
}
.g-msg-body { flex: 1; min-width: 0; }
.g-msg-body strong {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: var(--fc-text);
  margin-bottom: 2px;
}
.g-msg-body span {
  font-size: 14px;
  color: var(--fc-text-sec);
  line-height: 1.6;
}
.g-empty {
  text-align: center;
  color: var(--fc-text-muted);
  font-size: 13px;
  padding: 24px;
}

/* ═══ Action Bar ═══ */
.g-action-bar {
  padding: 14px 20px;
  border-top: 1px solid var(--fc-border);
  display: flex;
  gap: 10px;
  align-items: flex-end;
}
.g-action-bar.passive {
  font-size: 13px;
  color: var(--fc-text-muted);
  align-items: center;
}
.g-action-input {
  flex: 1;
  min-height: 40px;
  max-height: 120px;
  padding: 10px 16px;
  border: 1px solid var(--fc-border);
  border-radius: 16px;
  background: var(--fc-bg);
  font: inherit;
  font-size: 14px;
  color: var(--fc-text);
  resize: none;
  outline: none;
  transition: border-color 0.2s;
}
.g-action-input:focus { border-color: var(--fc-border-strong); }
.g-action-input::placeholder { color: var(--fc-text-muted); }

/* ═══ Vote Section ═══ */
.g-vote-result {
  padding: 16px 20px;
  border-radius: 16px;
  background: var(--fc-bg);
  border: 1px solid var(--fc-border);
}
.g-vote-result strong {
  font-size: 14px;
  color: var(--fc-text);
}
.g-vote-breakdown {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}
.g-vote-breakdown span {
  font-size: 12px;
  color: var(--fc-text-muted);
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--fc-surface);
  border: 1px solid var(--fc-border);
}
.g-vote-section {
  padding: 20px;
  border-radius: 20px;
  background: var(--fc-surface);
  border: 1px solid var(--fc-border);
}
.g-vote-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.g-vote-head h4 {
  margin: 0;
  font-family: var(--fc-font-display);
  font-size: 18px;
  font-weight: 600;
  color: var(--fc-text);
}
.g-vote-count {
  font-size: 12px;
  color: var(--fc-text-muted);
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--fc-bg);
  border: 1px solid var(--fc-border);
}
.g-vote-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
}
.g-vote-card {
  position: relative;
  padding: 16px;
  border: 1px solid var(--fc-border);
  border-radius: 16px;
  background: var(--fc-surface);
  cursor: pointer;
  text-align: left;
  transition: border-color 0.2s, box-shadow 0.25s, transform 0.25s;
  animation: g-vote-rise 0.4s cubic-bezier(0.22,0.68,0,1) both;
}
.g-vote-card:nth-child(2) { animation-delay: 0.05s; }
.g-vote-card:nth-child(3) { animation-delay: 0.10s; }
.g-vote-card:nth-child(4) { animation-delay: 0.15s; }
.g-vote-card:nth-child(5) { animation-delay: 0.20s; }
.g-vote-card:hover {
  border-color: var(--fc-border-strong);
  transform: translateY(-2px);
  box-shadow: 0 8px 24px rgba(28,23,20,0.06);
}
.g-vote-card.selected {
  border-color: var(--fc-selected-border, rgba(168,131,16,0.50));
  background: var(--fc-selected-bg, linear-gradient(180deg, #FFF8E7 0%, #F4E4BF 100%));
  box-shadow: var(--fc-selected-shadow, 0 0 0 2px rgba(212,175,55,0.28), 0 8px 16px rgba(168,131,16,0.14), inset 0 1px 0 rgba(255,255,255,0.78));
}
.g-vote-card.selected::after {
  content: '✓';
  position: absolute;
  top: 10px; right: 10px;
  width: 22px; height: 22px;
  border-radius: 50%;
  background: var(--fc-accent, #C08840);
  color: #fff;
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  animation: g-check-pop 0.3s cubic-bezier(0.22,0.68,0,1);
}
.g-vote-card:disabled { opacity: 0.5; cursor: default; transform: none; }
.g-vote-card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.g-vote-avatar {
  width: 36px; height: 36px;
  border-radius: 50%;
  object-fit: cover;
  border: 1px solid var(--fc-border);
  flex-shrink: 0;
}
.g-vote-card-head strong { font-size: 14px; color: var(--fc-text); }
.g-vote-card-head span { display: block; font-size: 12px; color: var(--fc-text-muted); margin-top: 2px; }
.g-vote-quote {
  font-size: 13px;
  color: var(--fc-text-sec);
  line-height: 1.5;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  margin: 0;
}
.g-vote-submit {
  display: flex;
  justify-content: center;
  margin-top: 16px;
}
.g-vote-waited {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 14px;
  border-radius: 16px;
  background: var(--fc-bg);
  border: 1px solid var(--fc-border);
  font-size: 13px;
  color: var(--fc-text-sec);
}
.g-vote-waited-icon {
  width: 22px; height: 22px;
  border-radius: 50%;
  background: var(--fc-accent, #C08840);
  color: #fff;
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* ═══ Lobby ═══ */
.g-lobby {
  text-align: center;
  padding: 40px 24px;
}
.g-lobby-title {
  font-family: var(--fc-font-display);
  font-size: 26px;
  font-weight: 600;
  color: var(--fc-text);
  margin: 0 0 8px;
}
.g-lobby-desc {
  font-size: 14px;
  color: var(--fc-text-sec);
  margin: 0 0 28px;
}
.g-lobby-avatars {
  display: flex;
  justify-content: center;
  margin-bottom: 24px;
}
.g-lobby-avatar {
  width: 48px; height: 48px;
  border-radius: 50%;
  border: 3px solid var(--fc-surface);
  margin-left: -8px;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 2px 8px rgba(28,23,20,0.06);
  animation: g-lobby-avatar 0.5s cubic-bezier(0.22,0.68,0,1) both;
}
.g-lobby-avatar:nth-child(1) { animation-delay: 0s; }
.g-lobby-avatar:nth-child(2) { animation-delay: 0.06s; }
.g-lobby-avatar:nth-child(3) { animation-delay: 0.12s; }
.g-lobby-avatar:nth-child(4) { animation-delay: 0.18s; }
.g-lobby-avatar:nth-child(5) { animation-delay: 0.24s; }
.g-lobby-avatar:nth-child(6) { animation-delay: 0.30s; }
.g-lobby-avatar img {
  width: 100%; height: 100%;
  object-fit: cover;
}
.g-lobby-avatar.empty {
  background: var(--fc-bg);
  border-color: var(--fc-border);
  border-style: dashed;
  color: var(--fc-text-muted);
  font-size: 18px;
}
.g-lobby-meter {
  width: 280px;
  margin: 0 auto 24px;
}
.g-lobby-meter-track {
  height: 6px;
  border-radius: 999px;
  background: var(--fc-border);
  overflow: hidden;
}
.g-lobby-meter-track span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--fc-accent, #C08840);
  transition: width 0.6s cubic-bezier(0.22,0.68,0,1);
}
.g-lobby-meter-info {
  display: flex;
  justify-content: space-between;
  margin-top: 8px;
  font-size: 12px;
  color: var(--fc-text-muted);
}
.g-lobby-personas {
  display: flex;
  justify-content: center;
  gap: 12px;
  margin-bottom: 24px;
}
.g-persona {
  padding: 14px 20px;
  border: 1px solid var(--fc-border);
  border-radius: 16px;
  background: var(--fc-surface);
  cursor: pointer;
  text-align: center;
  transition: border-color 0.2s, transform 0.2s;
  min-width: 110px;
}
.g-persona:hover { border-color: var(--fc-border-strong); transform: translateY(-2px); }
.g-persona:disabled { opacity: 0.4; cursor: not-allowed; transform: none; }
.g-persona em { font-style: normal; font-size: 20px; display: block; margin-bottom: 6px; }
.g-persona strong { font-size: 14px; color: var(--fc-text); display: block; }
.g-persona span { font-size: 12px; color: var(--fc-text-muted); display: block; margin-top: 4px; }
.g-lobby-actions {
  display: flex;
  justify-content: center;
  gap: 10px;
}

/* ═══ Result ═══ */
.g-result {
  text-align: center;
  padding: 40px 24px;
}
.g-result-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 20px;
  border-radius: 999px;
  background: rgba(192,136,64,0.08);
  border: 1px solid rgba(192,136,64,0.14);
  font-size: 13px;
  font-weight: 600;
  color: var(--fc-accent, #C08840);
  margin-bottom: 16px;
  animation: g-result-badge 0.6s cubic-bezier(0.22,0.68,0,1);
}
.g-result-title {
  font-family: var(--fc-font-display);
  font-size: 28px;
  font-weight: 600;
  color: var(--fc-text);
  margin: 0 0 10px;
  animation: g-result-title 0.7s cubic-bezier(0.22,0.68,0,1);
}
.g-result-desc {
  font-size: 14px;
  color: var(--fc-text-sec);
  margin: 0 0 28px;
}
.g-result-words {
  display: flex;
  justify-content: center;
  gap: 20px;
  margin-bottom: 28px;
}
.g-result-word {
  padding: 18px 28px;
  border-radius: 16px;
  background: var(--fc-bg);
  border: 1px solid var(--fc-border);
  animation: g-result-word 0.5s cubic-bezier(0.22,0.68,0,1) both;
}
.g-result-word:nth-child(2) { animation-delay: 0.1s; }
.g-result-word-label {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
  margin-bottom: 6px;
}
.g-result-word-text {
  font-family: var(--fc-font-display);
  font-size: 22px;
  font-weight: 600;
  color: var(--fc-text);
}
.g-result-word.spy .g-result-word-label { color: #B45B51; }
.g-result-word.spy { border-color: rgba(180,91,81,0.14); }
.g-result-players {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 10px;
  max-width: 640px;
  margin: 0 auto 24px;
}
.g-result-player {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-radius: 14px;
  background: var(--fc-bg);
  border: 1px solid var(--fc-border);
  text-align: left;
  animation: g-result-player 0.4s cubic-bezier(0.22,0.68,0,1) both;
}
.g-result-player:nth-child(2) { animation-delay: 0.06s; }
.g-result-player:nth-child(3) { animation-delay: 0.12s; }
.g-result-player:nth-child(4) { animation-delay: 0.18s; }
.g-result-player:nth-child(5) { animation-delay: 0.24s; }
.g-result-player:nth-child(6) { animation-delay: 0.30s; }
.g-result-avatar {
  width: 34px; height: 34px;
  border-radius: 50%;
  object-fit: cover;
  flex-shrink: 0;
  border: 1px solid var(--fc-border);
}
.g-result-info { flex: 1; min-width: 0; }
.g-result-info strong { font-size: 14px; color: var(--fc-text); display: block; }
.g-result-info span { font-size: 12px; color: var(--fc-text-muted); }
.g-result-player.spy .g-result-info span { color: #B45B51; font-weight: 600; }
.g-result-player.eliminated { opacity: 0.5; }
.g-result-word-sm { font-size: 12px; color: var(--fc-text-muted); font-style: normal; margin-left: auto; }
.g-result-actions {
  display: flex;
  justify-content: center;
  gap: 10px;
}

/* ═══ History / Replay ═══ */
.g-history {
  display: grid;
  gap: 10px;
  max-width: 640px;
  margin: 0 auto 24px;
  text-align: left;
}
.g-history-round {
  padding: 12px 16px;
  border-radius: 14px;
  background: var(--fc-bg);
  border: 1px solid var(--fc-border);
}
.g-history-round strong { font-size: 13px; color: var(--fc-text); }
.g-history-round > span { font-size: 12px; color: var(--fc-text-muted); margin-left: 8px; }
.g-history-round ul {
  margin: 8px 0 0;
  padding-left: 18px;
  font-size: 13px;
  color: var(--fc-text-sec);
}

/* ═══ Sidecard ═══ */
.g-sidecard {
  position: absolute;
  left: 50%; top: 0;
  transform: translateX(-50%);
  width: min(340px, calc(100% - 20px));
}
.g-sidecard-body {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  border-radius: 16px;
  background: var(--fc-surface);
  border: 1px solid var(--fc-border);
  box-shadow: 0 4px 24px rgba(28,23,20,0.06);
}
.g-sidecard-copy strong {
  display: block;
  font-size: 13px;
  color: var(--fc-text);
}
.g-sidecard-copy span {
  font-size: 12px;
  color: var(--fc-text-muted);
  margin-top: 4px;
  display: block;
}
.g-sidecard-actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}

/* ═══ Scrollbars ═══ */
.g-shell::-webkit-scrollbar,
.g-feed-body::-webkit-scrollbar { width: 4px; }
.g-shell::-webkit-scrollbar-thumb,
.g-feed-body::-webkit-scrollbar-thumb { background: rgba(28,23,20,0.14); border-radius: 999px; }
.g-shell::-webkit-scrollbar-track,
.g-feed-body::-webkit-scrollbar-track { background: transparent; }

/* ═══ Responsive ═══ */
@media (max-width: 900px) {
  .g-play-grid { grid-template-columns: 1fr; }
  .g-ring { height: 340px; }
  .g-header { flex-direction: column; align-items: stretch; gap: 12px; }
  .g-header-left { flex-wrap: wrap; }
  .g-steps { flex-wrap: wrap; }
  .g-lobby-personas { flex-direction: column; align-items: center; }
  .g-result-words { flex-direction: column; align-items: center; }
  .g-result-players { grid-template-columns: 1fr; }
  .g-vote-grid { grid-template-columns: 1fr; }
}
@media (max-width: 600px) {
  .game-layer { inset: 6px; }
  .g-shell { padding: 16px; border-radius: 20px; }
  .g-title { font-size: 18px; }
  .g-word-text { font-size: 26px; }
  .g-ring { height: 300px; }
  .g-seat { width: 64px; }
  .g-seat-avatar { width: 36px; height: 36px; }
  .g-seat-name { font-size: 10px; }
  .g-dock { padding: 6px 10px 6px 14px; gap: 8px; }
}

/* ═══ Animations ═══ */
@keyframes g-notice-drift {
  0%, 100% { transform: translateX(-50%) translateY(0); }
  50% { transform: translateX(-50%) translateY(-3px); }
}
@keyframes g-dot-breathe {
  0%, 100% { transform: scale(1); opacity: 1; }
  50% { transform: scale(1.3); opacity: 0.6; }
}
@keyframes g-dot-pulse {
  0%, 100% { transform: scale(1); box-shadow: 0 0 0 0 rgba(180,91,81,0.3); }
  50% { transform: scale(1.2); box-shadow: 0 0 0 6px rgba(180,91,81,0); }
}
@keyframes g-step-pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(192,136,64,0.18); }
  50% { box-shadow: 0 0 0 6px rgba(192,136,64,0); }
}
@keyframes g-word-enter {
  0% { opacity: 0; transform: scale(0.96) translateY(12px); }
  100% { opacity: 1; transform: scale(1) translateY(0); }
}
@keyframes g-word-glow {
  0%, 100% { opacity: 0; }
  50% { opacity: 0.6; }
}
@keyframes g-word-reveal {
  0% { opacity: 0; transform: translateY(8px); filter: blur(8px); }
  100% { opacity: 1; transform: translateY(0); filter: blur(0); }
}
@keyframes g-speaking-pulse {
  0%, 100% { box-shadow: 0 0 0 3px rgba(192,136,64,0.18), 0 2px 12px rgba(28,23,20,0.08); }
  50% { box-shadow: 0 0 0 6px rgba(192,136,64,0.06), 0 2px 12px rgba(28,23,20,0.08); }
}
@keyframes g-thinking {
  0%, 100% { transform: translateY(0); opacity: 0.4; }
  50% { transform: translateY(-4px); opacity: 1; }
}
@keyframes g-ring-drift {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
@keyframes g-ring-sway {
  0%, 100% { transform: rotate(-3deg); }
  50% { transform: rotate(3deg); }
}
@keyframes g-seat-counter {
  from { transform: rotate(calc(var(--seat-angle) * -1)); }
  to { transform: rotate(calc(var(--seat-angle) * -1 - 360deg)); }
}
@keyframes g-seat-counter-sway {
  0%, 100% { transform: rotate(calc(var(--seat-angle) * -1 + 3deg)); }
  50% { transform: rotate(calc(var(--seat-angle) * -1 - 3deg)); }
}
@keyframes g-msg-enter {
  0% { opacity: 0; transform: translateY(8px); }
  100% { opacity: 1; transform: translateY(0); }
}
@keyframes g-vote-rise {
  0% { opacity: 0; transform: translateY(16px) scale(0.97); }
  100% { opacity: 1; transform: translateY(0) scale(1); }
}
@keyframes g-check-pop {
  0% { transform: scale(0); }
  60% { transform: scale(1.2); }
  100% { transform: scale(1); }
}
@keyframes g-lobby-avatar {
  0% { opacity: 0; transform: scale(0.6); }
  100% { opacity: 1; transform: scale(1); }
}
@keyframes g-result-badge {
  0% { opacity: 0; transform: translateY(8px); }
  100% { opacity: 1; transform: translateY(0); }
}
@keyframes g-result-title {
  0% { opacity: 0; transform: translateY(12px); }
  100% { opacity: 1; transform: translateY(0); }
}
@keyframes g-result-word {
  0% { opacity: 0; transform: translateY(16px) scale(0.96); }
  100% { opacity: 1; transform: translateY(0) scale(1); }
}
@keyframes g-result-player {
  0% { opacity: 0; transform: translateX(-8px); }
  100% { opacity: 1; transform: translateX(0); }
}
</style>
