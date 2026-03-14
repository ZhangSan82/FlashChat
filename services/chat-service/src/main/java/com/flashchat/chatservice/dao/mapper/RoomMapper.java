package com.flashchat.chatservice.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashchat.chatservice.dao.entity.RoomDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RoomMapper extends BaseMapper<RoomDO> {
}
