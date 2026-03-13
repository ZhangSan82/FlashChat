package com.itjiye.chatservice.controller;

import com.itjiye.chatservice.dto.req.SendMsgReqDTO;
import com.itjiye.chatservice.service.ChatService;
import com.itjiye.convention.result.Result;
import com.itjiye.convention.result.Results;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/chat")
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
        log.info("[HTTP-发消息] roomId={}, memberId={}, content={}",
                request.getRoomId(), request.getMemberId(), request.getContent());

        chatService.sendMsg(request);

        return Results.success();
    }
}
