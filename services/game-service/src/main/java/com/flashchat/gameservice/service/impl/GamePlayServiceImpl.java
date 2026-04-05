package com.flashchat.gameservice.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashchat.convention.exception.ClientException;
import com.flashchat.convention.exception.ServiceException;
import com.flashchat.gameservice.config.GameConfig;
import com.flashchat.gameservice.dao.entity.GameDescriptionDO;
import com.flashchat.gameservice.dao.entity.GamePlayerDO;
import com.flashchat.gameservice.dao.entity.GameRoomDO;
import com.flashchat.gameservice.dao.entity.GameRoundDO;
import com.flashchat.gameservice.dao.entity.GameVoteDO;
import com.flashchat.gameservice.dao.entity.WordPairDO;
import com.flashchat.gameservice.dao.enums.GameStatusEnum;
import com.flashchat.gameservice.dao.enums.RoundPhaseEnum;
import com.flashchat.gameservice.dao.mapper.GamePlayerMapper;
import com.flashchat.gameservice.dto.req.StartGameReqDTO;
import com.flashchat.gameservice.dto.req.SubmitDescriptionReqDTO;
import com.flashchat.gameservice.dto.req.SubmitVoteReqDTO;
import com.flashchat.gameservice.dto.resp.GameConfigRespDTO;
import com.flashchat.gameservice.dto.resp.GamePlayerRespDTO;
import com.flashchat.gameservice.dto.resp.GameResultRespDTO;
import com.flashchat.gameservice.dto.resp.GameStateRespDTO;
import com.flashchat.gameservice.engine.GameActionLockManager;
import com.flashchat.gameservice.engine.GameContext;
import com.flashchat.gameservice.engine.GameContextManager;
import com.flashchat.gameservice.engine.GamePlayerInfo;
import com.flashchat.gameservice.engine.WhoIsSpyEngine;
import com.flashchat.gameservice.service.GameDescriptionService;
import com.flashchat.gameservice.service.GamePersistService;
import com.flashchat.gameservice.service.GamePlayService;
import com.flashchat.gameservice.service.GameRoomService;
import com.flashchat.gameservice.service.GameRoundService;
import com.flashchat.gameservice.service.GameVoteService;
import com.flashchat.gameservice.service.WordPairService;
import com.flashchat.user.core.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 游戏进行服务实现。
 * <p>
 * 这一层负责把 Controller 请求接到引擎和持久化层：
 * 1. 开始游戏
 * 2. 提交发言 / 投票
 * 3. 查询实时状态
 * 4. 查询历史记录
 * <p>
 * 约束：
 * 1. 所有 DB 查询统一走 service，不直接依赖 mapper
 * 2. 开局使用共享局部锁，和等待阶段的 join/leave/addAi/cancel 保持同一把锁
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GamePlayServiceImpl extends ServiceImpl<GamePlayerMapper, GamePlayerDO>
        implements GamePlayService {

    private final GameRoomService gameRoomService;
    private final GameDescriptionService gameDescriptionService;
    private final GameRoundService gameRoundService;
    private final GameVoteService gameVoteService;
    private final WordPairService wordPairService;
    private final GamePersistService gamePersistService;
    private final GameContextManager gameContextManager;
    private final GameActionLockManager gameActionLockManager;
    private final WhoIsSpyEngine whoIsSpyEngine;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void startGame(StartGameReqDTO request) {
        Long operatorId = UserContext.getRequiredLoginId();
        String gameId = normalizeRequiredText(request.getGameId(), "游戏 ID 不能为空");

        try (GameActionLockManager.LockHandle lockHandle = gameActionLockManager.lock("game:" + gameId)) {
            lockHandle.deferUnlockAfterTransaction();

            GameRoomDO room = getRequiredGameRoom(gameId);
            ensureWaiting(room);
            if (!operatorId.equals(room.getCreatorId())) {
                throw new ClientException("只有创建者可以开始游戏");
            }

            GameContext context = requireGameContext(gameId);
            if (context.getGameStatus().get() != GameStatusEnum.WAITING) {
                throw new ClientException("当前游戏不处于等待阶段");
            }

            GameConfig config = parseConfig(room.getConfig());
            List<GamePlayerDO> players = queryPlayersByGame(gameId);
            if (players.size() < config.getMinPlayersOrDefault()) {
                throw new ClientException("当前人数不足，无法开始游戏");
            }
            if (players.size() > config.getMaxPlayersOrDefault()) {
                throw new ServiceException("当前人数超过配置上限，请检查房间数据");
            }

            WordPairDO wordPair = wordPairService.pickRandomWordPair(room.getRoomId(), config);
            whoIsSpyEngine.assignRolesAndStart(context, players, wordPair);
            gamePersistService.persistGameStart(context, context.getAllPlayers(), wordPair);

            log.info("[GamePlay] 开始游戏成功 gameId={}, operatorId={}, playerCount={}, wordPairId={}",
                    gameId, operatorId, players.size(), wordPair.getId());
        }
    }

    @Override
    public void submitDescription(SubmitDescriptionReqDTO request) {
        Long accountId = UserContext.getRequiredLoginId();
        String gameId = normalizeRequiredText(request.getGameId(), "游戏 ID 不能为空");
        String content = normalizeRequiredText(request.getContent(), "发言内容不能为空");

        GameContext context = requireGameContext(gameId);
        GamePlayerInfo player = requirePlayerInContext(context, accountId);
        if (context.getCurrentPhase().get() != RoundPhaseEnum.DESCRIBING) {
            throw new ClientException("当前不在发言阶段");
        }
        if (!player.canSpeak()) {
            throw new ClientException("当前状态不能发言");
        }
        if (context.getCurrentSpeakerOrder().get() != player.getPlayerOrder()) {
            throw new ClientException("当前未轮到你发言");
        }
        if (System.currentTimeMillis() >= context.getTurnDeadline()) {
            throw new ClientException("当前发言已超时");
        }

        whoIsSpyEngine.handleDescriptionSubmitted(context, accountId, content);
    }

    @Override
    public void submitVote(SubmitVoteReqDTO request) {
        Long accountId = UserContext.getRequiredLoginId();
        String gameId = normalizeRequiredText(request.getGameId(), "游戏 ID 不能为空");

        GameContext context = requireGameContext(gameId);
        if (context.getCurrentPhase().get() != RoundPhaseEnum.VOTING) {
            throw new ClientException("当前不在投票阶段");
        }

        boolean accepted = whoIsSpyEngine.handleVoteSubmitted(context, accountId, request.getTargetAccountId());
        if (!accepted) {
            throw new ClientException("投票失败，可能已投票或目标无效");
        }
    }

    @Override
    public GameStateRespDTO getGameState(String gameId) {
        Long accountId = UserContext.getRequiredLoginId();
        String normalizedGameId = normalizeRequiredText(gameId, "游戏 ID 不能为空");

        GameRoomDO room = getRequiredGameRoom(normalizedGameId);
        GamePlayerDO myPlayerRecord = getRequiredPlayerRecord(normalizedGameId, accountId);
        GameContext context = gameContextManager.getContext(normalizedGameId);
        GameConfig config = context != null ? context.getConfig() : parseConfig(room.getConfig());

        if (context == null && GameStatusEnum.PLAYING.getCode().equals(room.getGameStatus())) {
            throw new ServiceException("当前版本暂不支持进程重启后的游戏恢复，请稍后重试");
        }

        List<GamePlayerDO> playerRecords = queryPlayersByGame(normalizedGameId);
        List<GamePlayerRespDTO> players = context != null && !context.getAllPlayers().isEmpty()
                ? context.getAllPlayers().stream().map(this::toPlayerResp).toList()
                : playerRecords.stream().map(this::toPlayerResp).toList();
        Map<Long, GamePlayerDO> playerById = playerRecords.stream()
                .filter(player -> player.getId() != null)
                .collect(Collectors.toMap(GamePlayerDO::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));

        GamePlayerInfo currentPlayer = context != null ? context.getPlayerByAccountId(accountId) : null;
        GamePlayerInfo currentSpeaker = context != null
                && context.getCurrentPhase().get() == RoundPhaseEnum.DESCRIBING
                ? context.getCurrentSpeaker()
                : null;

        GameStateRespDTO response = new GameStateRespDTO();
        response.setGameId(room.getGameId());
        response.setRoomId(room.getRoomId());
        response.setGameStatus(context != null ? context.getGameStatus().get().getCode() : room.getGameStatus());
        response.setCurrentPhase(context != null && context.getCurrentPhase().get() != null
                ? context.getCurrentPhase().get().name()
                : null);
        response.setCurrentRound(context != null ? context.getCurrentRound().get() : room.getCurrentRound());
        response.setMyRole(currentPlayer != null && currentPlayer.getRole() != null
                ? currentPlayer.getRole().getCode()
                : myPlayerRecord.getRole());
        response.setMyWord(currentPlayer != null ? currentPlayer.getWord() : myPlayerRecord.getWord());
        response.setMyVoted(buildMyVoted(context, accountId));
        response.setCurrentSpeakerAccountId(currentSpeaker != null ? currentSpeaker.getAccountId() : null);
        response.setCurrentSpeakerNickname(currentSpeaker != null ? currentSpeaker.getNickname() : null);
        response.setCurrentSpeakerOrder(currentSpeaker != null ? currentSpeaker.getPlayerOrder() : null);
        response.setTurnDeadline(context != null && context.getCurrentPhase().get() != null
                ? context.getTurnDeadline()
                : null);
        response.setPlayers(players);
        response.setConfig(GameConfigRespDTO.from(config));
        response.setCurrentRoundDescriptions(buildCurrentDescriptions(context));
        response.setVotableTargets(buildVotableTargets(context));
        response.setRoundResults(buildStateRoundResults(queryRoundsByGame(normalizedGameId), playerById));
        return response;
    }

    @Override
    public GameResultRespDTO getGameHistory(String gameId) {
        Long accountId = UserContext.getRequiredLoginId();
        String normalizedGameId = normalizeRequiredText(gameId, "游戏 ID 不能为空");

        GameRoomDO room = getRequiredGameRoom(normalizedGameId);
        getRequiredPlayerRecord(normalizedGameId, accountId);
        if (!GameStatusEnum.ENDED.getCode().equals(room.getGameStatus())) {
            throw new ClientException("当前游戏尚未结束");
        }

        List<GamePlayerDO> players = queryPlayersByGame(normalizedGameId);
        Map<Long, GamePlayerDO> playerById = players.stream()
                .filter(player -> player.getId() != null)
                .collect(Collectors.toMap(GamePlayerDO::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));

        Map<Integer, List<GameDescriptionDO>> descriptionMap = queryDescriptionsByGame(normalizedGameId).stream()
                .collect(Collectors.groupingBy(GameDescriptionDO::getRoundNumber, LinkedHashMap::new, Collectors.toList()));
        Map<Integer, List<GameVoteDO>> voteMap = queryVotesByGame(normalizedGameId).stream()
                .collect(Collectors.groupingBy(GameVoteDO::getRoundNumber, LinkedHashMap::new, Collectors.toList()));

        List<GameResultRespDTO.RoundResultDTO> roundResults = queryRoundsByGame(normalizedGameId).stream()
                .map(round -> buildRoundResult(round, playerById, descriptionMap.get(round.getRoundNumber()),
                        voteMap.get(round.getRoundNumber())))
                .toList();

        return GameResultRespDTO.builder()
                .gameId(room.getGameId())
                .roomId(room.getRoomId())
                .gameType(room.getGameType())
                .winnerSide(room.getWinnerSide())
                .endReason(room.getEndReason())
                .civilianWord(room.getCivilianWord())
                .spyWord(room.getSpyWord())
                .createTime(room.getCreateTime())
                .endTime(room.getEndTime())
                .players(players.stream().map(this::buildPlayerResult).toList())
                .rounds(roundResults)
                .build();
    }

    /**
     * 查询游戏主记录。
     * <p>
     * 状态查询和开局只读取当前阶段需要的字段，避免无意义列加载。
     */
    private GameRoomDO getRequiredGameRoom(String gameId) {
        GameRoomDO room = gameRoomService.lambdaQuery()
                .select(GameRoomDO::getId, GameRoomDO::getGameId, GameRoomDO::getRoomId,
                        GameRoomDO::getGameType, GameRoomDO::getGameStatus, GameRoomDO::getCurrentRound,
                        GameRoomDO::getConfig, GameRoomDO::getWinnerSide, GameRoomDO::getEndReason,
                        GameRoomDO::getCivilianWord, GameRoomDO::getSpyWord, GameRoomDO::getCreatorId,
                        GameRoomDO::getCreateTime, GameRoomDO::getEndTime)
                .eq(GameRoomDO::getGameId, gameId)
                .last("LIMIT 1")
                .one();
        if (room == null) {
            throw new ClientException("游戏不存在");
        }
        return room;
    }

    /**
     * 查询当前局全部玩家。
     * <p>
     * 开局前按 joinTime 排序，开局后 playerOrder 会被回写到 DB，
     * 因此这里优先按 playerOrder，再退化到 joinTime，保证展示顺序稳定。
     */
    private List<GamePlayerDO> queryPlayersByGame(String gameId) {
        return this.lambdaQuery()
                .select(GamePlayerDO::getId, GamePlayerDO::getGameId, GamePlayerDO::getAccountId,
                        GamePlayerDO::getPlayerType, GamePlayerDO::getAiProvider, GamePlayerDO::getAiPersona,
                        GamePlayerDO::getNickname, GamePlayerDO::getAvatar, GamePlayerDO::getRole,
                        GamePlayerDO::getWord, GamePlayerDO::getStatus, GamePlayerDO::getPlayerOrder,
                        GamePlayerDO::getJoinTime)
                .eq(GamePlayerDO::getGameId, gameId)
                .orderByAsc(GamePlayerDO::getPlayerOrder)
                .orderByAsc(GamePlayerDO::getJoinTime)
                .orderByAsc(GamePlayerDO::getId)
                .list();
    }

    private GamePlayerDO getRequiredPlayerRecord(String gameId, Long accountId) {
        GamePlayerDO player = this.lambdaQuery()
                .select(GamePlayerDO::getId, GamePlayerDO::getGameId, GamePlayerDO::getAccountId,
                        GamePlayerDO::getPlayerType, GamePlayerDO::getAiProvider, GamePlayerDO::getAiPersona,
                        GamePlayerDO::getNickname, GamePlayerDO::getAvatar, GamePlayerDO::getRole,
                        GamePlayerDO::getWord, GamePlayerDO::getStatus, GamePlayerDO::getPlayerOrder)
                .eq(GamePlayerDO::getGameId, gameId)
                .eq(GamePlayerDO::getAccountId, accountId)
                .last("LIMIT 1")
                .one();
        if (player == null) {
            throw new ClientException("你不在该游戏中");
        }
        return player;
    }

    private List<GameRoundDO> queryRoundsByGame(String gameId) {
        return gameRoundService.lambdaQuery()
                .select(GameRoundDO::getId, GameRoundDO::getGameId, GameRoundDO::getRoundNumber,
                        GameRoundDO::getEliminatedPlayerId, GameRoundDO::getIsTie, GameRoundDO::getCreateTime)
                .eq(GameRoundDO::getGameId, gameId)
                .orderByAsc(GameRoundDO::getRoundNumber)
                .list();
    }

    private List<GameDescriptionDO> queryDescriptionsByGame(String gameId) {
        return gameDescriptionService.lambdaQuery()
                .select(GameDescriptionDO::getId, GameDescriptionDO::getGameId, GameDescriptionDO::getRoundNumber,
                        GameDescriptionDO::getPlayerId, GameDescriptionDO::getContent, GameDescriptionDO::getIsSkipped,
                        GameDescriptionDO::getSubmitTime)
                .eq(GameDescriptionDO::getGameId, gameId)
                .orderByAsc(GameDescriptionDO::getRoundNumber)
                .orderByAsc(GameDescriptionDO::getSubmitTime)
                .orderByAsc(GameDescriptionDO::getId)
                .list();
    }

    private List<GameVoteDO> queryVotesByGame(String gameId) {
        return gameVoteService.lambdaQuery()
                .select(GameVoteDO::getId, GameVoteDO::getGameId, GameVoteDO::getRoundNumber,
                        GameVoteDO::getVoterPlayerId, GameVoteDO::getTargetPlayerId,
                        GameVoteDO::getIsAuto, GameVoteDO::getVoteTime)
                .eq(GameVoteDO::getGameId, gameId)
                .orderByAsc(GameVoteDO::getRoundNumber)
                .orderByAsc(GameVoteDO::getVoteTime)
                .orderByAsc(GameVoteDO::getId)
                .list();
    }

    private void ensureWaiting(GameRoomDO room) {
        if (!GameStatusEnum.WAITING.getCode().equals(room.getGameStatus())) {
            throw new ClientException("当前游戏不处于等待阶段");
        }
    }

    private GameContext requireGameContext(String gameId) {
        GameContext context = gameContextManager.getContext(gameId);
        if (context == null) {
            throw new ServiceException("当前版本暂不支持进程重启后的游戏恢复，请重新创建游戏");
        }
        return context;
    }

    private GamePlayerInfo requirePlayerInContext(GameContext context, Long accountId) {
        GamePlayerInfo player = context.getPlayerByAccountId(accountId);
        if (player == null) {
            throw new ClientException("你不在该游戏中");
        }
        return player;
    }

    private Boolean buildMyVoted(GameContext context, Long accountId) {
        if (context == null) {
            return Boolean.FALSE;
        }
        Long playerId = context.toPlayerId(accountId);
        if (playerId == null) {
            return Boolean.FALSE;
        }
        return context.getVotes().containsKey(playerId);
    }

    /**
     * 构造当前轮公开发言记录。
     * <p>
     * 发言记录在内存中使用 synchronizedList，因此遍历前需要先做快照。
     */
    private List<GameStateRespDTO.DescriptionDTO> buildCurrentDescriptions(GameContext context) {
        if (context == null) {
            return Collections.emptyList();
        }
        List<GameContext.DescriptionRecord> snapshot;
        synchronized (context.getCurrentRoundDescriptions()) {
            snapshot = new ArrayList<>(context.getCurrentRoundDescriptions());
        }
        return snapshot.stream()
                .map(record -> GameStateRespDTO.DescriptionDTO.builder()
                        .speakerAccountId(record.getAccountId())
                        .speakerNickname(record.getNickname())
                        .content(record.getContent())
                        .isSkipped(record.isSkipped())
                        .build())
                .toList();
    }

    /**
     * 构造当前可投票目标列表。
     * <p>
     * 只有投票阶段才返回数据，避免前端误把其他阶段当成可投票状态。
     */
    private List<GameStateRespDTO.VoteTargetDTO> buildVotableTargets(GameContext context) {
        if (context == null || context.getCurrentPhase().get() != RoundPhaseEnum.VOTING) {
            return Collections.emptyList();
        }
        return context.getAlivePlayers().stream()
                .map(player -> GameStateRespDTO.VoteTargetDTO.builder()
                        .accountId(player.getAccountId())
                        .nickname(player.getNickname())
                        .status(player.getStatus() != null ? player.getStatus().getCode() : null)
                        .build())
                .toList();
    }

    private GamePlayerRespDTO toPlayerResp(GamePlayerDO player) {
        return GamePlayerRespDTO.builder()
                .accountId(player.getAccountId())
                .nickname(player.getNickname())
                .avatar(player.getAvatar())
                .playerType(player.getPlayerType())
                .aiProvider(player.getAiProvider())
                .aiPersona(player.getAiPersona())
                .status(player.getStatus())
                .playerOrder(player.getPlayerOrder())
                .build();
    }

    private GamePlayerRespDTO toPlayerResp(GamePlayerInfo player) {
        return GamePlayerRespDTO.builder()
                .accountId(player.getAccountId())
                .nickname(player.getNickname())
                .avatar(player.getAvatar())
                .playerType(player.getPlayerType() != null ? player.getPlayerType().getCode() : null)
                .aiProvider(player.getAiProvider() != null ? player.getAiProvider().getCode() : null)
                .aiPersona(player.getAiPersona() != null ? player.getAiPersona().getCode() : null)
                .status(player.getStatus() != null ? player.getStatus().getCode() : null)
                .playerOrder(player.getPlayerOrder())
                .build();
    }

    private GameResultRespDTO.PlayerResultDTO buildPlayerResult(GamePlayerDO player) {
        return GameResultRespDTO.PlayerResultDTO.builder()
                .accountId(player.getAccountId())
                .nickname(player.getNickname())
                .avatar(player.getAvatar())
                .playerType(player.getPlayerType())
                .role(player.getRole())
                .word(player.getWord())
                .finalStatus(player.getStatus())
                .playerOrder(player.getPlayerOrder())
                .build();
    }

    private GameResultRespDTO.RoundResultDTO buildRoundResult(GameRoundDO round,
                                                              Map<Long, GamePlayerDO> playerById,
                                                              List<GameDescriptionDO> descriptions,
                                                              List<GameVoteDO> votes) {
        GamePlayerDO eliminated = round.getEliminatedPlayerId() != null
                ? playerById.get(round.getEliminatedPlayerId())
                : null;

        return GameResultRespDTO.RoundResultDTO.builder()
                .roundNumber(round.getRoundNumber())
                .eliminatedAccountId(eliminated != null ? eliminated.getAccountId() : null)
                .eliminatedNickname(eliminated != null ? eliminated.getNickname() : null)
                .isTie(round.getIsTie() != null && round.getIsTie() == 1)
                .descriptions(buildRoundDescriptions(descriptions, playerById))
                .votes(buildRoundVotes(votes, playerById))
                .build();
    }

    private List<GameStateRespDTO.RoundResultDTO> buildStateRoundResults(List<GameRoundDO> rounds,
                                                                         Map<Long, GamePlayerDO> playerById) {
        if (rounds == null || rounds.isEmpty()) {
            return Collections.emptyList();
        }
        return rounds.stream()
                .map(round -> {
                    GamePlayerDO eliminated = round.getEliminatedPlayerId() != null
                            ? playerById.get(round.getEliminatedPlayerId())
                            : null;
                    return GameStateRespDTO.RoundResultDTO.builder()
                            .roundNumber(round.getRoundNumber())
                            .eliminatedAccountId(eliminated != null ? eliminated.getAccountId() : null)
                            .eliminatedNickname(eliminated != null ? eliminated.getNickname() : null)
                            .eliminatedRole(eliminated != null ? eliminated.getRole() : null)
                            .isTie(round.getIsTie() != null && round.getIsTie() == 1)
                            .build();
                })
                .toList();
    }

    private List<GameResultRespDTO.DescriptionDTO> buildRoundDescriptions(List<GameDescriptionDO> descriptions,
                                                                          Map<Long, GamePlayerDO> playerById) {
        if (descriptions == null || descriptions.isEmpty()) {
            return Collections.emptyList();
        }
        return descriptions.stream()
                .map(description -> {
                    GamePlayerDO speaker = playerById.get(description.getPlayerId());
                    return GameResultRespDTO.DescriptionDTO.builder()
                            .speakerAccountId(speaker != null ? speaker.getAccountId() : null)
                            .speakerNickname(speaker != null ? speaker.getNickname() : null)
                            .content(description.getContent())
                            .isSkipped(description.getIsSkipped() != null && description.getIsSkipped() == 1)
                            .build();
                })
                .toList();
    }

    private List<GameResultRespDTO.VoteDTO> buildRoundVotes(List<GameVoteDO> votes,
                                                            Map<Long, GamePlayerDO> playerById) {
        if (votes == null || votes.isEmpty()) {
            return Collections.emptyList();
        }
        return votes.stream()
                .map(vote -> {
                    GamePlayerDO voter = playerById.get(vote.getVoterPlayerId());
                    GamePlayerDO target = playerById.get(vote.getTargetPlayerId());
                    return GameResultRespDTO.VoteDTO.builder()
                            .voterAccountId(voter != null ? voter.getAccountId() : null)
                            .targetAccountId(target != null ? target.getAccountId() : null)
                            .isAuto(vote.getIsAuto() != null && vote.getIsAuto() == 1)
                            .build();
                })
                .toList();
    }

    private GameConfig parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return new GameConfig();
        }
        return JSON.parseObject(configJson, GameConfig.class);
    }

    private String normalizeRequiredText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ClientException(message);
        }
        return value.trim();
    }
}
