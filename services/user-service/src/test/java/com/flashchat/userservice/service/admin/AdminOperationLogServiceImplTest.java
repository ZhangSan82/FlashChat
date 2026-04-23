package com.flashchat.userservice.service.admin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flashchat.userservice.dao.entity.AccountDO;
import com.flashchat.userservice.dao.entity.AdminOperationLogDO;
import com.flashchat.userservice.dao.enums.AccountRoleEnum;
import com.flashchat.userservice.dao.enums.AccountStatusEnum;
import com.flashchat.userservice.dao.enums.AdminOperationTargetTypeEnum;
import com.flashchat.userservice.dao.enums.AdminOperationTypeEnum;
import com.flashchat.userservice.dao.mapper.AdminOperationLogMapper;
import com.flashchat.userservice.dto.req.AdminOperationLogQueryReqDTO;
import com.flashchat.userservice.dto.resp.AdminOperationLogRespDTO;
import com.flashchat.userservice.dto.resp.AdminPageRespDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminOperationLogServiceImplTest {

    @Mock
    private AdminOperationLogMapper adminOperationLogMapper;

    @Mock
    private AdminAuthService adminAuthService;

    private AdminOperationLogService adminOperationLogService;

    @BeforeEach
    void setUp() {
        adminOperationLogService = new AdminOperationLogServiceImpl(adminOperationLogMapper, adminAuthService);
    }

    @Test
    void searchLogsShouldReturnPagedResult() {
        AccountDO admin = buildAccount(1L, "FC-ADMIN01");
        when(adminAuthService.requireActiveAdmin(1L)).thenReturn(admin);
        when(adminOperationLogMapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<AdminOperationLogDO> page = invocation.getArgument(0);
            page.setTotal(1L);
            page.setRecords(List.of(AdminOperationLogDO.builder()
                    .id(10L)
                    .operatorId(1L)
                    .operatorAccountId("FC-ADMIN01")
                    .operationType(AdminOperationTypeEnum.ACCOUNT_GRANT_ADMIN.name())
                    .targetType(AdminOperationTargetTypeEnum.ACCOUNT.name())
                    .targetId("FC-USER01")
                    .targetDisplay("user")
                    .reason("promote")
                    .detailJson("{\"afterRole\":1}")
                    .createTime(LocalDateTime.of(2026, 4, 18, 18, 0))
                    .build()));
            return page;
        });

        AdminOperationLogQueryReqDTO request = new AdminOperationLogQueryReqDTO();
        request.setOperationType(AdminOperationTypeEnum.ACCOUNT_GRANT_ADMIN.name());
        request.setPage(1L);
        request.setSize(10L);

        AdminPageRespDTO<AdminOperationLogRespDTO> result = adminOperationLogService.searchLogs(1L, request);

        assertEquals(1L, result.getTotal());
        assertEquals(1, result.getRecords().size());
        assertEquals("FC-ADMIN01", result.getRecords().get(0).getOperatorAccountId());
        assertEquals("授予管理员", result.getRecords().get(0).getOperationTypeDesc());
        assertEquals("账号", result.getRecords().get(0).getTargetTypeDesc());
    }

    @Test
    void recordShouldIgnoreNullInput() {
        adminOperationLogService.record(null);

        verifyNoInteractions(adminOperationLogMapper);
    }

    private AccountDO buildAccount(Long id, String accountId) {
        return AccountDO.builder()
                .id(id)
                .accountId(accountId)
                .systemRole(AccountRoleEnum.ADMIN.getCode())
                .status(AccountStatusEnum.NORMAL.getCode())
                .isRegistered(1)
                .build();
    }
}
