package com.flashchat.userservice.service.feedback;

import com.flashchat.convention.exception.ClientException;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 用户反馈提交实现。
 */
@Service
@RequiredArgsConstructor
public class FeedbackServiceImpl implements FeedbackService {

    private static final String DEFAULT_SOURCE_PAGE = "feedback_center";
    private static final String DEFAULT_SOURCE_SCENE = "manual_submit";

    private final FeedbackMapper feedbackMapper;
    private final AccountService accountService;

    @Override
    public FeedbackSubmitRespDTO submitFeedback(SubmitFeedbackReqDTO request) {
        FeedbackTypeEnum feedbackType = FeedbackTypeEnum.fromName(trim(request.getFeedbackType()));
        if (feedbackType == null) {
            throw new ClientException("反馈类型不合法");
        }

        FeedbackDO feedbackDO = FeedbackDO.builder()
                .feedbackType(feedbackType.name())
                .content(trim(request.getContent()))
                .contact(trimToNull(request.getContact()))
                .screenshotUrl(trimToNull(request.getScreenshotUrl()))
                .willingContact(Boolean.TRUE.equals(request.getWillingContact()) ? 1 : 0)
                .sourcePage(defaultIfBlank(request.getSourcePage(), DEFAULT_SOURCE_PAGE))
                .sourceScene(defaultIfBlank(request.getSourceScene(), DEFAULT_SOURCE_SCENE))
                .status(FeedbackStatusEnum.NEW.name())
                .build();

        enrichSubmitter(feedbackDO);
        feedbackMapper.insert(feedbackDO);

        return FeedbackSubmitRespDTO.builder()
                .id(feedbackDO.getId())
                .status(feedbackDO.getStatus())
                .statusDesc(FeedbackStatusEnum.NEW.getDesc())
                .build();
    }

    private void enrichSubmitter(FeedbackDO feedbackDO) {
        Long loginId = UserContext.getLoginId();
        if (loginId == null) {
            feedbackDO.setAccountType(FeedbackAccountTypeEnum.GUEST.name());
            return;
        }

        AccountDO account = accountService.getAccountByDbId(loginId);
        if (account == null) {
            feedbackDO.setAccountType(FeedbackAccountTypeEnum.GUEST.name());
            return;
        }

        feedbackDO.setAccountId(account.getAccountId());
        feedbackDO.setNicknameSnapshot(account.getNickname());
        feedbackDO.setAccountType(account.registered()
                ? FeedbackAccountTypeEnum.REGISTERED.name()
                : FeedbackAccountTypeEnum.GUEST.name());
    }

    private String defaultIfBlank(String value, String defaultValue) {
        String trimmed = trimToNull(value);
        return trimmed == null ? defaultValue : trimmed;
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private String trimToNull(String value) {
        String trimmed = trim(value);
        return trimmed == null || trimmed.isBlank() ? null : trimmed;
    }
}
