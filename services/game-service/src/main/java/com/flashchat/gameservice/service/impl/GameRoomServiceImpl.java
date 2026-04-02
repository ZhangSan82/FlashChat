package com.flashchat.gameservice.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashchat.channel.ChannelPushService;
import com.flashchat.channel.ChannelQueryService;
import com.flashchat.convention.exception.ClientException;
import com.flashchat.convention.exception.ServiceException;
import com.flashchat.gameservice.config.GameConfig;
import com.flashchat.gameservice.constant.GameWsEventType;
import com.flashchat.gameservice.dao.entity.GamePlayerDO;
import com.flashchat.gameservice.dao.entity.GameRoomDO;
import com.flashchat.gameservice.dao.enums.AiPersonaEnum;
import com.flashchat.gameservice.dao.enums.AiProviderEnum;
import com.flashchat.gameservice.dao.enums.EndReasonEnum;
import com.flashchat.gameservice.dao.enums.GameStatusEnum;
import com.flashchat.gameservice.dao.enums.PlayerStatusEnum;
import com.flashchat.gameservice.dao.enums.PlayerTypeEnum;
import com.flashchat.gameservice.dao.mapper.GameRoomMapper;
import com.flashchat.gameservice.dto.req.AddAiPlayerReqDTO;
import com.flashchat.gameservice.dto.req.CancelGameReqDTO;
import com.flashchat.gameservice.dto.req.CreateGameReqDTO;
import com.flashchat.gameservice.dto.req.JoinGameReqDTO;
import com.flashchat.gameservice.dto.req.LeaveGameReqDTO;
import com.flashchat.gameservice.dto.resp.GameInfoRespDTO;
import com.flashchat.gameservice.dto.resp.GamePlayerRespDTO;
import com.flashchat.gameservice.engine.GameActionLockManager;
import com.flashchat.gameservice.engine.GameContext;
import com.flashchat.gameservice.engine.GameContextManager;
import com.flashchat.gameservice.service.GamePlayerService;
import com.flashchat.gameservice.service.GameRoomService;
import com.flashchat.user.core.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 游戏房间服务实现。
 * <p>
 * 这一层只负责等待阶段的房间管理：
 * 1. 创建游戏
 * 2. 玩家加入
 * 3. 玩家退出
 * 4. 创建者取消
 * 5. 添加 AI 玩家
 * <p>
 * 这里刻意不处理开局、发言、投票等玩法逻辑，那些统一留给 GamePlayService / Engine。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameRoomServiceImpl extends ServiceImpl<GameRoomMapper, GameRoomDO>
        implements GameRoomService {

    private static final String GAME_TYPE_WHO_IS_SPY = "WHO_IS_SPY";
    private static final String DEFAULT_AI_AVATAR = "#5B8FF9";

    private final GamePlayerService gamePlayerService;
    private final GameContextManager gameContextManager;
    private final GameActionLockManager gameActionLockManager;
    private final ChannelQueryService channelQueryService;
    private final ChannelPushService channelPushService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GameInfoRespDTO createGame(CreateGameReqDTO request) {
        Long creatorId = UserContext.getRequiredLoginId();
        String roomId = normalizeRequiredText(request.getRoomId(), "房间 ID 不能为空");
        String nickname = normalizeRequiredText(request.getNickname(), "昵称不能为空");
        String avatar = normalizeOptionalText(request.getAvatar());

        // 只有聊天房间成员才允许在该房间创建游戏
        ensureRoomMember(roomId, creatorId);
        // 同一账号同一时刻只允许参与一局游戏
        ensureNotInOtherGame(creatorId, null);
        GameConfig resolvedConfig = resolveAndValidateConfig(request.getConfig());
        String lockKey = "room:" + roomId;
        try (GameActionLockManager.LockHandle lockHandle = gameActionLockManager.lock(lockKey)) {
            lockHandle.deferUnlockAfterTransaction();
            ensureRoomHasNoActiveGame(roomId);
            String gameId = generateGameId();
            LocalDateTime now = LocalDateTime.now();
            GameRoomDO room = GameRoomDO.builder()
                    .gameId(gameId)
                    .roomId(roomId)
                    .gameType(GAME_TYPE_WHO_IS_SPY)
                    .gameStatus(GameStatusEnum.WAITING.getCode())
                    .currentRound(0)
                    .config(JSON.toJSONString(resolvedConfig))
                    .creatorId(creatorId)
                    .createTime(now)
                    .build();
            GamePlayerDO creatorPlayer = GamePlayerDO.builder()
                    .gameId(gameId)
                    .accountId(creatorId)
                    .playerType(PlayerTypeEnum.HUMAN.getCode())
                    .nickname(nickname)
                    .avatar(avatar)
                    .status(PlayerStatusEnum.ALIVE.getCode())
                    .joinTime(now)
                    .build();
            if (!this.save(room)) {
                throw new ServiceException("创建游戏失败，请稍后重试");
            }
            if (!gamePlayerService.save(creatorPlayer)) {
                throw new ServiceException("创建游戏失败，请稍后重试");
            }
            GameInfoRespDTO response = buildGameInfoResp(room, resolvedConfig, List.of(creatorPlayer));
            registerAfterCommit(
                    () -> {
                        try {
                            GameContext context = new GameContext(gameId, roomId, creatorId, resolvedConfig);
                            context.addPlayer(creatorId);
                            gameContextManager.register(context);
                            gameContextManager.bindPlayer(creatorId, gameId);
                        } catch (Exception e) {
                            // 补偿：清内存 + DB标记结束，不打日志
                            gameContextManager.remove(gameId);
                            compensateGameToEnded(gameId);
                            throw e;
                        }
                    },
                    () -> channelPushService.broadcastToRoom(roomId, GameWsEventType.GAME_CREATED, response)
            );
            log.info("[GameRoom] 创建游戏成功 gameId={}, roomId={}, creatorId={}",
                    gameId, roomId, creatorId);
            return response;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GameInfoRespDTO joinGame(JoinGameReqDTO request) {
        Long accountId = UserContext.getRequiredLoginId();
        String gameId = normalizeRequiredText(request.getGameId(), "游戏 ID 不能为空");
        String nickname = normalizeRequiredText(request.getNickname(), "昵称不能为空");
        String avatar = normalizeOptionalText(request.getAvatar());
        ensureNotInOtherGame(accountId, gameId);
        String lockKey = "game:" + gameId;
        try (GameActionLockManager.LockHandle lockHandle = gameActionLockManager.lock(lockKey)) {
            lockHandle.deferUnlockAfterTransaction();
            GameRoomDO room = getRequiredGameRoom(gameId);
            ensureWaiting(room);
            ensureRoomMember(room.getRoomId(), accountId);

            GameContext context = requireGameContext(gameId);
            if (context.getPlayerAccountIds().contains(accountId)) {
                // 幂等处理：重复加入直接返回当前局信息，避免重复插入
                return getGameInfo(gameId);
            }

            int currentPlayerCount = context.getPlayerAccountIds().size();
            int maxPlayers = context.getConfig().getMaxPlayersOrDefault();
            if (currentPlayerCount >= maxPlayers) {
                throw new ClientException("当前游戏人数已满");
            }

            GamePlayerDO newPlayer = GamePlayerDO.builder()
                    .gameId(gameId)
                    .accountId(accountId)
                    .playerType(PlayerTypeEnum.HUMAN.getCode())
                    .nickname(nickname)
                    .avatar(avatar)
                    .status(PlayerStatusEnum.ALIVE.getCode())
                    .joinTime(LocalDateTime.now())
                    .build();
            if (!gamePlayerService.save(newPlayer)) {
                throw new ServiceException("加入游戏失败，请稍后重试");
            }

            List<GamePlayerDO> players = queryPlayersByGame(gameId);
            GameInfoRespDTO response = buildGameInfoResp(room, context.getConfig(), players);
            List<Long> broadcastTargets = context.getPlayerAccountIds().stream()
                    .filter(id -> id > 0)
                    .toList();
            registerAfterCommit(
                    () -> {
                        try {
                            context.addPlayer(accountId);
                            gameContextManager.bindPlayer(accountId, gameId);
                        } catch (Exception e) {
                            // 补偿：清内存 + 删DB残留记录
                            context.removePlayer(accountId);
                            gameContextManager.unbindPlayer(accountId, gameId);
                            compensateRemovePlayer(gameId, accountId);
                            throw e;
                        }
                    },
                    () -> channelPushService.sendToUsers(
                            broadcastTargets,
                            GameWsEventType.GAME_PLAYER_JOINED,
                            room.getRoomId(),
                            buildPlayerChangedPayload(gameId, newPlayer, players.size()))
            );
            log.info("[GameRoom] 加入游戏成功 gameId={}, accountId={}", gameId, accountId);
            return response;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void leaveGame(LeaveGameReqDTO request) {
        Long accountId = UserContext.getRequiredLoginId();
        String gameId = normalizeRequiredText(request.getGameId(), "游戏 ID 不能为空");

        String lockKey = "game:" + gameId;
        try (GameActionLockManager.LockHandle lockHandle = gameActionLockManager.lock(lockKey)) {
            lockHandle.deferUnlockAfterTransaction();
            GameRoomDO room = getRequiredGameRoom(gameId);
            ensureWaiting(room);

            GamePlayerDO player = getGamePlayer(gameId, accountId);
            if (player == null) {
                return;
            }
            if (accountId.equals(room.getCreatorId())) {
                throw new ClientException("创建者不能退出游戏，如需退出请取消游戏");
            }

            boolean removed = gamePlayerService.remove(new LambdaQueryWrapper<GamePlayerDO>()
                    .eq(GamePlayerDO::getGameId, gameId)
                    .eq(GamePlayerDO::getAccountId, accountId));
            if (!removed) {
                return;
            }

            GameContext context = requireGameContext(gameId);
            List<Long> broadcastTargets = context.getPlayerAccountIds().stream()
                    .filter(id -> id > 0 && !id.equals(accountId))
                    .toList();
            int remainingPlayerCount = context.getPlayerAccountIds().size() - 1;

            registerAfterCommit(
                    () -> {
                        try {
                            context.removePlayer(accountId);
                            gameContextManager.unbindPlayer(accountId, gameId);
                        } catch (Exception e) {
                            // 保守处理：确保反向索引被清掉，否则玩家永久锁死
                            try {
                                gameContextManager.unbindPlayer(accountId, gameId);
                            } catch (Exception ignored) {
                            }
                            throw e;
                        }
                    },
                    () -> channelPushService.sendToUsers(
                            broadcastTargets,
                            GameWsEventType.GAME_PLAYER_LEFT,
                            room.getRoomId(),
                            buildPlayerChangedPayload(gameId, player, remainingPlayerCount))
            );

            log.info("[GameRoom] 退出游戏成功 gameId={}, accountId={}", gameId, accountId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelGame(CancelGameReqDTO request) {
        Long operatorId = UserContext.getRequiredLoginId();
        String gameId = normalizeRequiredText(request.getGameId(), "游戏 ID 不能为空");

        String lockKey = "game:" + gameId;
        try (GameActionLockManager.LockHandle lockHandle = gameActionLockManager.lock(lockKey)) {
            lockHandle.deferUnlockAfterTransaction();
            GameRoomDO room = getRequiredGameRoom(gameId);
            ensureWaiting(room);
            if (!operatorId.equals(room.getCreatorId())) {
                throw new ClientException("只有创建者可以取消游戏");
            }

            List<Long> participantIds = queryPlayersByGame(gameId).stream()
                    .map(GamePlayerDO::getAccountId)
                    .filter(id -> id > 0)
                    .toList();

            boolean updated = this.update(new LambdaUpdateWrapper<GameRoomDO>()
                    .eq(GameRoomDO::getGameId, gameId)
                    .eq(GameRoomDO::getGameStatus, GameStatusEnum.WAITING.getCode())
                    .set(GameRoomDO::getGameStatus, GameStatusEnum.ENDED.getCode())
                    .set(GameRoomDO::getEndReason, EndReasonEnum.CANCELLED.getCode())
                    .set(GameRoomDO::getEndTime, LocalDateTime.now()));
            if (!updated) {
                throw new ServiceException("取消游戏失败，请稍后重试");
            }
            registerAfterCommit(
                    () -> {
                        try {
                            gameContextManager.remove(gameId);
                        } catch (Exception e) {
                            // remove 本质是 ConcurrentHashMap.remove，几乎不会失败
                            // 但如果真的失败，重试一次
                            try {
                                gameContextManager.remove(gameId);
                            } catch (Exception ignored) {
                            }
                            throw e;
                        }
                    },
                    () -> channelPushService.sendToUsers(
                            participantIds,
                            GameWsEventType.GAME_CANCELLED,
                            room.getRoomId(),
                            buildCancelPayload(gameId))
            );

            log.info("[GameRoom] 取消游戏成功 gameId={}, operatorId={}", gameId, operatorId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GameInfoRespDTO addAiPlayer(AddAiPlayerReqDTO request) {
        Long operatorId = UserContext.getRequiredLoginId();
        String gameId = normalizeRequiredText(request.getGameId(), "游戏 ID 不能为空");

        String lockKey = "game:" + gameId;
        try (GameActionLockManager.LockHandle lockHandle = gameActionLockManager.lock(lockKey)) {
            lockHandle.deferUnlockAfterTransaction();
            GameRoomDO room = getRequiredGameRoom(gameId);
            ensureWaiting(room);
            if (!operatorId.equals(room.getCreatorId())) {
                throw new ClientException("只有创建者可以添加 AI 玩家");
            }

            GameContext context = requireGameContext(gameId);
            int currentPlayerCount = context.getPlayerAccountIds().size();
            int maxPlayers = context.getConfig().getMaxPlayersOrDefault();
            if (currentPlayerCount >= maxPlayers) {
                throw new ClientException("当前游戏人数已满");
            }

            long currentAiCount = context.getPlayerAccountIds().stream()
                    .filter(id -> id < 0)
                    .count();
            if (currentAiCount >= context.getConfig().getMaxAiPlayersOrDefault()) {
                throw new ClientException("已达到当前配置允许的最大 AI 数量");
            }

            AiProviderEnum provider = requireAiProvider(request.getProvider());
            AiPersonaEnum persona = requireAiPersona(request.getPersona());

            long aiAccountId = context.nextAiAccountId();
            GamePlayerDO aiPlayer = GamePlayerDO.builder()
                    .gameId(gameId)
                    .accountId(aiAccountId)
                    .playerType(PlayerTypeEnum.AI.getCode())
                    .aiProvider(provider.getCode())
                    .aiPersona(persona.getCode())
                    .nickname(buildAiNickname(provider, currentAiCount + 1))
                    .avatar(DEFAULT_AI_AVATAR)
                    .status(PlayerStatusEnum.ALIVE.getCode())
                    .joinTime(LocalDateTime.now())
                    .build();
            if (!gamePlayerService.save(aiPlayer)) {
                throw new ServiceException("添加 AI 失败，请稍后重试");
            }

            List<GamePlayerDO> players = queryPlayersByGame(gameId);
            GameInfoRespDTO response = buildGameInfoResp(room, context.getConfig(), players);
            List<Long> broadcastTargets = context.getPlayerAccountIds().stream()
                    .filter(id -> id > 0)
                    .toList();
            registerAfterCommit(
                    () -> {
                        try {
                            context.addPlayer(aiAccountId);
                            gameContextManager.bindPlayer(aiAccountId, gameId);
                        } catch (Exception e) {
                            // 补偿：清内存 + 删DB残留记录
                            context.removePlayer(aiAccountId);
                            gameContextManager.unbindPlayer(aiAccountId, gameId);
                            compensateRemovePlayer(gameId, aiAccountId);
                            throw e;
                        }
                    },
                    () -> channelPushService.sendToUsers(
                            broadcastTargets,
                            GameWsEventType.GAME_PLAYER_JOINED,
                            room.getRoomId(),
                            buildPlayerChangedPayload(gameId, aiPlayer, players.size()))
            );

            log.info("[GameRoom] 添加 AI 成功 gameId={}, provider={}, persona={}, aiAccountId={}",
                    gameId, provider.getCode(), persona.getCode(), aiAccountId);
            return response;
        }
    }

    @Override
    public GameInfoRespDTO getGameInfo(String gameId) {
        String normalizedGameId = normalizeRequiredText(gameId, "游戏 ID 不能为空");
        GameRoomDO room = getRequiredGameRoom(normalizedGameId);
        GameConfig config = parseConfig(room.getConfig());
        List<GamePlayerDO> players = queryPlayersByGame(normalizedGameId);
        return buildGameInfoResp(room, config, players);
    }

    /**
     * 解析并补齐配置默认值。
     * <p>
     * 这里会把“前端未传”的字段补成完整值再落库，
     * 避免后续读配置时重复做默认值兜底逻辑。
     */
    private GameConfig resolveAndValidateConfig(GameConfig incoming) {
        GameConfig source = incoming == null ? new GameConfig() : incoming;
        GameConfig resolved = GameConfig.builder()
                .spyCount(source.getSpyCountOrDefault())
                .enableBlank(false)
                .describeTimeout(source.getDescribeTimeoutOrDefault())
                .voteTimeout(source.getVoteTimeoutOrDefault())
                .maxPlayers(source.getMaxPlayersOrDefault())
                .minPlayers(source.getMinPlayersOrDefault())
                .wordCategory(normalizeOptionalText(source.getWordCategory()))
                .difficulty(source.getDifficultyOrDefault())
                .maxAiPlayers(source.getMaxAiPlayersOrDefault())
                .build();

        if (Boolean.TRUE.equals(source.getEnableBlank())) {
            throw new ClientException("当前版本暂不支持白板模式");
        }
        if (resolved.getSpyCount() != null && resolved.getSpyCount() < 0) {
            throw new ClientException("卧底数量不能小于 0");
        }
        if (resolved.getDescribeTimeout() == null || resolved.getDescribeTimeout() <= 0) {
            throw new ClientException("发言时限必须大于 0");
        }
        if (resolved.getVoteTimeout() == null || resolved.getVoteTimeout() <= 0) {
            throw new ClientException("投票时限必须大于 0");
        }
        if (resolved.getMinPlayers() == null || resolved.getMinPlayers() < 4) {
            throw new ClientException("最小玩家数不能小于 4");
        }
        if (resolved.getMaxPlayers() == null || resolved.getMaxPlayers() > 10) {
            throw new ClientException("最大玩家数不能大于 10");
        }
        if (resolved.getMinPlayers() > resolved.getMaxPlayers()) {
            throw new ClientException("最小玩家数不能大于最大玩家数");
        }
        if (resolved.getDifficulty() == null || resolved.getDifficulty() < 0 || resolved.getDifficulty() > 3) {
            throw new ClientException("词库难度只能为 0 到 3");
        }
        if (resolved.getMaxAiPlayers() == null || resolved.getMaxAiPlayers() < 0) {
            throw new ClientException("最大 AI 数量不能小于 0");
        }
        if (resolved.getMaxAiPlayers() >= resolved.getMaxPlayers()) {
            throw new ClientException("最大 AI 数量必须小于最大玩家数");
        }
        if (resolved.getSpyCount() != null && resolved.getSpyCount() > 0
                && resolved.getSpyCount() > resolved.getMaxPlayers() - 2) {
            throw new ClientException("卧底数量过多，至少需要保留 2 个平民");
        }
        return resolved;
    }

    /**
     * 查询游戏主记录。
     * <p>
     * 这里只 select 本阶段会用到的字段，减少无意义列加载。
     */
    private GameRoomDO getRequiredGameRoom(String gameId) {
        GameRoomDO room = this.lambdaQuery()
                .select(GameRoomDO::getId, GameRoomDO::getGameId, GameRoomDO::getRoomId,
                        GameRoomDO::getGameType, GameRoomDO::getGameStatus, GameRoomDO::getCurrentRound,
                        GameRoomDO::getConfig, GameRoomDO::getCreatorId, GameRoomDO::getCreateTime)
                .eq(GameRoomDO::getGameId, gameId)
                .last("LIMIT 1")
                .one();
        if (room == null) {
            throw new ClientException("游戏不存在");
        }
        return room;
    }

    /**
     * 查询当前局玩家列表。
     * <p>
     * 按 joinTime + id 排序，保证等待阶段展示顺序稳定。
     */
    private List<GamePlayerDO> queryPlayersByGame(String gameId) {
        return gamePlayerService.lambdaQuery()
                .select(GamePlayerDO::getId, GamePlayerDO::getGameId, GamePlayerDO::getAccountId,
                        GamePlayerDO::getPlayerType, GamePlayerDO::getAiProvider, GamePlayerDO::getAiPersona,
                        GamePlayerDO::getNickname, GamePlayerDO::getAvatar, GamePlayerDO::getStatus,
                        GamePlayerDO::getPlayerOrder, GamePlayerDO::getJoinTime)
                .eq(GamePlayerDO::getGameId, gameId)
                .orderByAsc(GamePlayerDO::getJoinTime)
                .orderByAsc(GamePlayerDO::getId)
                .list();
    }

    /**
     * 查询指定账号在某局游戏中的玩家记录。
     */
    private GamePlayerDO getGamePlayer(String gameId, Long accountId) {
        return gamePlayerService.lambdaQuery()
                .select(GamePlayerDO::getId, GamePlayerDO::getGameId, GamePlayerDO::getAccountId,
                        GamePlayerDO::getPlayerType, GamePlayerDO::getAiProvider, GamePlayerDO::getAiPersona,
                        GamePlayerDO::getNickname, GamePlayerDO::getAvatar, GamePlayerDO::getStatus,
                        GamePlayerDO::getPlayerOrder, GamePlayerDO::getJoinTime)
                .eq(GamePlayerDO::getGameId, gameId)
                .eq(GamePlayerDO::getAccountId, accountId)
                .last("LIMIT 1")
                .one();
    }

    private GameInfoRespDTO buildGameInfoResp(GameRoomDO room, GameConfig config, List<GamePlayerDO> players) {
        List<GamePlayerRespDTO> playerDTOs = players.stream().map(this::toPlayerResp).toList();
        return GameInfoRespDTO.builder()
                .gameId(room.getGameId())
                .roomId(room.getRoomId())
                .gameType(room.getGameType())
                .gameStatus(room.getGameStatus())
                .currentRound(room.getCurrentRound())
                .playerCount(playerDTOs.size())
                .maxPlayers(config.getMaxPlayersOrDefault())
                .minPlayers(config.getMinPlayersOrDefault())
                .creatorId(room.getCreatorId())
                .createTime(room.getCreateTime())
                .players(playerDTOs)
                .build();
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

    private void ensureWaiting(GameRoomDO room) {
        if (!GameStatusEnum.WAITING.getCode().equals(room.getGameStatus())) {
            throw new ClientException("当前游戏不处于等待阶段");
        }
    }

    private void ensureRoomMember(String roomId, Long accountId) {
        if (!channelQueryService.isRoomMember(roomId, accountId)) {
            throw new ClientException("你不在该聊天房间中，无法操作游戏");
        }
    }

    private void ensureNotInOtherGame(Long accountId, String currentGameId) {
        GameContext existing = gameContextManager.getContextByAccountId(accountId);
        if (existing == null) {
            return;
        }
        if (currentGameId != null && currentGameId.equals(existing.getGameId())) {
            return;
        }
        throw new ClientException("你当前已在其他游戏中，暂不能重复参与");
    }

    /**
     * 同一个房间同一时刻只允许存在一局未结束游戏。
     * <p>
     * 这里同时检查内存和 DB，避免只靠其中一层导致脏数据穿透。
     */
    private void ensureRoomHasNoActiveGame(String roomId) {
        if (gameContextManager.getActiveContextByRoomId(roomId) != null) {
            throw new ClientException("当前房间已经存在一局未结束的游戏");
        }
        GameRoomDO dbRoom = this.lambdaQuery()
                .select(GameRoomDO::getId, GameRoomDO::getGameId)
                .eq(GameRoomDO::getRoomId, roomId)
                .ne(GameRoomDO::getGameStatus, GameStatusEnum.ENDED.getCode())
                .last("LIMIT 1")
                .one();
        if (dbRoom != null) {
            throw new ClientException("当前房间已经存在一局未结束的游戏");
        }
    }

    private GameContext requireGameContext(String gameId) {
        GameContext context = gameContextManager.getContext(gameId);
        if (context == null) {
            throw new ServiceException("当前版本暂不支持进程重启后的游戏恢复，请重新创建游戏");
        }
        return context;
    }

    private GameConfig parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return new GameConfig();
        }
        return JSON.parseObject(configJson, GameConfig.class);
    }

    private AiProviderEnum requireAiProvider(String providerCode) {
        String normalized = normalizeRequiredText(providerCode, "AI 厂商不能为空");
        AiProviderEnum provider = AiProviderEnum.of(normalized);
        if (provider == null) {
            throw new ClientException("不支持的 AI 厂商");
        }
        if (provider != AiProviderEnum.KIMI) {
            throw new ClientException("当前版本仅支持 KIMI AI");
        }
        return provider;
    }

    private AiPersonaEnum requireAiPersona(String personaCode) {
        String normalized = normalizeRequiredText(personaCode, "AI 性格不能为空");
        AiPersonaEnum persona = AiPersonaEnum.of(normalized);
        if (persona == null) {
            throw new ClientException("不支持的 AI 性格");
        }
        return persona;
    }

    private String buildAiNickname(AiProviderEnum provider, long sequence) {
        return provider.getCode() + "-AI-" + sequence;
    }

    private Map<String, Object> buildPlayerChangedPayload(String gameId, GamePlayerDO player, int playerCount) {
        Map<String, Object> payload = new LinkedHashMap<>(6);
        payload.put("gameId", gameId);
        payload.put("accountId", player.getAccountId());
        payload.put("nickname", player.getNickname());
        payload.put("avatar", player.getAvatar());
        payload.put("playerType", player.getPlayerType());
        payload.put("playerCount", playerCount);
        return payload;
    }

    private Map<String, Object> buildCancelPayload(String gameId) {
        Map<String, Object> payload = new LinkedHashMap<>(2);
        payload.put("gameId", gameId);
        payload.put("message", "游戏已取消");
        return payload;
    }

    private String generateGameId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 事务提交后再执行内存更新和广播。
     * <p>
     * 这样可以避免数据库回滚时，GameContext 或前端事件已经提前生效。
     */
    private void registerAfterCommit(Runnable critical, Runnable... extras) {
        Runnable safeRun = () -> {
            try {
                critical.run();
            } catch (Exception e) {
                // 统一在这里打日志，关键任务内部不重复打
                log.error("[GameRoom] 关键任务执行失败，已执行补偿，跳过后续任务", e);
                return;
            }
            for (Runnable extra : extras) {
                try {
                    extra.run();
                } catch (Exception e) {
                    log.error("[GameRoom] 非关键任务执行失败", e);
                }
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeRun.run();
                }
            });
            return;
        }
        safeRun.run();
    }

    /**
     * 补偿：将游戏标记为异常结束
     */
    private void compensateGameToEnded(String gameId) {
        try {
            lambdaUpdate()
                    .eq(GameRoomDO::getGameId, gameId)
                    .set(GameRoomDO::getGameStatus, GameStatusEnum.ENDED.getCode())
                    .set(GameRoomDO::getEndReason, EndReasonEnum.ERROR.getCode())
                    .set(GameRoomDO::getEndTime, LocalDateTime.now())
                    .update();
        } catch (Exception e) {
            log.error("[GameRoom] 补偿标记游戏结束失败 gameId={}", gameId, e);
        }
    }

    /**
     * 补偿：删除已提交的玩家记录
     */
    private void compensateRemovePlayer(String gameId, Long accountId) {
        try {
            gamePlayerService.remove(new LambdaQueryWrapper<GamePlayerDO>()
                    .eq(GamePlayerDO::getGameId, gameId)
                    .eq(GamePlayerDO::getAccountId, accountId));
        } catch (Exception e) {
            log.error("[GameRoom] 补偿删除玩家失败 gameId={}, accountId={}", gameId, accountId, e);
        }
    }

    private String normalizeRequiredText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ClientException(message);
        }
        return value.trim();
    }

    private String normalizeOptionalText(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }

}
