package com.flashchat.chatservice.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashchat.chatservice.dao.entity.RoomMemberDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RoomMemberMapper extends BaseMapper<RoomMemberDO> {
}
