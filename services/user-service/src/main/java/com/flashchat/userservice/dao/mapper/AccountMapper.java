package com.flashchat.userservice.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashchat.userservice.dao.entity.AccountDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AccountMapper extends BaseMapper<AccountDO> {

    /**
     * CAS 扣减积分（防超卖）
     * <p>
     * SQL: UPDATE t_account SET credits = credits - #{amount}
     *      WHERE id = #{accountId} AND credits &gt;= #{amount}
     */
    @Update("UPDATE t_account SET credits = credits - #{amount} " +
            "WHERE id = #{accountId} AND credits >= #{amount}")
    int deductCredits(@Param("accountId") Long accountId,
                      @Param("amount") int amount);

    /**
     * 增加积分（无条件，收入类型不校验余额）
     * <p>
     * SQL: UPDATE t_account SET credits = credits + #{amount}
     *      WHERE id = #{accountId}
     */
    @Update("UPDATE t_account SET credits = credits + #{amount} " +
            "WHERE id = #{accountId}")
    int addCredits(@Param("accountId") Long accountId,
                   @Param("amount") int amount);
}
