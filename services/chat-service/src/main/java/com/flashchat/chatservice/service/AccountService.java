package com.flashchat.chatservice.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.flashchat.chatservice.dao.entity.AccountDO;
import com.flashchat.chatservice.dto.resp.AccountInfoRespDTO;
import com.flashchat.chatservice.dto.resp.AuthRespDTO;

public interface AccountService extends IService<AccountDO> {


    /**
     * 匿名注册 + 自动登录（一站式）
     * <p>
     * 内部编排：创建账号（事务）→ 写缓存 → SaToken 登录
     * Controller 只需调这一个方法。
     */
    AuthRespDTO autoRegister();

    /**
     * 执行 SaToken 登录
     * @param account  账号实体
     * @param userType 用户类型，取值见 UserTypeConstant
     * @return 带 token 的认证响应
     */
    AuthRespDTO doLogin(AccountDO account, int userType);

    /**
     * 登出当前用户
     */
    void doLogout();

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
     */
    AccountDO getAccountByDbId(Long id);


}
