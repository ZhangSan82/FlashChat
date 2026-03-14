package com.flashchat.chatservice.controller;



import com.flashchat.chatservice.dto.req.RoomCreateReqDTO;
import com.flashchat.chatservice.dto.req.RoomJoinReqDTO;
import com.flashchat.chatservice.dto.req.RoomLeaveReqDTO;
import com.flashchat.chatservice.dto.resp.RoomInfoRespDTO;
import com.flashchat.chatservice.dto.resp.RoomMemberRespDTO;
import com.flashchat.chatservice.service.RoomService;

import com.flashchat.convention.result.Result;
import com.flashchat.convention.result.Results;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/FlashChat/v1/room")
@CrossOrigin(origins = "*")
@AllArgsConstructor
public class RoomController {

    private final RoomService roomService;

    /**
     * 创建房间
     */
    @PostMapping("/create")
    public Result<RoomInfoRespDTO> createRoom(@Valid @RequestBody RoomCreateReqDTO request) {
        log.info("创建房间:{}",request);
        return Results.success(roomService.createRoom(request));
    }


    /**
     * 加入房间
     */
    @PostMapping("/join")
    public Result<RoomInfoRespDTO> joinRoom(@Valid @RequestBody RoomJoinReqDTO request) {
        log.info("加入房间:{}",request);
        return Results.success(roomService.joinRoom(request));
    }


    /**
     * 离开房间
     */
    @PostMapping("/leave")
    public Result<Void> leaveRoom(@Valid @RequestBody RoomLeaveReqDTO request) {
        log.info("[离开房间] roomId={}, memberId={}", request.getRoomId(), request.getMemberId());
        roomService.leaveRoom(request);
        return Results.success();
    }

    /**
     * 房间的成员列表
     */
    @GetMapping("/members/{roomId}")
    public Result<List<RoomMemberRespDTO>> getRoomMembers(@PathVariable("roomId") String roomId) {
        log.info("[成员列表] roomId={}", roomId);
        List<RoomMemberRespDTO> members = roomService.getRoomMembers(roomId);
        return Results.success(members);
    }


}
