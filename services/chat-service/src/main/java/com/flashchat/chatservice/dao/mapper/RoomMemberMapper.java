package com.flashchat.chatservice.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashchat.chatservice.dao.entity.RoomMemberDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface RoomMemberMapper extends BaseMapper<RoomMemberDO> {
    /**
     * 批量统计房间活跃成员数（DB 聚合，用于对账任务）
     */
    @Select("<script>" +
            "SELECT room_id, COUNT(*) AS cnt FROM t_room_member " +
            "WHERE room_id IN " +
            "<foreach item='id' collection='roomIds' open='(' separator=',' close=')'>" +
            "#{id}" +
            "</foreach>" +
            " AND status = 1 " +
            "GROUP BY room_id" +
            "</script>")
    List<Map<String, Object>> countActiveMembersByRoomIds(@Param("roomIds") List<String> roomIds);
}
