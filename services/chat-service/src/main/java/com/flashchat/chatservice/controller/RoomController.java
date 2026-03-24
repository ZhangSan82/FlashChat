package com.flashchat.chatservice.controller;



import com.flashchat.chatservice.dto.req.*;
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
        log.info("[离开房间] roomId={}, memberId={}", request.getRoomId(), request.getAccountId());
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

    /**
     * 踢人（仅房主可操作）
     */
    @PostMapping("/kick")
    public Result<Void> kickMember(@Valid @RequestBody RoomKickReqDTO request) {
        log.info("[踢人] roomId={}, operator={}, target={}",
                request.getRoomId(), request.getAccountId(), request.getTargetAccountId());
        roomService.kickMember(request);
        return Results.success();
    }

    /**
     * 禁言（仅房主可操作）
     */
    @PostMapping("/mute")
    public Result<Void> muteMember(@Valid @RequestBody RoomMuteReqDTO request) {
        log.info("[禁言] roomId={}, operator={}, target={}",
                request.getRoomId(), request.getAccountId(), request.getTargetAccountId());
        roomService.muteMember(request);
        return Results.success();
    }

    /**
     * 解除禁言（仅房主可操作）
     */
    @PostMapping("/unmute")
    public Result<Void> unmuteMember(@Valid @RequestBody RoomMuteReqDTO request) {
        log.info("[解禁] roomId={}, operator={}, target={}",
                request.getRoomId(), request.getAccountId(), request.getTargetAccountId());
        roomService.unmuteMember(request);
        return Results.success();
    }

    /**
     * 获取我加入的所有房间
     */
    @GetMapping("/my-rooms")
    public Result<List<RoomInfoRespDTO>> getMyRooms(@RequestParam("accountId") String accountId) {
        log.info("[我的房间] accountId={}", accountId);
        return Results.success(roomService.getMyRooms(accountId));
    }


    @PostMapping("/close")
    public Result<Void> closeRoom(@Valid @RequestBody RoomCloseReqDTO request) {
        log.info("[关闭房间] roomId={}, operator={}", request.getRoomId(), request.getAccountId());
        roomService.closeRoom(request);
        return Results.success();
    }
}
