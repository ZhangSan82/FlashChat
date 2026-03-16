package com.flashchat.chatservice.dto.context;

import com.flashchat.chatservice.dao.entity.RoomDO;
import com.flashchat.chatservice.dao.entity.RoomMemberDO;
import lombok.Builder;
import lombok.Data;

/**
 * 房主操作的校验结果（踢人/禁言/解禁共用）
 */
@Data
@Builder
public class HostOperationContext {
    private RoomDO room;
    private Long operatorId;
    private Long targetId;
    private RoomMemberDO operatorMember;
    private RoomMemberDO targetMember;
}