package com.flashchat.gameservice.dao.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashchat.gameservice.dao.entity.WordPairDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WordPairMapper extends BaseMapper<WordPairDO> {
}