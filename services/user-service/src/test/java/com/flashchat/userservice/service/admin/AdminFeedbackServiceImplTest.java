package com.flashchat.userservice.service.admin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flashchat.convention.exception.ClientException;
import com.flashchat.convention.storage.OssAssetUrlService;
import com.flashchat.userservice.dao.entity.AccountDO;
import com.flashchat.userservice.dao.entity.AdminOperationLogDO;
import com.flashchat.userservice.dao.entity.FeedbackDO;
import com.flashchat.userservice.dao.enums.AccountRoleEnum;
import com.flashchat.userservice.dao.enums.AccountStatusEnum;
import com.flashchat.userservice.dao.enums.AdminOperationTargetTypeEnum;
import com.flashchat.userservice.dao.enums.AdminOperationTypeEnum;
import com.flashchat.userservice.dao.enums.FeedbackAccountTypeEnum;
import com.flashchat.userservice.dao.enums.FeedbackStatusEnum;
import com.flashchat.userservice.dao.enums.FeedbackTypeEnum;
import com.flashchat.userservice.dao.mapper.FeedbackMapper;
import com.flashchat.userservice.dto.req.AdminFeedbackProcessReqDTO;
import com.flashchat.userservice.dto.req.AdminFeedbackQueryReqDTO;
import com.flashchat.userservice.dto.resp.AdminFeedbackRespDTO;
import com.flashchat.userservice.dto.resp.AdminPageRespDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminFeedbackServiceImplTest {

    @Mock
    private AdminAuthService adminAuthService;

    @Mock
    private FeedbackMapper feedbackMapper;

    @Mock
    private AdminOperationLogService adminOperationLogService;

    @Mock
    private OssAssetUrlService ossAssetUrlService;

    private AdminFeedbackService adminFeedbackService;

    @BeforeEach
    void setUp() {
        adminFeedbackService = new AdminFeedbackServiceImpl(
                adminAuthService,
                feedbackMapper,
                adminOperationLogService,
                ossAssetUrlService
        );
    }

    @Test
    void searchFeedbacksShouldReturnPagedResult() {
        when(adminAuthService.requireActiveAdmin(1L)).thenReturn(buildAdmin());
        when(feedbackMapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<FeedbackDO> page = invocation.getArgument(0);
            page.setRecords(List.of(buildFeedback()));
            page.setTotal(1L);
            return page;
        });

        AdminFeedbackQueryReqDTO request = new AdminFeedbackQueryReqDTO();
        request.setStatus(FeedbackStatusEnum.NEW.name());
        request.setFeedbackType(FeedbackTypeEnum.BUG.name());
        request.setPage(1L);
        request.setSize(10L);

        AdminPageRespDTO<AdminFeedbackRespDTO> result = adminFeedbackService.searchFeedbacks(1L, request);

        assertEquals(1L, result.getTotal());
        assertEquals(1, result.getRecords().size());
        assertEquals("Bug 反馈", result.getRecords().get(0).getFeedbackTypeDesc());
        assertEquals("待处理", result.getRecords().get(0).getStatusDesc());
    }

    @Test
    void getFeedbackDetailShouldReturnSingleFeedback() {
        when(adminAuthService.requireActiveAdmin(1L)).thenReturn(buildAdmin());
        when(feedbackMapper.selectById(18L)).thenReturn(buildFeedback());

        AdminFeedbackRespDTO result = adminFeedbackService.getFeedbackDetail(1L, 18L);

        assertEquals(18L, result.getId());
        assertEquals("扫码进入房间后页面白屏", result.getContent());
        assertEquals("FC-USER09", result.getAccountId());
    }

    @Test
    void processFeedbackShouldUpdateStatusReplyAndRecordLog() {
        when(adminAuthService.requireActiveAdmin(1L)).thenReturn(buildAdmin());
        when(feedbackMapper.selectById(18L)).thenReturn(buildFeedback());

        AdminFeedbackProcessReqDTO request = new AdminFeedbackProcessReqDTO();
        request.setStatus(FeedbackStatusEnum.PROCESSING.name());
        request.setReply("已经复现，开始排查扫码链路");

        adminFeedbackService.processFeedback(1L, 18L, request);

        ArgumentCaptor<FeedbackDO> feedbackCaptor = ArgumentCaptor.forClass(FeedbackDO.class);
        verify(feedbackMapper).updateById(feedbackCaptor.capture());
        assertEquals(FeedbackStatusEnum.PROCESSING.name(), feedbackCaptor.getValue().getStatus());
        assertEquals("已经复现，开始排查扫码链路", feedbackCaptor.getValue().getReply());

        ArgumentCaptor<AdminOperationLogDO> logCaptor = ArgumentCaptor.forClass(AdminOperationLogDO.class);
        verify(adminOperationLogService).record(logCaptor.capture());
        assertEquals(AdminOperationTypeEnum.FEEDBACK_PROCESS.name(), logCaptor.getValue().getOperationType());
        assertEquals(AdminOperationTargetTypeEnum.FEEDBACK.name(), logCaptor.getValue().getTargetType());
    }

    @Test
    void processFeedbackShouldRequireReplyWhenResolved() {
        when(adminAuthService.requireActiveAdmin(1L)).thenReturn(buildAdmin());
        when(feedbackMapper.selectById(18L)).thenReturn(buildFeedback());

        AdminFeedbackProcessReqDTO request = new AdminFeedbackProcessReqDTO();
        request.setStatus(FeedbackStatusEnum.RESOLVED.name());
        request.setReply(" ");

        ClientException exception = assertThrows(ClientException.class,
                () -> adminFeedbackService.processFeedback(1L, 18L, request));

        assertEquals("反馈处理备注不能为空", exception.getMessage());
    }

    private AccountDO buildAdmin() {
        return AccountDO.builder()
                .id(1L)
                .accountId("FC-ADMIN01")
                .nickname("admin")
                .isRegistered(1)
                .systemRole(AccountRoleEnum.ADMIN.getCode())
                .status(AccountStatusEnum.NORMAL.getCode())
                .build();
    }

    private FeedbackDO buildFeedback() {
        return FeedbackDO.builder()
                .id(18L)
                .accountId("FC-USER09")
                .nicknameSnapshot("alice")
                .accountType(FeedbackAccountTypeEnum.REGISTERED.name())
                .feedbackType(FeedbackTypeEnum.BUG.name())
                .content("扫码进入房间后页面白屏")
                .contact("alice@example.com")
                .sourcePage("welcome")
                .sourceScene("scan_enter_room")
                .status(FeedbackStatusEnum.NEW.name())
                .build();
    }
}
