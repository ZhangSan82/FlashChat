package com.flashchat.gameservice.ai;

import com.flashchat.gameservice.ai.model.AiDescribeInput;
import com.flashchat.gameservice.ai.model.AiVoteInput;
import com.flashchat.gameservice.dao.entity.GameDescriptionDO;
import com.flashchat.gameservice.engine.GameContext;
import com.flashchat.gameservice.engine.GamePlayerInfo;
import com.flashchat.gameservice.service.GameDescriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 上下文组装器。
 * <p>
 * 职责：
 * 1. 从 DB 读取历史轮次发言
 * 2. 从内存读取当前轮快照
 * 3. 组装成提交给 AI 线程池的不可变输入
 * <p>
 * 这样引擎只关心状态推进，不直接依赖发言历史查询细节。
 */
@Component
@RequiredArgsConstructor
public class AiContextAssembler {

    /**
     * 发言 prompt 额外保留最近 2 个历史轮次，再加上当前轮快照。
     */
    private static final int DESCRIBE_HISTORY_ROUNDS = 2;

    /**
     * 投票 prompt 额外保留最近 2 个历史轮次，再加上当前轮快照，共覆盖最近 3 轮。
     */
    private static final int VOTE_HISTORY_ROUNDS = 2;

    private final GameDescriptionService gameDescriptionService;

    public AiDescribeInput buildDescribeInput(GameContext context, GamePlayerInfo aiPlayer) {
        int currentRound = context.getCurrentRound().get();
        List<GameContext.DescriptionRecord> currentRoundSnapshot = snapshotCurrentRoundDescriptions(context);
        List<GameDescriptionDO> historyDescriptions = queryHistoryDescriptions(
                context.getGameId(),
                Math.max(1, currentRound - DESCRIBE_HISTORY_ROUNDS),
                currentRound
        );
        Map<Long, GamePlayerInfo> playerById = buildPlayerById(context);

        return AiDescribeInput.builder()
                .gameId(context.getGameId())
                .aiPlayerId(aiPlayer.getPlayerId())
                .provider(aiPlayer.getAiProvider())
                .persona(aiPlayer.getAiPersona())
                .role(aiPlayer.getRole())
                .word(aiPlayer.getWord())
                .roundNumber(currentRound)
                .currentRoundDescriptions(buildCurrentRoundFacts(currentRoundSnapshot, currentRound))
                .historyDescriptions(buildHistoryFacts(historyDescriptions, playerById))
                .build();
    }

