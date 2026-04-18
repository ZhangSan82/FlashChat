package com.flashchat.userservice.service.admin;

import com.flashchat.userservice.dao.entity.AccountDO;

/**
 * 账号会话控制服务。
 */
public interface AccountSessionService {

    /**
     * 强制踢掉该账号的所有可能登录态。
     * <p>
     * 账号体系目前有匿名成员和注册用户两种 loginId 前缀，
     * 这里统一把两条前缀都尝试踢下线，避免脏会话残留。
     */
    void kickoutAllSessions(AccountDO account);
}
