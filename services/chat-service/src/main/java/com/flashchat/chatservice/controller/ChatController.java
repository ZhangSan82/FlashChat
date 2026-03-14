package com.flashchat.chatservice.controller;

import com.flashchat.chatservice.dto.req.SendMsgReqDTO;
import com.flashchat.chatservice.service.ChatService;
import com.flashchat.convention.result.Result;
import com.flashchat.convention.result.Results;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/FlashChat/v1/chat")
@CrossOrigin(origins = "*")
@AllArgsConstructor
public class ChatController {


    private final ChatService chatService;

    /**
     * 发送消息
     * <p>
     * POST /api/chat/msg
     * Body: {"roomId": "test_room", "memberId": 1, "content": "你好"}
     */
    @PostMapping("/msg")
    public Result<Void> sendMsg(@Valid @RequestBody SendMsgReqDTO request) {
        log.info("[HTTP-发消息] roomId={}, userId={}, content={}",
                request.getRoomId(), request.getAccountId(), request.getContent());

        chatService.sendMsg(request);

        return Results.success();

    }
}
