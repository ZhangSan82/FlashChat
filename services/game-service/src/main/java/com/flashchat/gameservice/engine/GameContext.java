package com.flashchat.gameservice.engine;

import com.flashchat.gameservice.config.GameConfig;
import com.flashchat.gameservice.dao.enums.GameStatusEnum;
import com.flashchat.gameservice.dao.enums.PlayerStatusEnum;
import com.flashchat.gameservice.dao.enums.RoundPhaseEnum;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单局游戏的运行时内存状态
 * <p>
 * 生命周期：创建游戏时构建 → 游戏结束时从 {@link GameContextManager} 移除。
 * <p>
 * 线程安全说明：
 * <ul>
 *   <li>gameStatus / currentPhase — AtomicReference，CAS 切换</li>
 *   <li>currentSpeakerOrder — AtomicInteger，CAS 推进</li>
 *   <li>alivePlayers — CopyOnWriteArrayList，读多写少</li>
 *   <li>votes — ConcurrentHashMap，putIfAbsent 幂等</li>
 *   <li>turnDeadline / roundStartOrder — volatile，单写多读</li>
 * </ul>
 */
@Slf4j
@Getter
public class GameContext {

    // ==================== 标识 ====================

    private final String gameId;
    private final String roomId;
    private final Long creatorId;
    private final GameConfig config;

    // ==================== 宏观状态（DB 同步） ====================

    private final AtomicReference<GameStatusEnum> gameStatus;

    // ==================== 轮内状态（纯内存） ====================

    private final AtomicReference<RoundPhaseEnum> currentPhase;
    private final AtomicInteger currentRound;
    private final AtomicInteger currentSpeakerOrder;
    private volatile long turnDeadline;
    private volatile int roundStartOrder;

    // ==================== 玩家 ====================

    /** 所有游戏玩家的 accountId（含 AI 负数 ID），用于广播过滤 */
    private final Set<Long> playerAccountIds;

    /** AI 玩家虚拟 accountId 计数器，每次 decrementAndGet 生成 -1, -2, -3... */
    private final AtomicLong aiIdCounter = new AtomicLong(0);

    /** 本轮自动投票的 playerId 集合（AI 自动投 / DISCONNECTED 自动投 / 超时补票） */
    private final Set<Long> autoVoterIds = ConcurrentHashMap.newKeySet();



    /** 全部玩家列表（按 playerOrder 排序，游戏开始后不可变） */
    private List<GamePlayerInfo> allPlayers;

    /** 存活玩家列表（动态维护，淘汰时移除） */
    private final CopyOnWriteArrayList<GamePlayerInfo> alivePlayers;

    /** 平民词 */
    private String civilianWord;

    /** 卧底词 */
    private String spyWord;

    // ==================== ID 映射 ====================

    /** accountId → t_game_player.id */
    private final Map<Long, Long> accountIdToPlayerId;

    /** t_game_player.id → accountId */
    private final Map<Long, Long> playerIdToAccountId;

    // ==================== 当前轮次数据（轮次结束后清空） ====================

    /** 本轮发言记录（有序） */
    private final List<DescriptionRecord> currentRoundDescriptions;

    /** 投票收集器：voterPlayerId → targetPlayerId */
    private final ConcurrentHashMap<Long, Long> votes;

    // ==================== 定时器 ====================

    /** 当前发言/投票的超时定时任务 */
    private volatile ScheduledFuture<?> currentTurnTimer;

    /** 掉线玩家的重连定时器：accountId → 定时任务 */
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> disconnectTimers;

    // ==================== 构造函数 ====================

    public GameContext(String gameId, String roomId, Long creatorId, GameConfig config) {
        this.gameId = gameId;
        this.roomId = roomId;
        this.creatorId = creatorId;
        this.config = config;

        this.gameStatus = new AtomicReference<>(GameStatusEnum.WAITING);
        this.currentPhase = new AtomicReference<>(null);
        this.currentRound = new AtomicInteger(0);
        this.currentSpeakerOrder = new AtomicInteger(0);

        this.playerAccountIds = ConcurrentHashMap.newKeySet();
        this.alivePlayers = new CopyOnWriteArrayList<>();
        this.allPlayers = Collections.emptyList();

        this.accountIdToPlayerId = new ConcurrentHashMap<>();
        this.playerIdToAccountId = new ConcurrentHashMap<>();

        this.currentRoundDescriptions = Collections.synchronizedList(new ArrayList<>());
        this.votes = new ConcurrentHashMap<>();
        this.disconnectTimers = new ConcurrentHashMap<>();
    }

    // ==================== 状态切换 ====================

    /**
     * CAS 切换宏观状态
     */
    public boolean casGameStatus(GameStatusEnum expected, GameStatusEnum target) {
        return gameStatus.compareAndSet(expected, target);
    }

    /**
     * CAS 切换轮内阶段
     */
    public boolean casPhase(RoundPhaseEnum expected, RoundPhaseEnum target) {
        return currentPhase.compareAndSet(expected, target);
    }

    /**
     * CAS 推进当前发言者序号
     */
    public boolean casSpeakerOrder(int expected, int target) {
        return currentSpeakerOrder.compareAndSet(expected, target);
    }

    // ==================== 玩家管理 ====================

    /**
     * 添加玩家（WAITING 阶段报名）
     */
    public void addPlayer(Long accountId) {
        playerAccountIds.add(accountId);
    }

    /**
     * 移除玩家（WAITING 阶段退出）
     */
    public void removePlayer(Long accountId) {
        playerAccountIds.remove(accountId);
        accountIdToPlayerId.remove(accountId);
        // playerIdToAccountId 在 WAITING 阶段可能还没建立映射
    }

