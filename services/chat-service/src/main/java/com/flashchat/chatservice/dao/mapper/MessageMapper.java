package com.flashchat.chatservice.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashchat.chatservice.dao.entity.MessageDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

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
            "(id, msg_id, room_id, sender_id, " +
            " nickname, avatar_color, content, content_cipher, content_iv, key_version, body, reply_msg_id, " +
            " msg_type, status, is_host, create_time) " +
            "VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.id}, #{item.msgId}, #{item.roomId}, " +
            " #{item.senderId}, " +
            " #{item.nickname}, #{item.avatarColor}, " +
            " #{item.content}, #{item.contentCipher}, #{item.contentIv}, #{item.keyVersion}, #{item.body}, #{item.replyMsgId}, " +
            " #{item.msgType}, #{item.status}, #{item.isHost}, " +
            " #{item.createTime})" +
            "</foreach>" +
            "</script>")
    int insertBatchIgnore(@Param("list") List<MessageDO> list);

    @Update("<script>" +
            "UPDATE t_message " +
            "SET content = NULL, " +
            " content_cipher = CASE id " +
            " <foreach collection='list' item='item'>" +
            "   WHEN #{item.id} THEN #{item.contentCipher} " +
            " </foreach> END, " +
            " content_iv = CASE id " +
            " <foreach collection='list' item='item'>" +
            "   WHEN #{item.id} THEN #{item.contentIv} " +
            " </foreach> END, " +
            " key_version = CASE id " +
            " <foreach collection='list' item='item'>" +
            "   WHEN #{item.id} THEN #{item.keyVersion} " +
            " </foreach> END " +
            "WHERE id IN " +
            "<foreach collection='list' item='item' open='(' separator=',' close=')'>" +
            " #{item.id} " +
            "</foreach>" +
            " AND content IS NOT NULL" +
            "</script>")
    int updateCryptoBatch(@Param("list") List<MessageDO> list);


    /**
     * 查询未读消息 ID 列表（带 LIMIT，配合 999+ 上限）
     * <p>
     * ORDER BY id ASC 利用索引 idx_room_id (room_id, id) 的排序，无额外排序开销
     * MySQL 扫到第 1000 行即停止，避免全表 COUNT
     */
    @Select("SELECT id FROM t_message WHERE room_id = #{roomId} AND id > #{ackId} AND status = 0 ORDER BY id ASC LIMIT 1000")
    List<Long> selectUnreadMsgIds(@Param("roomId") String roomId, @Param("ackId") long ackId);

    /**
     * 批量计算用户所有活跃房间的未读数（单条 SQL 替代 N+1）
     * <p>
     * JOIN t_room_member 和 t_message，按 room_id 分组 COUNT，
     * 每组上限 1000 行（LIMIT 由外层 application code 截断为 999）。
     * 索引依赖：t_message (room_id, id) + t_room_member (account_id, status)
     *
     * @return roomId → unreadCount 的映射列表
     */
    @Select("SELECT rm.room_id, COUNT(m.id) AS cnt " +
            "FROM t_room_member rm " +
            "INNER JOIN t_message m " +
            "  ON m.room_id = rm.room_id " +
            "  AND m.id > COALESCE(rm.last_ack_msg_id, 0) " +
            "  AND m.status = 0 " +
            "WHERE rm.account_id = #{accountId} " +
            "  AND rm.status = 1 " +
            "GROUP BY rm.room_id " +
            "HAVING cnt > 0")
    List<Map<String, Object>> selectBatchUnreadCounts(@Param("accountId") Long accountId);

}
