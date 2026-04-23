package com.flashchat.chatservice.controller;

import com.flashchat.chatservice.dto.req.AdminRoomQueryReqDTO;
import com.flashchat.chatservice.dto.resp.AdminRoomSummaryRespDTO;
import com.flashchat.chatservice.dto.resp.RoomInfoRespDTO;
import com.flashchat.chatservice.dto.resp.RoomMemberRespDTO;
import com.flashchat.chatservice.service.admin.AdminRoomService;
import com.flashchat.convention.result.Result;
import com.flashchat.convention.result.Results;
import com.flashchat.user.core.UserContext;
import com.flashchat.userservice.dto.req.AdminOperationReasonReqDTO;
import com.flashchat.userservice.dto.resp.AdminPageRespDTO;
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

/**
 * 管理端房间接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/FlashChat/v1/admin/rooms")
@CrossOrigin(origins = "*")
@AllArgsConstructor
public class AdminRoomController {

    private final AdminRoomService adminRoomService;

    @GetMapping
    public Result<AdminPageRespDTO<AdminRoomSummaryRespDTO>> searchRooms(@Valid AdminRoomQueryReqDTO request) {
        Long operatorId = UserContext.getRequiredLoginId();
        log.info("[admin room search] operator={}, keyword={}", operatorId, request.getKeyword());
        return Results.success(adminRoomService.searchRooms(operatorId, request));
    }

    @GetMapping("/{roomId}")
    public Result<RoomInfoRespDTO> getRoomDetail(@PathVariable("roomId") String roomId) {
        Long operatorId = UserContext.getRequiredLoginId();
        log.info("[admin room detail] operator={}, roomId={}", operatorId, roomId);
        return Results.success(adminRoomService.getRoomDetail(operatorId, roomId));
    }

    @GetMapping("/{roomId}/members")
    public Result<List<RoomMemberRespDTO>> getRoomMembers(@PathVariable("roomId") String roomId) {
        Long operatorId = UserContext.getRequiredLoginId();
        log.info("[admin room members] operator={}, roomId={}", operatorId, roomId);
        return Results.success(adminRoomService.getRoomMembers(operatorId, roomId));
    }

    @PostMapping("/{roomId}/close")
    public Result<Void> closeRoom(@PathVariable("roomId") String roomId,
                                  @Valid @RequestBody AdminOperationReasonReqDTO request) {
        Long operatorId = UserContext.getRequiredLoginId();
        log.info("[admin close room] operator={}, roomId={}", operatorId, roomId);
        adminRoomService.closeRoom(operatorId, roomId, request);
        return Results.success();
    }

    @PostMapping("/{roomId}/members/{accountId}/kick")
    public Result<Void> kickMember(@PathVariable("roomId") String roomId,
                                   @PathVariable("accountId") Long accountId,
                                   @Valid @RequestBody AdminOperationReasonReqDTO request) {
        Long operatorId = UserContext.getRequiredLoginId();
        log.info("[admin kick room member] operator={}, roomId={}, target={}", operatorId, roomId, accountId);
        adminRoomService.kickMember(operatorId, roomId, accountId, request);
        return Results.success();
    }

    @PostMapping("/{roomId}/members/{accountId}/mute")
    public Result<Void> muteMember(@PathVariable("roomId") String roomId,
                                   @PathVariable("accountId") Long accountId,
                                   @Valid @RequestBody AdminOperationReasonReqDTO request) {
        Long operatorId = UserContext.getRequiredLoginId();
        log.info("[admin mute room member] operator={}, roomId={}, target={}", operatorId, roomId, accountId);
        adminRoomService.muteMember(operatorId, roomId, accountId, request);
        return Results.success();
    }

    @PostMapping("/{roomId}/members/{accountId}/unmute")
    public Result<Void> unmuteMember(@PathVariable("roomId") String roomId,
                                     @PathVariable("accountId") Long accountId,
                                     @Valid @RequestBody AdminOperationReasonReqDTO request) {
        Long operatorId = UserContext.getRequiredLoginId();
        log.info("[admin unmute room member] operator={}, roomId={}, target={}", operatorId, roomId, accountId);
        adminRoomService.unmuteMember(operatorId, roomId, accountId, request);
        return Results.success();
    }
}