    public AiVoteInput buildVoteInput(GameContext context, GamePlayerInfo aiPlayer) {
        int currentRound = context.getCurrentRound().get();
        List<GameContext.DescriptionRecord> currentRoundSnapshot = snapshotCurrentRoundDescriptions(context);
        List<GameDescriptionDO> historyDescriptions = queryHistoryDescriptions(
                context.getGameId(),
                Math.max(1, currentRound - VOTE_HISTORY_ROUNDS),
                currentRound
        );

        Map<Long, List<GameDescriptionDO>> dbDescriptionsByPlayer = historyDescriptions.stream()
                .collect(Collectors.groupingBy(GameDescriptionDO::getPlayerId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<GameContext.DescriptionRecord>> currentDescriptionsByPlayer = currentRoundSnapshot.stream()
                .collect(Collectors.groupingBy(GameContext.DescriptionRecord::getPlayerId, LinkedHashMap::new, Collectors.toList()));

        List<AiVoteInput.VoteCandidate> candidates = new ArrayList<>();
        int index = 1;
        for (GamePlayerInfo candidate : context.getAlivePlayers()) {
            if (candidate.getPlayerId().equals(aiPlayer.getPlayerId())) {
                continue;
            }
            List<AiVoteInput.RoundSpeechFact> rounds = buildCandidateRounds(
                    dbDescriptionsByPlayer.getOrDefault(candidate.getPlayerId(), Collections.emptyList()),
                    currentDescriptionsByPlayer.getOrDefault(candidate.getPlayerId(), Collections.emptyList()),
                    currentRound
            );
            candidates.add(AiVoteInput.VoteCandidate.builder()
                    .index(index++)
                    .playerId(candidate.getPlayerId())
                    .nickname(candidate.getNickname())
                    .descriptionsByRound(List.copyOf(rounds))
                    .build());
        }

        return AiVoteInput.builder()
                .gameId(context.getGameId())
                .aiPlayerId(aiPlayer.getPlayerId())
                .provider(aiPlayer.getAiProvider())
                .persona(aiPlayer.getAiPersona())
                .role(aiPlayer.getRole())
                .word(aiPlayer.getWord())
                .roundNumber(currentRound)
                .candidates(List.copyOf(candidates))
                .build();
    }

    /**
     * 当前轮发言使用 synchronizedList 保存，遍历前先做快照。
     */
    private List<GameContext.DescriptionRecord> snapshotCurrentRoundDescriptions(GameContext context) {
        synchronized (context.getCurrentRoundDescriptions()) {
            return new ArrayList<>(context.getCurrentRoundDescriptions());
        }
    }

    /**
     * 只查当前 AI 需要的最近几轮历史，避免把整局所有发言无界灌给模型。
     * <p>
     * 这里默认依赖当前引擎的串行时序：
     * 上一轮一定是先 {@code persistRoundResult()} 落库，再 {@code startNewRound()}。
     * 因此下一轮 AI 组装输入时，可以稳定读到上一轮已经持久化完成的历史发言。
     */
    private List<GameDescriptionDO> queryHistoryDescriptions(String gameId, int startRound, int currentRound) {
        if (currentRound <= 1 || startRound >= currentRound) {
            return Collections.emptyList();
        }
        return gameDescriptionService.lambdaQuery()
                .select(GameDescriptionDO::getGameId, GameDescriptionDO::getRoundNumber,
                        GameDescriptionDO::getPlayerId, GameDescriptionDO::getContent,
                        GameDescriptionDO::getIsSkipped, GameDescriptionDO::getSubmitTime,
                        GameDescriptionDO::getId)
                .eq(GameDescriptionDO::getGameId, gameId)
                .ge(GameDescriptionDO::getRoundNumber, startRound)
                .lt(GameDescriptionDO::getRoundNumber, currentRound)
                .orderByAsc(GameDescriptionDO::getRoundNumber)
                .orderByAsc(GameDescriptionDO::getSubmitTime)
                .orderByAsc(GameDescriptionDO::getId)
                .list();
    }

    private Map<Long, GamePlayerInfo> buildPlayerById(GameContext context) {
        return context.getAllPlayers().stream()
                .collect(Collectors.toMap(GamePlayerInfo::getPlayerId, player -> player, (left, right) -> left, LinkedHashMap::new));
    }

    private List<AiDescribeInput.SpeechFact> buildCurrentRoundFacts(List<GameContext.DescriptionRecord> snapshot,
                                                                    int currentRound) {
        if (snapshot.isEmpty()) {
            return List.of();
        }
        return List.copyOf(snapshot.stream()
                .map(record -> AiDescribeInput.SpeechFact.builder()
                        .roundNumber(currentRound)
                        .speakerNickname(record.getNickname())
                        .content(record.getContent())
                        .skipped(record.isSkipped())
                        .build())
                .toList());
    }

    private List<AiDescribeInput.SpeechFact> buildHistoryFacts(List<GameDescriptionDO> descriptions,
                                                               Map<Long, GamePlayerInfo> playerById) {
        if (descriptions.isEmpty()) {
            return List.of();
        }
        return List.copyOf(descriptions.stream()
                .map(description -> {
                    GamePlayerInfo player = playerById.get(description.getPlayerId());
                    return AiDescribeInput.SpeechFact.builder()
                            .roundNumber(description.getRoundNumber() != null ? description.getRoundNumber() : 0)
                            .speakerNickname(player != null ? player.getNickname() : "未知玩家")
                            .content(description.getContent())
                            .skipped(description.getIsSkipped() != null && description.getIsSkipped() == 1)
                            .build();
                })
                .toList());
    }

    private List<AiVoteInput.RoundSpeechFact> buildCandidateRounds(List<GameDescriptionDO> dbDescriptions,
                                                                   List<GameContext.DescriptionRecord> currentDescriptions,
                                                                   int currentRound) {
        Map<Integer, List<AiVoteInput.SpeechFact>> roundMap = new LinkedHashMap<>();

        for (GameDescriptionDO description : dbDescriptions) {
            int roundNumber = description.getRoundNumber() != null ? description.getRoundNumber() : 0;
            roundMap.computeIfAbsent(roundNumber, ignored -> new ArrayList<>())
                    .add(AiVoteInput.SpeechFact.builder()
                            .content(description.getContent())
                            .skipped(description.getIsSkipped() != null && description.getIsSkipped() == 1)
                            .build());
        }

        if (!currentDescriptions.isEmpty()) {
            List<AiVoteInput.SpeechFact> currentFacts = currentDescriptions.stream()
                    .map(description -> AiVoteInput.SpeechFact.builder()
                            .content(description.getContent())
                            .skipped(description.isSkipped())
                            .build())
                    .toList();
            roundMap.computeIfAbsent(currentRound, ignored -> new ArrayList<>())
                    .addAll(currentFacts);
        }

        return roundMap.entrySet().stream()
                .map(entry -> AiVoteInput.RoundSpeechFact.builder()
                        .roundNumber(entry.getKey())
                        .speeches(List.copyOf(entry.getValue()))
                        .build())
                .toList();
    }
}
