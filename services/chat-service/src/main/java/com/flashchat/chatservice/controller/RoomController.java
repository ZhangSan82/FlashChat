package com.flashchat.chatservice.controller;

import com.flashchat.chatservice.dto.req.*;
import com.flashchat.chatservice.dto.resp.RoomInfoRespDTO;
import com.flashchat.chatservice.dto.resp.RoomMemberRespDTO;
import com.flashchat.chatservice.dto.resp.RoomPricingRespDTO;
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
        log.info("[离开房间] roomId={}", request.getRoomId());
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
        log.info("[踢人] roomId={}, target={}",
                request.getRoomId(), request.getTargetAccountId());
        roomService.kickMember(request);
        return Results.success();
    }

    /**
     * 禁言（仅房主可操作）
     */
    @PostMapping("/mute")
    public Result<Void> muteMember(@Valid @RequestBody RoomMuteReqDTO request) {
        log.info("[禁言] roomId={},target={}",
                request.getRoomId(), request.getTargetAccountId());
        roomService.muteMember(request);
        return Results.success();
    }

    /**
     * 解除禁言（仅房主可操作）
     */
    @PostMapping("/unmute")
    public Result<Void> unmuteMember(@Valid @RequestBody RoomMuteReqDTO request) {
        log.info("[解禁] roomId={}, target={}",
                request.getRoomId(),  request.getTargetAccountId());
        roomService.unmuteMember(request);
        return Results.success();
    }

    /**
     * 获取我加入的所有房间
     */
    @GetMapping("/my-rooms")
    public Result<List<RoomInfoRespDTO>> getMyRooms() {
        log.info("[我的房间]");
        return Results.success(roomService.getMyRooms());
    }


    @PostMapping("/close")
    public Result<Void> closeRoom(@Valid @RequestBody RoomCloseReqDTO request) {
        log.info("[关闭房间] roomId={}", request.getRoomId());
        roomService.closeRoom(request);
        return Results.success();
    }

    /**
     * 公开房间列表
     */
    @GetMapping("/public")
    public Result<List<RoomInfoRespDTO>> listPublicRooms(@Valid PublicRoomListReqDTO request) {
        log.info("[公开房间列表] page={}, size={}, sort={}",
                request.getPage(), request.getSize(), request.getSort());
        return Results.success(roomService.listPublicRooms(request));
    }

    /**
     * 获取房间分享链接
     */
    @GetMapping("/{roomId}/share")
    public Result<String> getShareUrl(@PathVariable("roomId") String roomId) {
        log.info("[获取分享链接] roomId={}", roomId);
        return Results.success(roomService.getShareUrl(roomId));
    }

    /**
     * 查询房间时长定价列表
     */
    @GetMapping("/pricing")
    public Result<List<RoomPricingRespDTO>> getRoomPricing() {
        return Results.success(roomService.getRoomPricing());
    }

    /**
     * 房间时长延期
     */
    @PostMapping("/extend")
    public Result<RoomInfoRespDTO> extendRoom(@Valid @RequestBody RoomExtendReqDTO request) {
        log.info("[房间延期] roomId={}, duration={}", request.getRoomId(), request.getDuration());
        return Results.success(roomService.extendRoom(request));
    }

    /**
     * 房间人数扩容
     */
    @PostMapping("/resize")
    public Result<Void> resizeRoom(@Valid @RequestBody RoomResizeReqDTO request) {
        log.info("[房间扩容] roomId={}, newMaxMembers={}",
                request.getRoomId(), request.getNewMaxMembers());
        roomService.resizeRoom(request);
        return Results.success();
    }

    /**
     * 房间预览（无需加入即可查看基本信息）
     */
    @GetMapping("/preview/{roomId}")
    public Result<RoomInfoRespDTO> previewRoom(@PathVariable("roomId") String roomId) {
        log.info("[房间预览] roomId={}", roomId);
        return Results.success(roomService.previewRoom(roomId));
    }
}
