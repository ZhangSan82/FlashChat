package com.flashchat.gameservice.engine;

import com.flashchat.channel.ChannelPushService;
import com.flashchat.channel.event.GameEndedNotifyEvent;
import com.flashchat.gameservice.constant.GameWsEventType;
import com.flashchat.gameservice.dao.entity.GamePlayerDO;
import com.flashchat.gameservice.dao.entity.WordPairDO;
import com.flashchat.gameservice.dao.enums.*;
import com.flashchat.gameservice.timer.GameTurnTimer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 谁是卧底 — 核心状态机引擎
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WhoIsSpyEngine {

    private final ChannelPushService channelPushService;
    private final GameContextManager gameContextManager;
    private final GameTurnTimer gameTurnTimer;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 分配角色、词语、发言顺序，然后启动第一轮
     */
    public void assignRolesAndStart(GameContext ctx,
                                    List<GamePlayerDO> playerDOs,
                                    WordPairDO wordPair) {
        int totalPlayers = playerDOs.size();
        int spyCount = ctx.getConfig().resolveSpyCount(totalPlayers);
        // 1. 打乱顺序
        List<GamePlayerDO> shuffled = new ArrayList<>(playerDOs);
        Collections.shuffle(shuffled);
        Set<Long> spyPlayerIds = shuffled.stream().limit(spyCount).map(GamePlayerDO::getId).collect(Collectors.toSet());
        Collections.shuffle(shuffled);
        // 2. 根据spyPlayerIds决定卧底
        List<GamePlayerInfo> playerInfos = new ArrayList<>(totalPlayers);
        for (int i = 0; i < shuffled.size(); i++) {
            GamePlayerDO pdo = shuffled.get(i);
            boolean isSpy = spyPlayerIds.contains(pdo.getId());
            playerInfos.add(GamePlayerInfo.builder()
                    .playerId(pdo.getId())
                    .accountId(pdo.getAccountId())
                    .playerType(PlayerTypeEnum.of(pdo.getPlayerType()))
                    .nickname(pdo.getNickname())
                    .avatar(pdo.getAvatar())
                    .role(isSpy ? GameRoleEnum.SPY : GameRoleEnum.CIVILIAN)
                    .word(isSpy ? wordPair.getWordB() : wordPair.getWordA())
                    .playerOrder(i + 1)
                    .status(PlayerStatusEnum.ALIVE)
                    .aiProvider(AiProviderEnum.of(pdo.getAiProvider()))
                    .aiPersona(AiPersonaEnum.of(pdo.getAiPersona()))
                    .build());
        }
        // 3. 初始化上下文
        ctx.initPlayers(playerInfos);
        ctx.setCivilianWord(wordPair.getWordA());
        ctx.setSpyWord(wordPair.getWordB());
        ctx.casGameStatus(GameStatusEnum.WAITING, GameStatusEnum.PLAYING);
        // 4. 广播游戏开始
        List<Map<String, Object>> playerList = playerInfos.stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("accountId", p.getAccountId());
                    m.put("nickname", p.getNickname());
                    m.put("avatar", p.getAvatar());
                    m.put("playerType", p.getPlayerType().getCode());
                    m.put("playerOrder", p.getPlayerOrder());
                    return m;
                }).collect(Collectors.toList());
        Map<String, Object> startData = new LinkedHashMap<>();
        startData.put("gameId", ctx.getGameId());
        startData.put("players", playerList);
        broadcastToPlayers(ctx, GameWsEventType.GAME_STARTED, startData);
        // 5. 私发每个玩家的角色和词语
        for (GamePlayerInfo p : playerInfos) {
            Map<String, Object> roleData = new LinkedHashMap<>();
            roleData.put("role", p.getRole().getCode());
            roleData.put("word", p.getWord());
            channelPushService.sendToUser(p.getAccountId(),
                    GameWsEventType.GAME_ROLE_ASSIGNED, ctx.getRoomId(), roleData);
        }
        log.info("[Engine] 游戏开始 gameId={}, 玩家={}, 卧底数={}",
                ctx.getGameId(), totalPlayers, spyCount);
        // 6. 启动第一轮
        ctx.setRoundStartOrder(1);
        startNewRound(ctx);
    }

    /**
     * 游戏过程
     */
    public void startNewRound(GameContext ctx) {
        ctx.startNewRound();
        ctx.getCurrentPhase().set(RoundPhaseEnum.DESCRIBING);
        int startBefore = ctx.getRoundStartOrder() - 1;
        ctx.getCurrentSpeakerOrder().set(startBefore);
        log.info("[Engine] 第{}轮开始 gameId={}, roundStartOrder={}",
                ctx.getCurrentRound().get(), ctx.getGameId(), ctx.getRoundStartOrder());
        advanceToNextSpeaker(ctx, startBefore);
    }

    /**
     * 推进到下一个发言者
     */
    public void advanceToNextSpeaker(GameContext ctx, int expectedCurrentOrder) {
        int total = ctx.getAllPlayers().size();
        int scanOrder = expectedCurrentOrder;
        int scannedCount = 0;
        // 收集扫描过程中遇到的 DISCONNECTED 玩家，CAS 成功后再统一处理
        List<GamePlayerInfo> skippedDisconnected = new ArrayList<>();
        while (scannedCount < total) {
            scanOrder = (scanOrder % total) + 1;
            scannedCount++;
            GamePlayerInfo target = ctx.getPlayerByOrder(scanOrder);
            if (target == null) {
                continue;
            }
            if (target.getStatus() == PlayerStatusEnum.ELIMINATED) {
                continue;
            }
            if (target.getStatus() == PlayerStatusEnum.DISCONNECTED) {
                // 不在此处记录和广播 — 收集起来等 CAS 成功后处理
                boolean alreadyRecorded = ctx.getCurrentRoundDescriptions().stream()
                        .anyMatch(d -> d.getPlayerId().equals(target.getPlayerId()));
                if (!alreadyRecorded) {
                    skippedDisconnected.add(target);
                }
                continue;
            }
            // ===== ALIVE 玩家 — CAS 抢占 =====
            if (!ctx.casSpeakerOrder(expectedCurrentOrder, scanOrder)) {
                log.debug("[Engine] CAS 推进失败 gameId={}, expected={}, target={}",
                        ctx.getGameId(), expectedCurrentOrder, scanOrder);
                return;
            }
            // CAS 成功 — 先处理跳过的 DISCONNECTED 玩家
            processSkippedDisconnected(ctx, skippedDisconnected);
            // 启动该玩家的发言回合
            startTurnForPlayer(ctx, target);
            return;
        }

        // ===== 全部扫描完毕，无 ALIVE 玩家可发言 =====
        // 用 total + 1 作为哨兵值（有效 order 范围 1~total）防止重复处理
        if (!ctx.casSpeakerOrder(expectedCurrentOrder, total + 1)) {
            return;
        }
        processSkippedDisconnected(ctx, skippedDisconnected);
        if (allHumansGone(ctx)) {
            log.warn("[Engine] 所有真人掉线 gameId={}", ctx.getGameId());
            endGame(ctx, null, EndReasonEnum.ALL_DISCONNECTED);
        } else {
            enterVotingPhase(ctx);
        }
    }

    /**
     * CAS 成功后统一处理跳过的掉线玩家：记录空白发言 + 广播
     */
    private void processSkippedDisconnected(GameContext ctx, List<GamePlayerInfo> skipped) {
        for (GamePlayerInfo dc : skipped) {
            ctx.addDescription(new GameContext.DescriptionRecord(
                    dc.getPlayerId(), dc.getAccountId(),
                    dc.getNickname(), null, true));
            Map<String, Object> skipData = new LinkedHashMap<>();
            skipData.put("speakerAccountId", dc.getAccountId());
            skipData.put("speakerNickname", dc.getNickname());
            skipData.put("message", dc.getNickname() + " 掉线，跳过发言");
            broadcastToPlayers(ctx, GameWsEventType.GAME_PLAYER_DISCONNECTED, skipData);
            broadcastDescription(ctx, dc.getAccountId(), dc.getNickname(), null, true);
        }
    }

    /**
     * 处理玩家提交发言
     */
    public void handleDescriptionSubmitted(GameContext ctx, Long accountId, String content) {
        // 校验 1：阶段
        if (ctx.getCurrentPhase().get() != RoundPhaseEnum.DESCRIBING) {
            return;
        }
        GamePlayerInfo player = ctx.getPlayerByAccountId(accountId);
        if (player == null) {
            return;
        }
        // 校验 2：是否当前发言者
        int playerOrder = player.getPlayerOrder();
        if (ctx.getCurrentSpeakerOrder().get() != playerOrder) {
            return;
        }
        // 校验 3：是否在截止时间内
        if (System.currentTimeMillis() >= ctx.getTurnDeadline()) {
            log.debug("[Engine] 发言已超时 gameId={}, player={}, order={}",
                    ctx.getGameId(), player.getNickname(), playerOrder);
            return;
        }
        // 取消超时定时器
        ctx.cancelCurrentTurnTimer();
        // 记录发言
        ctx.addDescription(new GameContext.DescriptionRecord(
                player.getPlayerId(), player.getAccountId(),
                player.getNickname(), content, false));
        // 广播发言内容
        broadcastDescription(ctx, player.getAccountId(), player.getNickname(), content, false);
        log.info("[Engine] 发言提交 gameId={}, player={}, order={}",
                ctx.getGameId(), player.getNickname(), playerOrder);
        // 推进到下一个发言者
        advanceToNextSpeaker(ctx, playerOrder);
    }

    /**
     * 进入投票阶段
     */
    public void enterVotingPhase(GameContext ctx) {
        if (!ctx.casPhase(RoundPhaseEnum.DESCRIBING, RoundPhaseEnum.VOTING)) {
            return;
        }
        int timeoutSeconds = ctx.getConfig().getVoteTimeoutOrDefault();
        // 可投票目标：所有存活玩家（含 DISCONNECTED，不含 ELIMINATED）
        List<Map<String, Object>> targets = ctx.getAlivePlayers().stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("accountId", p.getAccountId());
                    m.put("nickname", p.getNickname());
                    m.put("status", p.getStatus().getCode());
                    return m;
                }).collect(Collectors.toList());
        Map<String, Object> voteData = new LinkedHashMap<>();
        voteData.put("timeoutSeconds", timeoutSeconds);
        voteData.put("deadline", System.currentTimeMillis() + timeoutSeconds * 1000L);
        voteData.put("votableTargets", targets);
        voteData.put("roundNumber", ctx.getCurrentRound().get());
        broadcastToPlayers(ctx, GameWsEventType.GAME_VOTE_PHASE, voteData);
        gameTurnTimer.scheduleVoteTimeout(ctx, timeoutSeconds, () -> handleVoteTimeout(ctx));
        log.info("[Engine] 进入投票阶段 gameId={}, round={}, timeout={}s",
                ctx.getGameId(), ctx.getCurrentRound().get(), timeoutSeconds);
    }

    /**
     * 处理投票提交
     */
    public boolean handleVoteSubmitted(GameContext ctx, Long voterAccountId, Long targetAccountId) {
        // 校验 1：阶段
        if (ctx.getCurrentPhase().get() != RoundPhaseEnum.VOTING) {
            return false;
        }
        Long voterPlayerId = ctx.toPlayerId(voterAccountId);
        Long targetPlayerId = ctx.toPlayerId(targetAccountId);
        if (voterPlayerId == null || targetPlayerId == null) {
            return false;
        }
        // 校验 2：投票者必须在游戏中（ALIVE 或 DISCONNECTED，不含 ELIMINATED）
        GamePlayerInfo voter = ctx.getPlayerByPlayerId(voterPlayerId);
        if (voter == null || !voter.isInGame()) {
            log.debug("[Engine] 投票被拒：投票者不在游戏中 gameId={}, voter={}",
                    ctx.getGameId(), voterAccountId);
            return false;
        }
        // 校验 3：目标必须在游戏中，且不能投自己
        GamePlayerInfo target = ctx.getPlayerByPlayerId(targetPlayerId);
        if (target == null || !target.isInGame()) {
            log.debug("[Engine] 投票被拒：目标不在游戏中 gameId={}, target={}",
                    ctx.getGameId(), targetAccountId);
            return false;
        }
        if (voterPlayerId.equals(targetPlayerId)) {
            log.debug("[Engine] 投票被拒：不能投自己 gameId={}, voter={}",
                    ctx.getGameId(), voterAccountId);
            return false;
        }
        // 校验 4：幂等投票
        boolean accepted = ctx.castVote(voterPlayerId, targetPlayerId);
        if (!accepted) {
            return false;
        }
        log.debug("[Engine] 投票成功 gameId={}, voter={}, target={}",
                ctx.getGameId(), voterAccountId, targetAccountId);
        // 检查是否所有人都已投票
        if (ctx.allVoted()) {
            ctx.cancelCurrentTurnTimer();
            judge(ctx);
        }
        return true;
    }

    /**
     * 投票超时回调
     */
    public void handleVoteTimeout(GameContext ctx) {
        if (ctx.getCurrentPhase().get() != RoundPhaseEnum.VOTING) {
            return;
        }
        log.info("[Engine] 投票超时 gameId={}", ctx.getGameId());
        judge(ctx);
    }

    /**
     * 统计票数 → 淘汰 → 判断胜负
     */
    public void judge(GameContext ctx) {
        if (!ctx.casPhase(RoundPhaseEnum.VOTING, RoundPhaseEnum.JUDGING)) {
            return;
        }
        // 1. 自动补票：为未投票者（掉线/超时）随机投票
        for (GamePlayerInfo p : ctx.getAlivePlayers()) {
            if (!ctx.getVotes().containsKey(p.getPlayerId())) {
                Long randomTarget = pickRandomVoteTarget(ctx, p.getPlayerId());
                if (randomTarget != null) {
                    ctx.castVote(p.getPlayerId(), randomTarget);
                    log.debug("[Engine] 自动补票 gameId={}, voter={}, target={}",
                            ctx.getGameId(), ctx.toAccountId(p.getPlayerId()),
                            ctx.toAccountId(randomTarget));
                }
            }
        }
        // 2. 统计票数
        Map<Long, Integer> voteCounts = new HashMap<>();
        ctx.getVotes().forEach((voter, target) ->
                voteCounts.merge(target, 1, Integer::sum));
        int maxVotes = voteCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<Long> maxVotedPlayerIds = voteCounts.entrySet().stream()
                .filter(e -> e.getValue() == maxVotes)
                .map(Map.Entry::getKey)
                .toList();
        boolean isTie = maxVotedPlayerIds.size() > 1;
        Long eliminatedPlayerId = isTie ? null : maxVotedPlayerIds.get(0);
        // 3. 构建投票详情（转换为 accountId）
        Map<Long, List<Long>> voteDetails = new LinkedHashMap<>();
        ctx.getVotes().forEach((voterPId, targetPId) -> {
            Long targetAccId = ctx.toAccountId(targetPId);
            Long voterAccId = ctx.toAccountId(voterPId);
            voteDetails.computeIfAbsent(targetAccId, k -> new ArrayList<>()).add(voterAccId);
        });
        // 4. 执行淘汰
        GamePlayerInfo eliminated = null;
        if (!isTie && eliminatedPlayerId != null) {
            eliminated = ctx.getPlayerByPlayerId(eliminatedPlayerId);
            ctx.eliminatePlayer(eliminatedPlayerId);
        }
        // 5. 广播投票结果
        Map<String, Object> resultData = new LinkedHashMap<>();
        resultData.put("voteDetails", voteDetails);
        resultData.put("eliminatedAccountId", eliminated != null ? eliminated.getAccountId() : null);
        resultData.put("eliminatedNickname", eliminated != null ? eliminated.getNickname() : null);
        resultData.put("eliminatedRole", eliminated != null ? eliminated.getRole().getCode() : null);
        resultData.put("isTie", isTie);
        resultData.put("roundNumber", ctx.getCurrentRound().get());
        broadcastToPlayers(ctx, GameWsEventType.GAME_VOTE_RESULT, resultData);
        log.info("[Engine] 判定完成 gameId={}, round={}, tie={}, eliminated={}",
                ctx.getGameId(), ctx.getCurrentRound().get(), isTie,
                eliminated != null ? eliminated.getNickname() : "无");
        // 6. 检查胜负
        WinnerSideEnum winner = checkWinCondition(ctx);
        if (winner != null) {
            endGame(ctx, winner, EndReasonEnum.NORMAL);
            return;
        }
        // 7. 游戏继续 → 下一轮
        int nextStartOrder = findNextAliveOrderAfter(ctx, ctx.getRoundStartOrder());
        ctx.setRoundStartOrder(nextStartOrder);
        startNewRound(ctx);
    }

    /**
     * 结束游戏
     */
    public void endGame(GameContext ctx, WinnerSideEnum winner, EndReasonEnum reason) {
        if (!ctx.casGameStatus(GameStatusEnum.PLAYING, GameStatusEnum.ENDED)) {
            return;
        }
        gameTurnTimer.cancelAll(ctx);
        List<Map<String, Object>> playerRoles = ctx.getAllPlayers().stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("accountId", p.getAccountId());
                    m.put("nickname", p.getNickname());
                    m.put("role", p.getRole() != null ? p.getRole().getCode() : null);
                    m.put("word", p.getWord());
                    m.put("status", p.getStatus().getCode());
                    return m;
                }).collect(Collectors.toList());
        Map<String, Object> endData = new LinkedHashMap<>();
        endData.put("gameId", ctx.getGameId());
        endData.put("winnerSide", winner != null ? winner.getCode() : null);
        endData.put("endReason", reason.getCode());
        endData.put("civilianWord", ctx.getCivilianWord());
        endData.put("spyWord", ctx.getSpyWord());
        endData.put("playerRoles", playerRoles);
        broadcastToPlayers(ctx, GameWsEventType.GAME_ENDED, endData);
        String summary = buildGameSummary(ctx, winner);
        eventPublisher.publishEvent(new GameEndedNotifyEvent(
                this, ctx.getRoomId(), ctx.getGameId(),
                winner != null ? winner.getCode() : null, summary));
        gameContextManager.remove(ctx.getGameId());
        log.info("[Engine] 游戏结束 gameId={}, winner={}, reason={}",
                ctx.getGameId(), winner, reason);
    }

    // ==================== 私有方法 ====================

    /**
     * 启动某玩家的发言回合
     * <p>
     * 修正点 2：AI 采用同步占位发言，不走 scheduleDescribeTimeout，
     * 不覆盖 turnDeadline。P1 阶段替换为异步 LLM 调用。
     */
    private void startTurnForPlayer(GameContext ctx, GamePlayerInfo player) {
        int timeoutSeconds = ctx.getConfig().getDescribeTimeoutOrDefault();
        Map<String, Object> turnData = new LinkedHashMap<>();
        turnData.put("currentSpeakerAccountId", player.getAccountId());
        turnData.put("currentSpeakerNickname", player.getNickname());
        turnData.put("currentSpeakerOrder", player.getPlayerOrder());
        turnData.put("timeoutSeconds", timeoutSeconds);
        turnData.put("deadline", System.currentTimeMillis() + timeoutSeconds * 1000L);
        turnData.put("roundNumber", ctx.getCurrentRound().get());
        if (player.isAi()) {
            // MVP 阶段：AI 同步占位发言，不启动定时器
            broadcastToPlayers(ctx, GameWsEventType.GAME_AI_THINKING, turnData);
            String aiContent = "[AI] 这个词让我联想到日常生活中很常见的东西";
            ctx.addDescription(new GameContext.DescriptionRecord(
                    player.getPlayerId(), player.getAccountId(),
                    player.getNickname(), aiContent, false));
            broadcastDescription(ctx, player.getAccountId(),
                    player.getNickname(), aiContent, false);
            // 同步推进到下一位（递归深度 ≤ 连续AI数，MVP最多4，不会栈溢出）
            advanceToNextSpeaker(ctx, player.getPlayerOrder());
        } else {
            // 真人玩家：广播轮次开始 + 启动超时定时器
            broadcastToPlayers(ctx, GameWsEventType.GAME_TURN_START, turnData);
            int expectedOrder = player.getPlayerOrder();
            gameTurnTimer.scheduleDescribeTimeout(ctx, timeoutSeconds, () -> {
                // CAS 检查：如果 speakerOrder 已经被推进（玩家正常提交），跳过
                if (ctx.getCurrentSpeakerOrder().get() == expectedOrder) {
                    ctx.addDescription(new GameContext.DescriptionRecord(
                            player.getPlayerId(), player.getAccountId(),
                            player.getNickname(), null, true));
                    broadcastDescription(ctx, player.getAccountId(),
                            player.getNickname(), null, true);
                    advanceToNextSpeaker(ctx, expectedOrder);
                }
            });
        }
    }

    private void broadcastDescription(GameContext ctx, Long speakerAccountId,
                                      String nickname, String content, boolean skipped) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("speakerAccountId", speakerAccountId);
        data.put("speakerNickname", nickname);
        data.put("content", content);
        data.put("isSkipped", skipped);
        data.put("roundNumber", ctx.getCurrentRound().get());
        broadcastToPlayers(ctx, GameWsEventType.GAME_DESCRIPTION, data);
    }

    private void broadcastToPlayers(GameContext ctx, int type, Object data) {
        channelPushService.sendToUsers(ctx.getPlayerAccountIds(), type, ctx.getRoomId(), data);
    }

    private WinnerSideEnum checkWinCondition(GameContext ctx) {
        long aliveSpies = ctx.getAliveSpyCount();
        long aliveCivilians = ctx.getAliveCivilianCount();

        if (aliveSpies == 0) {
            return WinnerSideEnum.CIVILIAN;
        }
        if (aliveSpies >= aliveCivilians) {
            return WinnerSideEnum.SPY;
        }
        return null;
    }

    private Long pickRandomVoteTarget(GameContext ctx, Long selfPlayerId) {
        List<GamePlayerInfo> candidates = ctx.getAlivePlayers().stream()
                .filter(p -> !p.getPlayerId().equals(selfPlayerId))
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())).getPlayerId();
    }

    private int findNextAliveOrderAfter(GameContext ctx, int afterOrder) {
        int total = ctx.getAllPlayers().size();
        for (int i = 1; i <= total; i++) {
            int candidateOrder = ((afterOrder - 1 + i) % total) + 1;
            GamePlayerInfo p = ctx.getPlayerByOrder(candidateOrder);
            if (p != null && p.getStatus() == PlayerStatusEnum.ALIVE) {
                return candidateOrder;
            }
        }
        return afterOrder;
    }

    /**
     * 检查是否所有真人玩家都不在 ALIVE 状态（全掉线或全淘汰）
     */
    private boolean allHumansGone(GameContext ctx) {
        return ctx.getAlivePlayers().stream()
                .filter(GamePlayerInfo::isHuman)
                .noneMatch(p -> p.getStatus() == PlayerStatusEnum.ALIVE);
    }

    private String buildGameSummary(GameContext ctx, WinnerSideEnum winner) {
        if (winner == null) {
            return "谁是卧底游戏异常结束";
        }
        String side = winner == WinnerSideEnum.CIVILIAN ? "平民" : "卧底";
        return String.format("谁是卧底游戏结束！%s 获胜！平民词：%s，卧底词：%s",
                side, ctx.getCivilianWord(), ctx.getSpyWord());
    }
}
