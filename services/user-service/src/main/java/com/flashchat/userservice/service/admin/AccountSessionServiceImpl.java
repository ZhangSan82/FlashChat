package com.flashchat.userservice.service.admin;

import cn.dev33.satoken.stp.StpUtil;
import com.flashchat.user.toolkit.LoginIdUtil;
import com.flashchat.userservice.dao.entity.AccountDO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 账号会话控制实现。
 */
@Slf4j
@Service
public class AccountSessionServiceImpl implements AccountSessionService {

    @Override
    public void kickoutAllSessions(AccountDO account) {
        if (account == null || account.getId() == null) {
            return;
        }
        try {
            StpUtil.kickout(LoginIdUtil.memberLoginId(account.getId()));
            StpUtil.kickout(LoginIdUtil.userLoginId(account.getId()));
        } catch (Exception ex) {
            log.warn("[管理员强制下线] accountId={} 时踢出会话发生异常", account.getAccountId(), ex);
        }
    }
}
