package com.flashchat.gameservice.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashchat.gameservice.dao.entity.GameVoteDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GameVoteMapper extends BaseMapper<GameVoteDO> {
}
