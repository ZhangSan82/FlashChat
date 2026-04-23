package com.flashchat.userservice.service.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flashchat.convention.exception.ClientException;
import com.flashchat.convention.storage.OssAssetUrlService;
import com.flashchat.user.event.AccountBannedEvent;
import com.flashchat.user.event.MemberLogoutEvent;
import com.flashchat.userservice.dao.entity.AccountDO;
import com.flashchat.userservice.dao.entity.AdminOperationLogDO;
import com.flashchat.userservice.dao.enums.AccountRoleEnum;
import com.flashchat.userservice.dao.enums.AccountStatusEnum;
import com.flashchat.userservice.dao.enums.AdminOperationTargetTypeEnum;
import com.flashchat.userservice.dao.enums.AdminOperationTypeEnum;
import com.flashchat.userservice.dao.enums.CreditTypeEnum;
import com.flashchat.userservice.dao.mapper.AccountMapper;
import com.flashchat.userservice.dto.req.AdminAccountQueryReqDTO;
import com.flashchat.userservice.dto.req.AdminCreditAdjustReqDTO;
import com.flashchat.userservice.dto.req.AdminOperationReasonReqDTO;
import com.flashchat.userservice.dto.resp.AdminAccountRespDTO;
import com.flashchat.userservice.dto.resp.AdminPageRespDTO;
import com.flashchat.userservice.service.AccountService;
import com.flashchat.userservice.service.CreditService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 管理员账号管理服务实现。
 */
@Service
@RequiredArgsConstructor
public class AdminAccountServiceImpl implements AdminAccountService {

    private final AdminAuthService adminAuthService;
    private final AccountService accountService;
    private final AccountMapper accountMapper;
    private final CreditService creditService;
    private final AdminOperationLogService adminOperationLogService;
    private final AccountSessionService accountSessionService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final OssAssetUrlService ossAssetUrlService;

    @Override
    public AdminPageRespDTO<AdminAccountRespDTO> searchAccounts(Long operatorId, AdminAccountQueryReqDTO request) {
        adminAuthService.requireActiveAdmin(operatorId);

        long pageNo = request.getPage() == null || request.getPage() < 1 ? 1L : request.getPage();
        long size = request.getSize() == null || request.getSize() < 1 ? 20L : Math.min(request.getSize(), 100L);

        LambdaQueryWrapper<AccountDO> wrapper = new LambdaQueryWrapper<>();
        if (hasText(request.getKeyword())) {
            String keyword = request.getKeyword().trim();
            wrapper.and(q -> q.like(AccountDO::getAccountId, keyword)
                    .or().like(AccountDO::getNickname, keyword)
                    .or().like(AccountDO::getEmail, keyword));
        }
        if (request.getStatus() != null) {
            wrapper.eq(AccountDO::getStatus, request.getStatus());
        }
        if (request.getSystemRole() != null) {
            wrapper.eq(AccountDO::getSystemRole, request.getSystemRole());
        }
        wrapper.orderByDesc(AccountDO::getId);

        Page<AccountDO> result = accountMapper.selectPage(new Page<>(pageNo, size), wrapper);
        List<AdminAccountRespDTO> records = result.getRecords().stream()
                .map(this::toAdminAccountResp)
                .toList();

        return AdminPageRespDTO.<AdminAccountRespDTO>builder()
                .page(pageNo)
                .size(size)
                .total(result.getTotal())
                .records(records)
                .build();
    }

    @Override
    public AdminAccountRespDTO getAccountDetail(Long operatorId, String accountId) {
        adminAuthService.requireActiveAdmin(operatorId);
        return toAdminAccountResp(loadTargetAccount(accountId));
    }

    @Override
    public void banAccount(Long operatorId, String accountId, AdminOperationReasonReqDTO request) {
        AccountDO operator = adminAuthService.requireActiveAdmin(operatorId);
        AccountDO target = getManagedTargetAccount(accountId, operatorId);
        if (target.getStatus() != null && target.getStatus() == AccountStatusEnum.BANNED.getCode()) {
            return;
        }

        Integer beforeStatus = target.getStatus();
        target.setStatus(AccountStatusEnum.BANNED.getCode());
        accountService.updateById(target);
        accountService.evictCacheByDbId(target.getId());
        accountSessionService.kickoutAllSessions(target);
        applicationEventPublisher.publishEvent(new AccountBannedEvent(this, target.getId()));
        adminOperationLogService.record(buildLog(
                operator,
                AdminOperationTypeEnum.ACCOUNT_BAN,
                AdminOperationTargetTypeEnum.ACCOUNT,
                target.getAccountId(),
                target.getNickname(),
                request.getReason(),
                "{\"beforeStatus\":" + beforeStatus + ",\"afterStatus\":" + target.getStatus() + "}"
        ));
    }

