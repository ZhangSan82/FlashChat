package com.flashchat.chatservice.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashchat.chatservice.dao.entity.RoomDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RoomMapper extends BaseMapper<RoomDO> {


    /**
     * CAS 递增成员数（防超卖）
     */
    @Update("UPDATE t_room SET current_members = current_members + 1 " +
            "WHERE room_id = #{roomId} " +
            "AND current_members < max_members " +
            "AND status IN (0, 1, 2) " +
            "AND del_flag = 0")
    int incrementMemberCount(@Param("roomId") String roomId);

    /**
     * 递减成员数
     */
    @Update("UPDATE t_room SET current_members = current_members - 1 " +
            "WHERE room_id = #{roomId} " +
            "AND current_members > 0 " +
            "AND del_flag = 0")
    int decrementMemberCount(@Param("roomId") String roomId);

}
