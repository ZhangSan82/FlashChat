package com.flashchat.userservice.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashchat.userservice.dao.entity.InviteCodeDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InviteCodeMapper extends BaseMapper<InviteCodeDO> {
}