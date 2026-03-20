package com.flashchat.chatservice.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashchat.chatservice.dao.entity.MessageDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MessageMapper extends BaseMapper<MessageDO> {


    /**
     * 查询全表最大消息 ID（用于 Redis 重启后恢复水位）
     * @return 最大 ID，表为空时返回 null
     */
    @Select("SELECT MAX(id) FROM t_message")
    Long selectMaxId();



    /**
     * 批量插入（INSERT IGNORE）
     * 由 MessageStreamConsumer 攒批后调用，一次 INSERT 多条消息。
     * 幂等保障 — 依赖 t_message 的两个唯一约束：
     *   PRIMARY KEY (id)              → 预分配的自增 ID
     *   UNIQUE KEY uk_msg_id (msg_id) → UUID 业务 ID
     * 任一冲突 → INSERT IGNORE 静默跳过该行，不抛异常，不影响同批其他行。
     * 典型场景：
     *   正常消费：全部插入，返回值 = list.size()
     *   重复消费（Consumer ACK 前宕机重启）：冲突行被跳过，返回值 < list.size()
     *   降级写入后重复（saveSync 已入库，Stream 又消费到）：同上，安全
     * @param list 消息列表（不可为空，调用方需保证）
     * @return 实际插入行数（affected rows）
     */
    @Insert("<script>" +
            "INSERT IGNORE INTO t_message " +
            "(id, msg_id, room_id, sender_user_id, sender_member_id, " +
            "nickname, avatar_color, content, msg_type, status, is_host, create_time) " +
            "VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.id}, #{item.msgId}, #{item.roomId}, #{item.senderUserId}, #{item.senderMemberId}, " +
            "#{item.nickname}, #{item.avatarColor}, #{item.content}, #{item.msgType}, #{item.status}, " +
            "#{item.isHost}, #{item.createTime})" +
            "</foreach>" +
            "</script>")
    int insertBatchIgnore(@Param("list") List<MessageDO> list);

}
