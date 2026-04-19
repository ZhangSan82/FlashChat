package com.flashchat.userservice.service.admin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flashchat.convention.exception.ClientException;
import com.flashchat.user.event.AccountBannedEvent;
import com.flashchat.user.event.MemberLogoutEvent;
import com.flashchat.userservice.dao.entity.AccountDO;
import com.flashchat.userservice.dao.entity.AdminOperationLogDO;
import com.flashchat.userservice.dao.enums.AccountRoleEnum;
import com.flashchat.userservice.dao.enums.AccountStatusEnum;
import com.flashchat.userservice.dao.enums.CreditTypeEnum;
import com.flashchat.userservice.dao.mapper.AccountMapper;
import com.flashchat.userservice.dto.req.AdminAccountQueryReqDTO;
import com.flashchat.userservice.dto.req.AdminCreditAdjustReqDTO;
import com.flashchat.userservice.dto.req.AdminOperationReasonReqDTO;
import com.flashchat.userservice.dto.resp.AdminAccountRespDTO;
import com.flashchat.userservice.dto.resp.AdminPageRespDTO;
import com.flashchat.userservice.service.AccountService;
import com.flashchat.userservice.service.CreditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAccountServiceImplTest {

    @Mock
    private AdminAuthService adminAuthService;

    @Mock
    private AccountService accountService;

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private CreditService creditService;

    @Mock
    private AdminOperationLogService adminOperationLogService;

    @Mock
    private AccountSessionService accountSessionService;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private AdminAccountService adminAccountService;

    @BeforeEach
    void setUp() {
        adminAccountService = new AdminAccountServiceImpl(
                adminAuthService,
                accountService,
                accountMapper,
                creditService,
                adminOperationLogService,
                accountSessionService,
                applicationEventPublisher
        );
    }

    @Test
    void searchAccountsShouldReturnPagedResult() {
        AccountDO admin = buildAccount(1L, "FC-ADMIN01", "admin", AccountRoleEnum.ADMIN.getCode(), AccountStatusEnum.NORMAL.getCode(), 999);
        AccountDO target = buildAccount(2L, "FC-USER01", "user", AccountRoleEnum.USER.getCode(), AccountStatusEnum.BANNED.getCode(), 20);
        when(adminAuthService.requireActiveAdmin(1L)).thenReturn(admin);
        when(accountMapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<AccountDO> page = invocation.getArgument(0);
            page.setRecords(List.of(target));
            page.setTotal(1L);
            return page;
        });

        AdminAccountQueryReqDTO request = new AdminAccountQueryReqDTO();
        request.setKeyword("FC-USER01");
        request.setPage(1L);
        request.setSize(10L);

        AdminPageRespDTO<AdminAccountRespDTO> result = adminAccountService.searchAccounts(1L, request);

        assertEquals(1L, result.getTotal());
        assertEquals(1, result.getRecords().size());
        assertEquals("FC-USER01", result.getRecords().get(0).getAccountId());
        assertEquals("系统封禁", result.getRecords().get(0).getStatusDesc());
    }

    @Test
    void banAccountShouldUpdateStatusKickoutTargetAndRecordLog() {
        AccountDO admin = buildAccount(1L, "FC-ADMIN01", "admin", AccountRoleEnum.ADMIN.getCode(), AccountStatusEnum.NORMAL.getCode(), 999);
        AccountDO target = buildAccount(2L, "FC-USER01", "user", AccountRoleEnum.USER.getCode(), AccountStatusEnum.NORMAL.getCode(), 20);
        when(adminAuthService.requireActiveAdmin(1L)).thenReturn(admin);
        when(accountMapper.selectOne(any())).thenReturn(target);

        AdminOperationReasonReqDTO request = new AdminOperationReasonReqDTO();
        request.setReason("spam");

        adminAccountService.banAccount(1L, "FC-USER01", request);

        ArgumentCaptor<AccountDO> accountCaptor = ArgumentCaptor.forClass(AccountDO.class);
        verify(accountService).updateById(accountCaptor.capture());
        assertEquals(AccountStatusEnum.BANNED.getCode(), accountCaptor.getValue().getStatus());
        verify(accountService).evictCacheByDbId(2L);
        verify(accountSessionService).kickoutAllSessions(target);
        verify(applicationEventPublisher).publishEvent(any(AccountBannedEvent.class));
        verify(adminOperationLogService).record(any(AdminOperationLogDO.class));
    }

    @Test
    void kickoutAccountShouldKickoutTargetAndPublishLogoutEvent() {
        AccountDO admin = buildAccount(1L, "FC-ADMIN01", "admin", AccountRoleEnum.ADMIN.getCode(), AccountStatusEnum.NORMAL.getCode(), 999);
        AccountDO target = buildAccount(2L, "FC-USER01", "user", AccountRoleEnum.USER.getCode(), AccountStatusEnum.NORMAL.getCode(), 20);
        when(adminAuthService.requireActiveAdmin(1L)).thenReturn(admin);
        when(accountMapper.selectOne(any())).thenReturn(target);

        AdminOperationReasonReqDTO request = new AdminOperationReasonReqDTO();
        request.setReason("force logout");

        adminAccountService.kickoutAccount(1L, "FC-USER01", request);

        verify(accountSessionService).kickoutAllSessions(target);
        verify(applicationEventPublisher).publishEvent(any(MemberLogoutEvent.class));
        verify(adminOperationLogService).record(any(AdminOperationLogDO.class));
    }

    @Test
    void adjustCreditsShouldGrantCreditsWhenDirectionIsIncrease() {
        AccountDO admin = buildAccount(1L, "FC-ADMIN01", "admin", AccountRoleEnum.ADMIN.getCode(), AccountStatusEnum.NORMAL.getCode(), 999);
        AccountDO target = buildAccount(2L, "FC-USER01", "user", AccountRoleEnum.USER.getCode(), AccountStatusEnum.NORMAL.getCode(), 20);
        when(adminAuthService.requireActiveAdmin(1L)).thenReturn(admin);
        when(accountMapper.selectOne(any())).thenReturn(target);

        AdminCreditAdjustReqDTO request = new AdminCreditAdjustReqDTO();
        request.setAmount(30);
        request.setDirection(1);
        request.setReason("compensation");

        adminAccountService.adjustCredits(1L, "FC-USER01", request);

        verify(creditService).grantCredits(eq(2L), eq(30), eq(CreditTypeEnum.ADMIN_ADJUST_INCREASE), any(), eq("compensation"));
        verify(accountService).evictCacheByDbId(2L);
        verify(adminOperationLogService).record(any(AdminOperationLogDO.class));
    }

    @Test
    void adjustCreditsShouldDeductCreditsWhenDirectionIsDecrease() {
        AccountDO admin = buildAccount(1L, "FC-ADMIN01", "admin", AccountRoleEnum.ADMIN.getCode(), AccountStatusEnum.NORMAL.getCode(), 999);
        AccountDO target = buildAccount(2L, "FC-USER01", "user", AccountRoleEnum.USER.getCode(), AccountStatusEnum.NORMAL.getCode(), 20);
        when(adminAuthService.requireActiveAdmin(1L)).thenReturn(admin);
        when(accountMapper.selectOne(any())).thenReturn(target);

        AdminCreditAdjustReqDTO request = new AdminCreditAdjustReqDTO();
        request.setAmount(10);
        request.setDirection(-1);
        request.setReason("penalty");

        adminAccountService.adjustCredits(1L, "FC-USER01", request);

        verify(creditService).deductCredits(eq(2L), eq(10), eq(CreditTypeEnum.ADMIN_ADJUST_DECREASE), any(), eq("penalty"));
        verify(accountService).evictCacheByDbId(2L);
        verify(adminOperationLogService).record(any(AdminOperationLogDO.class));
    }

    @Test
    void getAccountDetailShouldReturnBannedAccountDetail() {
        AccountDO admin = buildAccount(1L, "FC-ADMIN01", "admin", AccountRoleEnum.ADMIN.getCode(), AccountStatusEnum.NORMAL.getCode(), 999);
        AccountDO target = buildAccount(2L, "FC-USER01", "user", AccountRoleEnum.USER.getCode(), AccountStatusEnum.BANNED.getCode(), 20);
        when(adminAuthService.requireActiveAdmin(1L)).thenReturn(admin);
        when(accountMapper.selectOne(any())).thenReturn(target);

        AdminAccountRespDTO result = adminAccountService.getAccountDetail(1L, "FC-USER01");

        assertNotNull(result);
        assertEquals("FC-USER01", result.getAccountId());
        assertEquals("系统封禁", result.getStatusDesc());
        assertEquals("普通用户", result.getSystemRoleDesc());
    }

    @Test
    void grantAdminShouldUpgradeTargetRoleAndRecordLog() {
        AccountDO admin = buildAccount(1L, "FC-ADMIN01", "admin", AccountRoleEnum.ADMIN.getCode(), AccountStatusEnum.NORMAL.getCode(), 999);
        AccountDO target = buildAccount(2L, "FC-USER01", "user", AccountRoleEnum.USER.getCode(), AccountStatusEnum.NORMAL.getCode(), 20);
        when(adminAuthService.requireActiveAdmin(1L)).thenReturn(admin);
        when(accountMapper.selectOne(any())).thenReturn(target);

        AdminOperationReasonReqDTO request = new AdminOperationReasonReqDTO();
        request.setReason("promote to admin");

        adminAccountService.grantAdmin(1L, "FC-USER01", request);

        ArgumentCaptor<AccountDO> accountCaptor = ArgumentCaptor.forClass(AccountDO.class);
        verify(accountService).updateById(accountCaptor.capture());
        assertEquals(AccountRoleEnum.ADMIN.getCode(), accountCaptor.getValue().getSystemRole());
        verify(accountService).evictCacheByDbId(2L);
        verify(adminOperationLogService).record(any(AdminOperationLogDO.class));
    }

    @Test
    void revokeAdminShouldDowngradeTargetRoleAndKickoutSessions() {
        AccountDO admin = buildAccount(1L, "FC-ADMIN01", "admin", AccountRoleEnum.ADMIN.getCode(), AccountStatusEnum.NORMAL.getCode(), 999);
        AccountDO target = buildAccount(2L, "FC-ADMIN02", "other-admin", AccountRoleEnum.ADMIN.getCode(), AccountStatusEnum.NORMAL.getCode(), 20);
        when(adminAuthService.requireActiveAdmin(1L)).thenReturn(admin);
        when(accountMapper.selectOne(any())).thenReturn(target);
        when(accountMapper.selectCount(any())).thenReturn(2L);

        AdminOperationReasonReqDTO request = new AdminOperationReasonReqDTO();
        request.setReason("rollback admin role");

        adminAccountService.revokeAdmin(1L, "FC-ADMIN02", request);

        ArgumentCaptor<AccountDO> accountCaptor = ArgumentCaptor.forClass(AccountDO.class);
        verify(accountService).updateById(accountCaptor.capture());
        assertEquals(AccountRoleEnum.USER.getCode(), accountCaptor.getValue().getSystemRole());
        verify(accountSessionService).kickoutAllSessions(target);
        verify(accountService).evictCacheByDbId(2L);
        verify(adminOperationLogService).record(any(AdminOperationLogDO.class));
    }

    @Test
    void revokeAdminShouldRejectSelfOperation() {
        AccountDO admin = buildAccount(1L, "FC-ADMIN01", "admin", AccountRoleEnum.ADMIN.getCode(), AccountStatusEnum.NORMAL.getCode(), 999);
        when(adminAuthService.requireActiveAdmin(1L)).thenReturn(admin);
        when(accountMapper.selectOne(any())).thenReturn(admin);

        AdminOperationReasonReqDTO request = new AdminOperationReasonReqDTO();
        request.setReason("self revoke");

        ClientException exception = assertThrows(ClientException.class,
                () -> adminAccountService.revokeAdmin(1L, "FC-ADMIN01", request));

        assertEquals("不能调整自己的管理员角色", exception.getMessage());
    }

    @Test
    void revokeAdminShouldRejectLastAdmin() {
        AccountDO admin = buildAccount(1L, "FC-ADMIN01", "admin", AccountRoleEnum.ADMIN.getCode(), AccountStatusEnum.NORMAL.getCode(), 999);
        AccountDO target = buildAccount(2L, "FC-ADMIN02", "other-admin", AccountRoleEnum.ADMIN.getCode(), AccountStatusEnum.NORMAL.getCode(), 20);
        when(adminAuthService.requireActiveAdmin(1L)).thenReturn(admin);
        when(accountMapper.selectOne(any())).thenReturn(target);
        when(accountMapper.selectCount(any())).thenReturn(1L);

        AdminOperationReasonReqDTO request = new AdminOperationReasonReqDTO();
        request.setReason("keep one admin");

        ClientException exception = assertThrows(ClientException.class,
                () -> adminAccountService.revokeAdmin(1L, "FC-ADMIN02", request));

        assertEquals("至少保留一个系统管理员账号", exception.getMessage());
    }

    private AccountDO buildAccount(Long id, String accountId, String nickname, Integer role, Integer status, Integer credits) {
        return AccountDO.builder()
                .id(id)
                .accountId(accountId)
                .nickname(nickname)
                .email(accountId.toLowerCase() + "@example.com")
                .avatarColor("#123456")
                .avatarUrl("")
                .credits(credits)
                .isRegistered(1)
                .systemRole(role)
                .status(status)
                .build();
    }
}