    @Override
    public void unbanAccount(Long operatorId, String accountId, AdminOperationReasonReqDTO request) {
        AccountDO operator = adminAuthService.requireActiveAdmin(operatorId);
        AccountDO target = getManagedTargetAccount(accountId, operatorId);
        if (target.getStatus() != null && target.getStatus() == AccountStatusEnum.NORMAL.getCode()) {
            return;
        }

        Integer beforeStatus = target.getStatus();
        target.setStatus(AccountStatusEnum.NORMAL.getCode());
        accountService.updateById(target);
        accountService.evictCacheByDbId(target.getId());
        adminOperationLogService.record(buildLog(
                operator,
                AdminOperationTypeEnum.ACCOUNT_UNBAN,
                AdminOperationTargetTypeEnum.ACCOUNT,
                target.getAccountId(),
                target.getNickname(),
                request.getReason(),
                "{\"beforeStatus\":" + beforeStatus + ",\"afterStatus\":" + target.getStatus() + "}"
        ));
    }

    @Override
    public void kickoutAccount(Long operatorId, String accountId, AdminOperationReasonReqDTO request) {
        AccountDO operator = adminAuthService.requireActiveAdmin(operatorId);
        AccountDO target = getManagedTargetAccount(accountId, operatorId);
        accountSessionService.kickoutAllSessions(target);
        applicationEventPublisher.publishEvent(new MemberLogoutEvent(this, target.getId(), null));
        adminOperationLogService.record(buildLog(
                operator,
                AdminOperationTypeEnum.ACCOUNT_KICKOUT,
                AdminOperationTargetTypeEnum.ACCOUNT,
                target.getAccountId(),
                target.getNickname(),
                request.getReason(),
                null
        ));
    }

    @Override
    public void adjustCredits(Long operatorId, String accountId, AdminCreditAdjustReqDTO request) {
        AccountDO operator = adminAuthService.requireActiveAdmin(operatorId);
        AccountDO target = getManagedTargetAccount(accountId, operatorId);

        if (request.getDirection() == null || (request.getDirection() != 1 && request.getDirection() != -1)) {
            throw new ClientException("积分调整方向必须是 1 或 -1");
        }

        String bizId = "admin-adjust:" + operator.getId() + ":" + target.getId() + ":" + UUID.randomUUID();
        if (request.getDirection() == 1) {
            creditService.grantCredits(
                    target.getId(),
                    request.getAmount(),
                    CreditTypeEnum.ADMIN_ADJUST_INCREASE,
                    bizId,
                    request.getReason()
            );
            adminOperationLogService.record(buildLog(
                    operator,
                    AdminOperationTypeEnum.CREDIT_ADJUST_INCREASE,
                    AdminOperationTargetTypeEnum.ACCOUNT,
                    target.getAccountId(),
                    target.getNickname(),
                    request.getReason(),
                    "{\"amount\":" + request.getAmount() + ",\"direction\":1}"
            ));
        } else {
            creditService.deductCredits(
                    target.getId(),
                    request.getAmount(),
                    CreditTypeEnum.ADMIN_ADJUST_DECREASE,
                    bizId,
                    request.getReason()
            );
            adminOperationLogService.record(buildLog(
                    operator,
                    AdminOperationTypeEnum.CREDIT_ADJUST_DECREASE,
                    AdminOperationTargetTypeEnum.ACCOUNT,
                    target.getAccountId(),
                    target.getNickname(),
                    request.getReason(),
                    "{\"amount\":" + request.getAmount() + ",\"direction\":-1}"
            ));
        }
        accountService.evictCacheByDbId(target.getId());
    }

    @Override
    public void grantAdmin(Long operatorId, String accountId, AdminOperationReasonReqDTO request) {
        AccountDO operator = adminAuthService.requireActiveAdmin(operatorId);
        AccountDO target = getRoleManageTargetAccount(accountId, operatorId);
        if (target.isAdmin()) {
            return;
        }
        if (!target.registered()) {
            throw new ClientException("游客账号不能授予管理员权限");
        }
        if (!target.isNormal()) {
            throw new ClientException("已封禁账号不能授予管理员权限");
        }

        Integer beforeRole = target.getSystemRole();
        target.setSystemRole(AccountRoleEnum.ADMIN.getCode());
        accountService.updateById(target);
        accountService.evictCacheByDbId(target.getId());
        adminOperationLogService.record(buildLog(
                operator,
                AdminOperationTypeEnum.ACCOUNT_GRANT_ADMIN,
                AdminOperationTargetTypeEnum.ACCOUNT,
                target.getAccountId(),
                target.getNickname(),
                request.getReason(),
                "{\"beforeRole\":" + beforeRole + ",\"afterRole\":" + target.getSystemRole() + "}"
        ));
    }

