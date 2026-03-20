package com.flashchat.chatservice.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashchat.chatservice.dao.entity.MessageDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MessageMapper extends BaseMapper<MessageDO> {


    /**
     * 查询全表最大消息 ID（用于 Redis 重启后恢复水位）
     * @return 最大 ID，表为空时返回 null
     */
    @Select("SELECT MAX(id) FROM t_message")
    Long selectMaxId();

}
