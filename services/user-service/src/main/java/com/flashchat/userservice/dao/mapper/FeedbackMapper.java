package com.flashchat.userservice.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashchat.userservice.dao.entity.FeedbackDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FeedbackMapper extends BaseMapper<FeedbackDO> {
}
