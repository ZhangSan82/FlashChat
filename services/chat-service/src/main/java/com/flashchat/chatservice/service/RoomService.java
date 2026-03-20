package com.flashchat.chatservice.service;


import com.baomidou.mybatisplus.extension.service.IService;
import com.flashchat.chatservice.dao.entity.RoomDO;
import com.flashchat.chatservice.dto.req.*;
import com.flashchat.chatservice.dto.resp.RoomInfoRespDTO;
import com.flashchat.chatservice.dto.resp.RoomMemberRespDTO;
import com.flashchat.convention.result.Result;
import jakarta.validation.Valid;

import java.util.List;

public interface RoomService extends IService<RoomDO> {


    /**
     * 创建房间
     */
    RoomInfoRespDTO createRoom(@Valid RoomCreateReqDTO request);

    /**
     * 加入房间
     */
    RoomInfoRespDTO joinRoom(@Valid RoomJoinReqDTO request);

    /**
     * 离开房间
     */
    void leaveRoom(@Valid RoomLeaveReqDTO request);

    /**
     * 房间的成员列表
     */
    List<RoomMemberRespDTO> getRoomMembers(String roomId);

    /**
     * 用户重新登录恢复房间
     */
    void restoreRoomMemberships(Long memberId);

    /**
     *踢人
     */
    void kickMember(@Valid RoomKickReqDTO request);

    /**
     * 禁言
     */
    void muteMember(@Valid RoomMuteReqDTO request);

    /**
     *解言
     */
    void unmuteMember(@Valid RoomMuteReqDTO request);

    /**
     *关闭房间
     */
    void closeRoom(@Valid RoomCloseReqDTO request);

    List<RoomInfoRespDTO> getMyRooms(String accountId);

    /**
     *用缓存查询房间
     */
    RoomDO getRoomByRoomId(String roomId);
}
