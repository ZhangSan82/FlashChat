package com.flashchat.userservice.service.admin;

import com.flashchat.convention.exception.ClientException;
import com.flashchat.userservice.dao.entity.AccountDO;
import com.flashchat.userservice.service.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 管理员鉴权实现。
 * <p>
 * 管理员敏感操作统一实时回查账号状态，避免旧 Session 在角色变化后继续持有权限。
 */
@Service
@RequiredArgsConstructor
public class AdminAuthServiceImpl implements AdminAuthService {

    private final AccountService accountService;

    @Override
    public AccountDO requireActiveAdmin(Long operatorId) {
        if (operatorId == null) {
            throw new ClientException("当前未登录，无法执行管理员操作");
        }
        AccountDO operator = accountService.getAccountByDbId(operatorId);
        if (operator == null) {
            throw new ClientException("管理员账号不存在");
        }
        if (!operator.registered()) {
            throw new ClientException("游客账号不能执行管理员操作");
        }
        if (!operator.isNormal()) {
            throw new ClientException("当前账号已被封禁，无法执行管理员操作");
        }
        if (!operator.isAdmin()) {
            throw new ClientException("当前账号没有管理员权限");
        }
        return operator;
    }
}
