package com.flashchat.gameservice.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 游戏配置对象
 * <p>
 * 以 JSON 格式存储在 t_game_room.config 字段中。
 * <p>
 * 所有字段使用 Integer 包装类型，null 表示"未配置，使用默认值"。
 * getter 方法内置默认值兜底，调用方直接用 getter 即可，无需关心 null。
 * <p>
 * 前端可传任意子集的字段，后端原样序列化存入 DB。
 * 读取时通过 getter 的默认值兜底，保证业务逻辑始终有值可用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameConfig {

    private static final int DEFAULT_DESCRIBE_TIMEOUT = 60;
    private static final int DEFAULT_VOTE_TIMEOUT = 30;
    private static final int DEFAULT_MAX_PLAYERS = 10;
    private static final int DEFAULT_MIN_PLAYERS = 4;
    private static final int DEFAULT_MAX_AI_PLAYERS = 4;

    /**
     * 卧底数量
     * <p>
     * null 或 0 = 自动计算
     * <ul>
     *   <li>4-6 人：1 个卧底</li>
     *   <li>7-9 人：2 个卧底</li>
     *   <li>10 人：2 个卧底</li>
     * </ul>
     * 正数 = 指定数量。
     */
    private Integer spyCount;

    /**
     * 是否启用白板角色
     * <p>
     * null = false（不启用）。MVP 阶段固定不启用。
     */
    private Boolean enableBlank;

    /** 发言时限（秒），null = 60 */
    private Integer describeTimeout;

    /** 投票时限（秒），null = 30 */
    private Integer voteTimeout;

    /** 最大玩家数（含 AI），null = 10 */
    private Integer maxPlayers;

    /** 最小玩家数（含 AI），null = 4 */
    private Integer minPlayers;

    /**
     * 词库分类筛选
     * <p>
     * null = 不限分类，随机抽取。
     */
    private String wordCategory;

    /**
     * 词库难度筛选
     * <p>
     * null 或 0 = 不限难度。1-3 = 指定难度。
     */
    private Integer difficulty;

    /**
     * 最大 AI 玩家数量
     * <p>
     * null = 4。设为 0 表示不允许添加 AI。
     */
    private Integer maxAiPlayers;

    // ==================== 带默认值的 getter ====================

    public int getSpyCountOrDefault() {
        return spyCount != null ? spyCount : 0;
    }

    public boolean isEnableBlankOrDefault() {
        return enableBlank != null && enableBlank;
    }

    public int getDescribeTimeoutOrDefault() {
        return describeTimeout != null ? describeTimeout : DEFAULT_DESCRIBE_TIMEOUT;
    }

    public int getVoteTimeoutOrDefault() {
        return voteTimeout != null ? voteTimeout : DEFAULT_VOTE_TIMEOUT;
    }

    public int getMaxPlayersOrDefault() {
        return maxPlayers != null ? maxPlayers : DEFAULT_MAX_PLAYERS;
    }

    public int getMinPlayersOrDefault() {
        return minPlayers != null ? minPlayers : DEFAULT_MIN_PLAYERS;
    }

    public int getDifficultyOrDefault() {
        return difficulty != null ? difficulty : 0;
    }

    public int getMaxAiPlayersOrDefault() {
        return maxAiPlayers != null ? maxAiPlayers : DEFAULT_MAX_AI_PLAYERS;
    }

    /**
     * 根据玩家总数计算卧底数量
     */
    public int resolveSpyCount(int totalPlayers) {
        int configured = getSpyCountOrDefault();
        if (configured > 0) {
            return configured;
        }
        if (totalPlayers <= 6) {
            return 1;
        }
        if (totalPlayers <= 9) {
            return 2;
        }
        return 2;
    }
}
