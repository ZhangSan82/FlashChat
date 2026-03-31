package com.flashchat.gameservice.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashchat.gameservice.dao.entity.GamePlayerDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 游戏玩家 Mapper
 */
@Mapper
public interface GamePlayerMapper extends BaseMapper<GamePlayerDO> {
}
