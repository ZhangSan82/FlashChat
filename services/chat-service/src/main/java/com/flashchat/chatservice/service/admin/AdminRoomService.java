package com.flashchat.chatservice.service.admin;

import com.flashchat.chatservice.dto.resp.RoomInfoRespDTO;
import com.flashchat.chatservice.dto.resp.RoomMemberRespDTO;
import com.flashchat.userservice.dto.req.AdminOperationReasonReqDTO;

import java.util.List;

/**
 * 管理端房间管理服务。
 */
public interface AdminRoomService {

    RoomInfoRespDTO getRoomDetail(Long operatorId, String roomId);

    List<RoomMemberRespDTO> getRoomMembers(Long operatorId, String roomId);

    void closeRoom(Long operatorId, String roomId, AdminOperationReasonReqDTO request);

    void kickMember(Long operatorId, String roomId, Long targetAccountId, AdminOperationReasonReqDTO request);

    void muteMember(Long operatorId, String roomId, Long targetAccountId, AdminOperationReasonReqDTO request);

    void unmuteMember(Long operatorId, String roomId, Long targetAccountId, AdminOperationReasonReqDTO request);
}
