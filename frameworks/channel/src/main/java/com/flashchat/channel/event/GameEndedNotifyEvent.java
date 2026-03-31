package com.flashchat.channel.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 游戏结束通知事件（game-service → chat-service）
 * chat-service 监听此事件后：
 * 往对应聊天房间的消息流中插入一条系统消息，
 * 通知房间内所有人（包括未参与游戏的观众）游戏结果。
 * <p>
 * 系统消息示例："谁是卧底结束！平民胜利！卧底是 xxx。平民词：苹果，卧底词：梨"
 */
@Getter
public class GameEndedNotifyEvent extends ApplicationEvent {

    /** 聊天房间 ID（chat-service 用此 ID 插入系统消息） */
    private final String roomId;

    /** 游戏 ID */
    private final String gameId;

    /** 系统消息内容（由 game-service 组装好，chat-service 直接使用） */
    private final String summaryMessage;

    /**
     * 胜利方标识
     * <p>
     * "CIVILIAN" / "SPY" = 正常结束有胜负，前端可据此差异化渲染（如颜色区分）。
     * null = 异常结束（房间到期、全员掉线、手动取消等），无胜负。
     */
    private final String winnerSide;

    public GameEndedNotifyEvent(Object source, String roomId,
                                String gameId, String summaryMessage,
                                String winnerSide) {
        super(source);
        this.roomId = roomId;
        this.gameId = gameId;
        this.summaryMessage = summaryMessage;
        this.winnerSide = winnerSide;
    }
}