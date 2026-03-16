package com.flashchat.chatservice.service;


import com.baomidou.mybatisplus.extension.service.IService;
import com.flashchat.chatservice.dao.entity.RoomDO;
import com.flashchat.chatservice.dto.req.RoomCreateReqDTO;
import com.flashchat.chatservice.dto.req.RoomJoinReqDTO;
import com.flashchat.chatservice.dto.req.RoomLeaveReqDTO;
import com.flashchat.chatservice.dto.resp.RoomInfoRespDTO;
import com.flashchat.chatservice.dto.resp.RoomMemberRespDTO;
import com.flashchat.convention.result.Result;
import jakarta.validation.Valid;

import java.util.List;

public interface RoomService extends IService<RoomDO> {


    /**
     *创建房间
     */
    RoomInfoRespDTO createRoom(@Valid RoomCreateReqDTO request);

    /**
     *加入房间
     */
    RoomInfoRespDTO joinRoom(@Valid RoomJoinReqDTO request);

    /**
     * 离开房间
     */
    void leaveRoom(@Valid RoomLeaveReqDTO request);

    /**
     *房间的成员列表
     */
    List<RoomMemberRespDTO> getRoomMembers(String roomId);

    /**
     *用户重新登录恢复房间
     */
    void restoreRoomMemberships(Long memberId);
}
