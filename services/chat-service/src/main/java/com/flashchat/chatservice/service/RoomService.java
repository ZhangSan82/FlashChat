package com.flashchat.chatservice.service;


import com.baomidou.mybatisplus.extension.service.IService;
import com.flashchat.chatservice.dao.entity.RoomDO;
import com.flashchat.chatservice.dto.req.*;
import com.flashchat.chatservice.dto.resp.RoomInfoRespDTO;
import com.flashchat.chatservice.dto.resp.RoomMemberRespDTO;
import com.flashchat.chatservice.dto.resp.RoomPricingRespDTO;
import com.flashchat.convention.result.Result;
import jakarta.validation.Valid;

import java.util.List;
import java.util.Set;

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
    void restoreRoomMemberships(Long accountId);

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

    /** 系统触发：即将到期 */
    void doRoomExpiringSoon(String roomId);

    /** 系统触发：进入宽限期 */
    void doRoomExpired(String roomId);

    /** 系统触发：正式关闭（无权限校验） */
    void doCloseRoom(String roomId);

    List<RoomInfoRespDTO> getMyRooms();

    /**
     *用缓存查询房间
     */
    RoomDO getRoomByRoomId(String roomId);

    /**
     * 查询所有已关闭状态的房间 ID
     * 供内存清理任务使用
     */
    Set<String> listClosedRoomIds();

    /**
     * 查询公开房间列表
     */
    List<RoomInfoRespDTO> listPublicRooms(PublicRoomListReqDTO request);

    /**
     * 获取房间分享链接
     * 前端用此 URL 生成二维码
     */
    String getShareUrl(String roomId);

    List<RoomPricingRespDTO> getRoomPricing();

    /**
     * 房间时长延期
     */
    RoomInfoRespDTO extendRoom(@Valid RoomExtendReqDTO request);

    /**
     * 房间人数扩容
     */
    void resizeRoom(@Valid RoomResizeReqDTO request);

}
