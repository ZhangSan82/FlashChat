package com.flashchat.chatservice.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.flashchat.chatservice.dao.entity.AccountDO;
import com.flashchat.chatservice.dto.resp.AccountInfoRespDTO;

public interface AccountService extends IService<AccountDO> {
    /**
     * 匿名用户自动注册（创建匿名身份）
     */
    AccountInfoRespDTO autoRegister();

    /**
     * 通过业务账号 ID（FC-XXXXXX）查询信息
     */
    AccountInfoRespDTO getAccountInfoByAccountId(String accountId);

    /**
     * 通过业务账号 ID（FC-XXXXXX）查询完整实体（带缓存）
     */
    AccountDO getByAccountId(String accountId);

    /**
     * 通过数据库主键 ID 查询（带缓存）
     * 替代旧 MemberService.getByMemberId()
     */
    AccountDO getAccountByDbId(Long id);
}
