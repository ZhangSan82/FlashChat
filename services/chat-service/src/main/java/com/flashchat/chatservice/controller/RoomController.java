package com.flashchat.chatservice.controller;

import com.flashchat.chatservice.dto.req.PublicRoomListReqDTO;
import com.flashchat.chatservice.dto.req.RoomAvatarUpdateReqDTO;
import com.flashchat.chatservice.dto.req.RoomCloseReqDTO;
import com.flashchat.chatservice.dto.req.RoomCreateReqDTO;
import com.flashchat.chatservice.dto.req.RoomExtendReqDTO;
import com.flashchat.chatservice.dto.req.RoomJoinReqDTO;
import com.flashchat.chatservice.dto.req.RoomKickReqDTO;
import com.flashchat.chatservice.dto.req.RoomLeaveReqDTO;
import com.flashchat.chatservice.dto.req.RoomMuteReqDTO;
import com.flashchat.chatservice.dto.req.RoomResizeReqDTO;
import com.flashchat.chatservice.dto.resp.RoomInfoRespDTO;
import com.flashchat.chatservice.dto.resp.RoomMemberRespDTO;
import com.flashchat.chatservice.dto.resp.RoomPricingRespDTO;
import com.flashchat.chatservice.service.RoomService;
import com.flashchat.convention.result.Result;
import com.flashchat.convention.result.Results;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/FlashChat/v1/room")
@CrossOrigin(origins = "*")
@AllArgsConstructor
public class RoomController {

    private final RoomService roomService;

    @PostMapping("/create")
    public Result<RoomInfoRespDTO> createRoom(@Valid @RequestBody RoomCreateReqDTO request) {
        log.info("[create room] request={}", request);
        return Results.success(roomService.createRoom(request));
    }

    @PostMapping("/join")
    public Result<RoomInfoRespDTO> joinRoom(@Valid @RequestBody RoomJoinReqDTO request) {
        log.info("[join room] request={}", request);
        return Results.success(roomService.joinRoom(request));
    }

    @PostMapping("/leave")
    public Result<Void> leaveRoom(@Valid @RequestBody RoomLeaveReqDTO request) {
        log.info("[leave room] roomId={}", request.getRoomId());
        roomService.leaveRoom(request);
        return Results.success();
    }

    @GetMapping("/members/{roomId}")
    public Result<List<RoomMemberRespDTO>> getRoomMembers(@PathVariable("roomId") String roomId) {
        log.info("[room members] roomId={}", roomId);
        return Results.success(roomService.getRoomMembers(roomId));
    }

    @PostMapping("/kick")
    public Result<Void> kickMember(@Valid @RequestBody RoomKickReqDTO request) {
        log.info("[kick member] roomId={}, target={}", request.getRoomId(), request.getTargetAccountId());
        roomService.kickMember(request);
        return Results.success();
    }

    @PostMapping("/mute")
    public Result<Void> muteMember(@Valid @RequestBody RoomMuteReqDTO request) {
        log.info("[mute member] roomId={}, target={}", request.getRoomId(), request.getTargetAccountId());
        roomService.muteMember(request);
        return Results.success();
    }

    @PostMapping("/unmute")
    public Result<Void> unmuteMember(@Valid @RequestBody RoomMuteReqDTO request) {
        log.info("[unmute member] roomId={}, target={}", request.getRoomId(), request.getTargetAccountId());
        roomService.unmuteMember(request);
        return Results.success();
    }

    @GetMapping("/my-rooms")
    public Result<List<RoomInfoRespDTO>> getMyRooms() {
        log.info("[my rooms]");
        return Results.success(roomService.getMyRooms());
    }

    @PostMapping("/close")
    public Result<Void> closeRoom(@Valid @RequestBody RoomCloseReqDTO request) {
        log.info("[close room] roomId={}", request.getRoomId());
        roomService.closeRoom(request);
        return Results.success();
    }

    @GetMapping("/public")
    public Result<List<RoomInfoRespDTO>> listPublicRooms(@Valid PublicRoomListReqDTO request) {
        log.info("[public rooms] page={}, size={}, sort={}", request.getPage(), request.getSize(), request.getSort());
        return Results.success(roomService.listPublicRooms(request));
    }

    @GetMapping("/{roomId}/share")
    public Result<String> getShareUrl(@PathVariable("roomId") String roomId) {
        log.info("[room share] roomId={}", roomId);
        return Results.success(roomService.getShareUrl(roomId));
    }

    @GetMapping("/pricing")
    public Result<List<RoomPricingRespDTO>> getRoomPricing() {
        return Results.success(roomService.getRoomPricing());
    }

    @PostMapping("/extend")
    public Result<RoomInfoRespDTO> extendRoom(@Valid @RequestBody RoomExtendReqDTO request) {
        log.info("[extend room] roomId={}, duration={}", request.getRoomId(), request.getDuration());
        return Results.success(roomService.extendRoom(request));
    }

    @PostMapping("/resize")
    public Result<Void> resizeRoom(@Valid @RequestBody RoomResizeReqDTO request) {
        log.info("[resize room] roomId={}, newMaxMembers={}", request.getRoomId(), request.getNewMaxMembers());
        roomService.resizeRoom(request);
        return Results.success();
    }

    @PostMapping("/avatar")
    public Result<RoomInfoRespDTO> updateRoomAvatar(@Valid @RequestBody RoomAvatarUpdateReqDTO request) {
        log.info("[update room avatar] roomId={}", request.getRoomId());
        return Results.success(roomService.updateRoomAvatar(request));
    }

    @GetMapping("/preview/{roomId}")
    public Result<RoomInfoRespDTO> previewRoom(@PathVariable("roomId") String roomId) {
        log.info("[preview room] roomId={}", roomId);
        return Results.success(roomService.previewRoom(roomId));
    }
}
