package com.flashchat.chatservice.listener;

import com.flashchat.channel.event.GameEndedNotifyEvent;
import com.flashchat.chatservice.service.SystemMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 游戏结束事件监听器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GameEndedListener {

    private final SystemMessageService systemMessageService;

    @EventListener
    public void onGameEnded(GameEndedNotifyEvent event) {
        log.info("[游戏结束通知] room={}, gameId={}, winner={}",
                event.getRoomId(), event.getGameId(), event.getWinnerSide());
        systemMessageService.sendToRoom(event.getRoomId(), event.getSummaryMessage());
    }
}
