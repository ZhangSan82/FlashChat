package com.flashchat.gameservice.constant;

/**
 * 游戏模块 WebSocket 事件类型常量
 * <p>
 * 编号空间分配：
 * <ul>
 *   <li>chat-service：0-19</li>
 *   <li>game-service：20-49（当前使用 20-33，预留 34-49 供扩展）</li>
 * </ul>
 * <p>
 * 前端统一按 type 数值分发处理，不依赖后端枚举类型。
 */
public final class GameWsEventType {

    private GameWsEventType() {
    }

    /** 游戏已创建（推给聊天房间全员，展示报名入口） */
    public static final int GAME_CREATED = 20;

    /** 玩家加入游戏（推给游戏玩家） */
    public static final int GAME_PLAYER_JOINED = 21;

    /** 游戏开始（推给游戏玩家，含玩家列表和发言顺序） */
    public static final int GAME_STARTED = 22;

    /** 角色分配（私发给个人，含角色和词语） */
    public static final int GAME_ROLE_ASSIGNED = 23;

    /** 轮到某人发言（推给游戏玩家，含倒计时） */
    public static final int GAME_TURN_START = 24;

    /** 玩家发言内容（推给游戏玩家） */
    public static final int GAME_DESCRIPTION = 25;

    /** AI 正在思考（推给游戏玩家，前端显示思考动画） */
    public static final int GAME_AI_THINKING = 26;

    /** 投票阶段开始（推给游戏玩家，含可投票目标列表） */
    public static final int GAME_VOTE_PHASE = 27;

    /** 投票结果（推给游戏玩家，含票数统计和淘汰信息） */
    public static final int GAME_VOTE_RESULT = 28;

    /** 游戏结束（推给游戏玩家，含胜负和全部角色公布） */
    public static final int GAME_ENDED = 29;

    /** 玩家退出游戏（推给游戏玩家，WAITING 阶段） */
    public static final int GAME_PLAYER_LEFT = 30;

    /** 游戏取消（推给游戏玩家，WAITING 阶段创建者取消） */
    public static final int GAME_CANCELLED = 31;

    /** 玩家掉线通知（推给游戏玩家） */
    public static final int GAME_PLAYER_DISCONNECTED = 32;

    /** 玩家重连通知（推给游戏玩家） */
    public static final int GAME_PLAYER_RECONNECTED = 33;
}