    @Override
    public void revokeAdmin(Long operatorId, String accountId, AdminOperationReasonReqDTO request) {
        AccountDO operator = adminAuthService.requireActiveAdmin(operatorId);
        AccountDO target = getRoleManageTargetAccount(accountId, operatorId);
        if (!target.isAdmin()) {
            return;
        }
        if (countAdminAccounts() <= 1) {
            throw new ClientException("至少保留一个系统管理员账号");
        }

        Integer beforeRole = target.getSystemRole();
        target.setSystemRole(AccountRoleEnum.USER.getCode());
        accountService.updateById(target);
        accountService.evictCacheByDbId(target.getId());
        accountSessionService.kickoutAllSessions(target);
        adminOperationLogService.record(buildLog(
                operator,
                AdminOperationTypeEnum.ACCOUNT_REVOKE_ADMIN,
                AdminOperationTargetTypeEnum.ACCOUNT,
                target.getAccountId(),
                target.getNickname(),
                request.getReason(),
                "{\"beforeRole\":" + beforeRole + ",\"afterRole\":" + target.getSystemRole() + "}"
        ));
    }

    private AccountDO getManagedTargetAccount(String accountId, Long operatorId) {
        AccountDO target = loadTargetAccount(accountId);
        if (target.getId() != null && target.getId().equals(operatorId)) {
            throw new ClientException("不能对自己的账号执行该管理员操作");
        }
        if (target.isAdmin()) {
            throw new ClientException("当前版本暂不支持直接操作管理员账号，请走数据库或后续角色管理流程");
        }
        return target;
    }

    private AccountDO getRoleManageTargetAccount(String accountId, Long operatorId) {
        AccountDO target = loadTargetAccount(accountId);
        if (target.getId() != null && target.getId().equals(operatorId)) {
            throw new ClientException("不能调整自己的管理员角色");
        }
        return target;
    }

    private AccountDO loadTargetAccount(String accountId) {
        AccountDO target = accountMapper.selectOne(
                new LambdaQueryWrapper<AccountDO>().eq(AccountDO::getAccountId, accountId).last("limit 1")
        );
        if (target == null) {
            throw new ClientException("账号不存在");
        }
        return target;
    }

    private long countAdminAccounts() {
        Long count = accountMapper.selectCount(
                new LambdaQueryWrapper<AccountDO>().eq(AccountDO::getSystemRole, AccountRoleEnum.ADMIN.getCode())
        );
        return count == null ? 0L : count;
    }

    private AdminAccountRespDTO toAdminAccountResp(AccountDO account) {
        AccountRoleEnum roleEnum = AccountRoleEnum.of(account.getSystemRole());
        boolean banned = account.getStatus() != null
                && account.getStatus() == AccountStatusEnum.BANNED.getCode();
        return AdminAccountRespDTO.builder()
                .id(account.getId())
                .accountId(account.getAccountId())
                .nickname(account.getNickname())
                .avatarColor(account.getAvatarColor())
                .avatarUrl(ossAssetUrlService.resolveAccessUrl(account.getAvatarUrl()))
                .email(account.getEmail())
                .credits(account.getCredits())
                .isRegistered(account.registered())
                .status(account.getStatus())
                .statusDesc(banned ? "系统封禁" : "正常")
                .systemRole(roleEnum.getCode())
                .systemRoleDesc(roleEnum.getDesc())
                .lastActiveTime(account.getLastActiveTime())
                .createTime(account.getCreateTime())
                .build();
    }

    private AdminOperationLogDO buildLog(AccountDO operator,
                                         AdminOperationTypeEnum operationType,
                                         AdminOperationTargetTypeEnum targetType,
                                         String targetId,
                                         String targetDisplay,
                                         String reason,
                                         String detailJson) {
        return AdminOperationLogDO.builder()
                .operatorId(operator.getId())
                .operatorAccountId(operator.getAccountId())
                .operationType(operationType.name())
                .targetType(targetType.name())
                .targetId(targetId)
                .targetDisplay(targetDisplay)
                .reason(reason)
                .detailJson(detailJson)
                .build();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
