package com.flashchat.userservice.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashchat.userservice.dao.entity.CreditTransactionDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 积分流水 Mapper
 */
@Mapper
public interface CreditTransactionMapper extends BaseMapper<CreditTransactionDO> {
}