    /**
     * 初始化玩家列表（游戏开始时调用，开始后 allPlayers 不可变）
     */
    public void initPlayers(List<GamePlayerInfo> players) {
        this.allPlayers = Collections.unmodifiableList(players);
        this.alivePlayers.clear();
        this.alivePlayers.addAll(players);

        // 构建双向映射
        for (GamePlayerInfo p : players) {
            accountIdToPlayerId.put(p.getAccountId(), p.getPlayerId());
            playerIdToAccountId.put(p.getPlayerId(), p.getAccountId());
        }
    }

    /**
     * 淘汰玩家
     */
    public void eliminatePlayer(Long playerId) {
        GamePlayerInfo player = getPlayerByPlayerId(playerId);
        if (player != null) {
            player.setStatus(PlayerStatusEnum.ELIMINATED);
            alivePlayers.remove(player);
        }
    }

    // ==================== 查询方法 ====================

    /**
     * 通过 accountId 获取玩家信息
     */
    public GamePlayerInfo getPlayerByAccountId(Long accountId) {
        Long playerId = accountIdToPlayerId.get(accountId);
        if (playerId == null) {
            return null;
        }
        return getPlayerByPlayerId(playerId);
    }

    /**
     * 通过 playerId 获取玩家信息
     */
    public GamePlayerInfo getPlayerByPlayerId(Long playerId) {
        for (GamePlayerInfo p : allPlayers) {
            if (p.getPlayerId().equals(playerId)) {
                return p;
            }
        }
        return null;
    }

    /**
     * 通过 playerOrder 获取玩家信息
     */
    public GamePlayerInfo getPlayerByOrder(int order) {
        for (GamePlayerInfo p : allPlayers) {
            if (p.getPlayerOrder() == order) {
                return p;
            }
        }
        return null;
    }

    /**
     * 获取当前应发言的玩家
     */
    public GamePlayerInfo getCurrentSpeaker() {
        return getPlayerByOrder(currentSpeakerOrder.get());
    }

    /**
     * 获取总玩家数
     */
    public int getTotalPlayerCount() {
        return playerAccountIds.size();
    }

    /**
     * 获取存活的真人玩家数
     */
    public long getAliveHumanCount() {
        return alivePlayers.stream()
                .filter(GamePlayerInfo::isHuman)
                .count();
    }

    /**
     * 获取存活的卧底数
     */
    public long getAliveSpyCount() {
        return alivePlayers.stream()
                .filter(GamePlayerInfo::isSpy)
                .count();
    }

    /**
     * 获取存活的平民数（含白板）
     */
    public long getAliveCivilianCount() {
        return alivePlayers.stream()
                .filter(p -> !p.isSpy())
                .count();
    }

    /**
     * playerId → accountId 映射
     */
    public Long toAccountId(Long playerId) {
        return playerIdToAccountId.get(playerId);
    }

    /**
     * accountId → playerId 映射
     */
    public Long toPlayerId(Long accountId) {
        return accountIdToPlayerId.get(accountId);
    }

    // ==================== 轮次管理 ====================

    /**
     * 开始新一轮
     */
    public void startNewRound() {
        currentRound.incrementAndGet();
        currentRoundDescriptions.clear();
        votes.clear();
        autoVoterIds.clear();
    }

    /**
     * 记录发言
     */
    public void addDescription(DescriptionRecord record) {
        currentRoundDescriptions.add(record);
    }

    /**
     * 投票（幂等）
     */
    public boolean castVote(Long voterPlayerId, Long targetPlayerId) {
        return votes.putIfAbsent(voterPlayerId, targetPlayerId) == null;
    }

    /**
     * 检查是否所有存活玩家都已投票
     */
    public boolean allVoted() {
        for (GamePlayerInfo p : alivePlayers) {
            if (!votes.containsKey(p.getPlayerId())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 生成下一个 AI 玩家的虚拟 accountId
     *
     * @return 负数 ID：-1, -2, -3...
     */
    public long nextAiAccountId() {
        return aiIdCounter.decrementAndGet();
    }

    /**
     * 标记某玩家本轮为自动投票
     */
    public void markAutoVoter(Long playerId) {
        autoVoterIds.add(playerId);
    }

    /**
     * 判断某玩家本轮是否为自动投票
     */
    public boolean isAutoVoter(Long playerId) {
        return autoVoterIds.contains(playerId);
    }


    // ==================== 定时器管理 ====================

    public void setCurrentTurnTimer(ScheduledFuture<?> timer) {
        this.currentTurnTimer = timer;
    }

    public void cancelCurrentTurnTimer() {
        ScheduledFuture<?> timer = this.currentTurnTimer;
        if (timer != null && !timer.isDone()) {
            timer.cancel(false);
        }
        this.currentTurnTimer = null;
    }

    public void setTurnDeadline(long deadline) {
        this.turnDeadline = deadline;
    }

    public void setRoundStartOrder(int order) {
        this.roundStartOrder = order;
    }

    public void setCivilianWord(String word) {
        this.civilianWord = word;
    }

    public void setSpyWord(String word) {
        this.spyWord = word;
    }

    // ==================== 发言记录内部类 ====================

    /**
     * 轮内发言记录（内存态，轮次结束后批量落库）
     */
    @Getter
    public static class DescriptionRecord {
        private final Long playerId;
        private final Long accountId;
        private final String nickname;
        private final String content;
        private final boolean skipped;
        private final long timestamp;

        public DescriptionRecord(Long playerId, Long accountId, String nickname,
                                 String content, boolean skipped) {
            this.playerId = playerId;
            this.accountId = accountId;
            this.nickname = nickname;
            this.content = content;
            this.skipped = skipped;
            this.timestamp = System.currentTimeMillis();
        }
    }
}