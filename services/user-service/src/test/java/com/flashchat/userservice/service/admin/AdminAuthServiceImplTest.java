package com.flashchat.userservice.service.admin;

import com.flashchat.convention.exception.ClientException;
import com.flashchat.userservice.dao.entity.AccountDO;
import com.flashchat.userservice.dao.enums.AccountRoleEnum;
import com.flashchat.userservice.dao.enums.AccountStatusEnum;
import com.flashchat.userservice.service.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAuthServiceImplTest {

    @Mock
    private AccountService accountService;

    private AdminAuthService adminAuthService;

    @BeforeEach
    void setUp() {
        adminAuthService = new AdminAuthServiceImpl(accountService);
    }

    @Test
    void requireActiveAdminShouldReturnAccountWhenOperatorIsNormalAdmin() {
        AccountDO admin = buildAccount(100L, "FC-ADMIN01", AccountRoleEnum.ADMIN.getCode(), AccountStatusEnum.NORMAL.getCode());
        when(accountService.getAccountByDbId(100L)).thenReturn(admin);

        AccountDO result = adminAuthService.requireActiveAdmin(100L);

        assertEquals(admin, result);
    }

    @Test
    void requireActiveAdminShouldRejectNonAdminAccount() {
        AccountDO user = buildAccount(101L, "FC-USER01", AccountRoleEnum.USER.getCode(), AccountStatusEnum.NORMAL.getCode());
        when(accountService.getAccountByDbId(101L)).thenReturn(user);

        assertThrows(ClientException.class, () -> adminAuthService.requireActiveAdmin(101L));
    }

    @Test
    void requireActiveAdminShouldRejectBannedAccount() {
        AccountDO bannedAdmin = buildAccount(102L, "FC-ADMIN02", AccountRoleEnum.ADMIN.getCode(), AccountStatusEnum.BANNED.getCode());
        when(accountService.getAccountByDbId(102L)).thenReturn(bannedAdmin);

        assertThrows(ClientException.class, () -> adminAuthService.requireActiveAdmin(102L));
    }

    private AccountDO buildAccount(Long id, String accountId, Integer role, Integer status) {
        return AccountDO.builder()
                .id(id)
                .accountId(accountId)
                .nickname("tester")
                .isRegistered(1)
                .systemRole(role)
                .status(status)
                .credits(100)
                .build();
    }
}
