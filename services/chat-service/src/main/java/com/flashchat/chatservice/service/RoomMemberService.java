package com.flashchat.chatservice.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.flashchat.chatservice.dao.entity.RoomMemberDO;


public interface RoomMemberService extends IService<RoomMemberDO> {

    /**
     * 通过缓存查询用户
     */
    RoomMemberDO getRoomMemberByRoomIdAndAccountId(String roomId, Long accountId);


    /**
     * 更新 + 自动失效缓存
     * 替代所有直接调用 updateById 的地方
     */
    boolean updateWithCacheEvict(RoomMemberDO entity);

    /**
     * 手动失效缓存（用于 lambdaUpdate 等无法走 updateWithCacheEvict 的场景）
     */
    void evictCache(String roomId, Long accountId);

    /**
     * 批量失效某个房间所有成员的缓存（关闭房间时使用）
     */
    void evictAllMemberCacheInRoom(String roomId);

    /**
     * 保存 + 写入缓存
     */
    boolean saveWithCache(RoomMemberDO entity);
}
