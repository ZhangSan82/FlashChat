package com.flashchat.userservice.service.feedback;

import com.flashchat.user.constant.UserTypeConstant;
import com.flashchat.user.core.LoginUserInfoDTO;
import com.flashchat.user.core.UserContext;
import com.flashchat.userservice.dao.entity.AccountDO;
import com.flashchat.userservice.dao.entity.FeedbackDO;
import com.flashchat.userservice.dao.enums.FeedbackAccountTypeEnum;
import com.flashchat.userservice.dao.enums.FeedbackStatusEnum;
import com.flashchat.userservice.dao.enums.FeedbackTypeEnum;
import com.flashchat.userservice.dao.mapper.FeedbackMapper;
import com.flashchat.userservice.dto.req.SubmitFeedbackReqDTO;
import com.flashchat.userservice.dto.resp.FeedbackSubmitRespDTO;
import com.flashchat.userservice.service.AccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedbackServiceImplTest {

    @Mock
    private FeedbackMapper feedbackMapper;

    @Mock
    private AccountService accountService;

    private FeedbackService feedbackService;

    @BeforeEach
    void setUp() {
        feedbackService = new FeedbackServiceImpl(feedbackMapper, accountService);
    }

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
    }

    @Test
    void submitFeedbackShouldCreateGuestFeedbackWhenNoLoginContext() {
        when(feedbackMapper.insert(any(FeedbackDO.class))).thenAnswer(invocation -> {
            FeedbackDO feedbackDO = invocation.getArgument(0);
            feedbackDO.setId(11L);
            return 1;
        });

        SubmitFeedbackReqDTO request = new SubmitFeedbackReqDTO();
        request.setFeedbackType(FeedbackTypeEnum.BUG.name());
        request.setContent("扫码进入房间后页面白屏");
        request.setSourcePage("welcome");
        request.setSourceScene("scan_enter_room");

        FeedbackSubmitRespDTO result = feedbackService.submitFeedback(request);

        ArgumentCaptor<FeedbackDO> captor = ArgumentCaptor.forClass(FeedbackDO.class);
        verify(feedbackMapper).insert(captor.capture());
        FeedbackDO inserted = captor.getValue();

        assertEquals(FeedbackAccountTypeEnum.GUEST.name(), inserted.getAccountType());
        assertEquals(FeedbackStatusEnum.NEW.name(), inserted.getStatus());
        assertEquals("welcome", inserted.getSourcePage());
        assertEquals("scan_enter_room", inserted.getSourceScene());
        assertEquals("扫码进入房间后页面白屏", inserted.getContent());
        assertNotNull(result);
        assertEquals(11L, result.getId());
        assertEquals(FeedbackStatusEnum.NEW.name(), result.getStatus());
    }

    @Test
    void submitFeedbackShouldAttachRegisteredAccountSnapshotWhenUserLoggedIn() {
        LoginUserInfoDTO userInfo = LoginUserInfoDTO.builder()
                .loginId(9L)
                .userType(UserTypeConstant.USER)
                .accountId("FC-USER09")
                .nickname("alice")
                .systemRole(0)
                .build();
        UserContext.setUser(userInfo);

        AccountDO account = AccountDO.builder()
                .id(9L)
                .accountId("FC-USER09")
                .nickname("alice")
                .isRegistered(1)
                .build();
        when(accountService.getAccountByDbId(9L)).thenReturn(account);

        SubmitFeedbackReqDTO request = new SubmitFeedbackReqDTO();
        request.setFeedbackType(FeedbackTypeEnum.ACCOUNT.name());
        request.setContent("登录后资料页头像没有立即刷新");
        request.setContact("alice@example.com");
        request.setWillingContact(true);

        feedbackService.submitFeedback(request);

        ArgumentCaptor<FeedbackDO> captor = ArgumentCaptor.forClass(FeedbackDO.class);
        verify(feedbackMapper).insert(captor.capture());
        FeedbackDO inserted = captor.getValue();

        assertEquals("FC-USER09", inserted.getAccountId());
        assertEquals("alice", inserted.getNicknameSnapshot());
        assertEquals(FeedbackAccountTypeEnum.REGISTERED.name(), inserted.getAccountType());
        assertEquals("feedback_center", inserted.getSourcePage());
        assertEquals("manual_submit", inserted.getSourceScene());
        assertEquals(Integer.valueOf(1), inserted.getWillingContact());
    }
}
