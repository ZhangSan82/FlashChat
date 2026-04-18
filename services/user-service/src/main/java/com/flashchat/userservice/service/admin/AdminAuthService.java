package com.flashchat.userservice.service.admin;

import com.flashchat.userservice.dao.entity.AccountDO;

/**
 * 管理员鉴权服务。
 * <p>
 * 管理员敏感操作不能只信 Session，必须实时回查账号状态和系统角色。
 */
public interface AdminAuthService {

    /**
     * 校验操作人是否是当前有效的系统管理员。
     *
     * @param operatorId 当前登录账号主键
     * @return 操作人账号实体
     */
    AccountDO requireActiveAdmin(Long operatorId);
}
