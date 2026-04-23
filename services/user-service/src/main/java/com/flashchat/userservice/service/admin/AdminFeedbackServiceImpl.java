package com.flashchat.userservice.service.admin;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flashchat.convention.exception.ClientException;
import com.flashchat.convention.storage.OssAssetUrlService;
import com.flashchat.userservice.dao.entity.AccountDO;
import com.flashchat.userservice.dao.entity.AdminOperationLogDO;
import com.flashchat.userservice.dao.entity.FeedbackDO;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理员反馈处理实现。
 */
@Service
@RequiredArgsConstructor
public class AdminFeedbackServiceImpl implements AdminFeedbackService {

    private final AdminAuthService adminAuthService;
    private final FeedbackMapper feedbackMapper;
    private final AdminOperationLogService adminOperationLogService;
    private final OssAssetUrlService ossAssetUrlService;

    @Override
    public AdminPageRespDTO<AdminFeedbackRespDTO> searchFeedbacks(Long operatorId, AdminFeedbackQueryReqDTO request) {
        adminAuthService.requireActiveAdmin(operatorId);

        long pageNo = request.getPage() == null || request.getPage() < 1 ? 1L : request.getPage();
        long size = request.getSize() == null || request.getSize() < 1 ? 20L : Math.min(request.getSize(), 100L);

        LambdaQueryWrapper<FeedbackDO> wrapper = new LambdaQueryWrapper<>();
        if (hasText(request.getStatus())) {
            wrapper.eq(FeedbackDO::getStatus, request.getStatus().trim());
        }
        if (hasText(request.getFeedbackType())) {
            wrapper.eq(FeedbackDO::getFeedbackType, request.getFeedbackType().trim());
        }
        if (hasText(request.getAccountType())) {
            wrapper.eq(FeedbackDO::getAccountType, request.getAccountType().trim());
        }
        if (hasText(request.getSourcePage())) {
            wrapper.eq(FeedbackDO::getSourcePage, request.getSourcePage().trim());
        }
        if (hasText(request.getSourceScene())) {
            wrapper.eq(FeedbackDO::getSourceScene, request.getSourceScene().trim());
        }
        if (request.getStartTime() != null) {
            wrapper.ge(FeedbackDO::getCreateTime, request.getStartTime());
        }
        if (request.getEndTime() != null) {
            wrapper.le(FeedbackDO::getCreateTime, request.getEndTime());
        }
        if (hasText(request.getKeyword())) {
            String keyword = request.getKeyword().trim();
            wrapper.and(q -> q.like(FeedbackDO::getContent, keyword)
                    .or()
                    .like(FeedbackDO::getAccountId, keyword)
                    .or()
                    .like(FeedbackDO::getNicknameSnapshot, keyword)
                    .or()
                    .like(FeedbackDO::getContact, keyword));
        }
        wrapper.orderByDesc(FeedbackDO::getId);

        Page<FeedbackDO> result = feedbackMapper.selectPage(new Page<>(pageNo, size), wrapper);
        List<AdminFeedbackRespDTO> records = result.getRecords().stream()
                .map(this::toResp)
                .toList();

        return AdminPageRespDTO.<AdminFeedbackRespDTO>builder()
                .page(pageNo)
                .size(size)
                .total(result.getTotal())
                .records(records)
                .build();
    }

    @Override
    public AdminFeedbackRespDTO getFeedbackDetail(Long operatorId, Long feedbackId) {
        adminAuthService.requireActiveAdmin(operatorId);
        return toResp(getRequiredFeedback(feedbackId));
    }

    @Override
    public void processFeedback(Long operatorId, Long feedbackId, AdminFeedbackProcessReqDTO request) {
        AccountDO operator = adminAuthService.requireActiveAdmin(operatorId);
        FeedbackDO feedback = getRequiredFeedback(feedbackId);
        FeedbackStatusEnum statusEnum = FeedbackStatusEnum.fromName(trim(request.getStatus()));
        if (statusEnum == null) {
            throw new ClientException("反馈状态不合法");
        }

        String reply = trimToNull(request.getReply());
        if ((statusEnum == FeedbackStatusEnum.RESOLVED || statusEnum == FeedbackStatusEnum.CLOSED)
                && !hasText(reply)) {
            throw new ClientException("反馈处理备注不能为空");
        }

        FeedbackDO update = FeedbackDO.builder()
                .id(feedbackId)
                .status(statusEnum.name())
                .reply(reply)
                .build();
        feedbackMapper.updateById(update);

        adminOperationLogService.record(AdminOperationLogDO.builder()
                .operatorId(operator.getId())
                .operatorAccountId(operator.getAccountId())
                .operationType(AdminOperationTypeEnum.FEEDBACK_PROCESS.name())
                .targetType(AdminOperationTargetTypeEnum.FEEDBACK.name())
                .targetId(String.valueOf(feedbackId))
                .targetDisplay(buildTargetDisplay(feedback))
                .reason(hasText(reply) ? reply : "反馈状态变更为 " + statusEnum.getDesc())
                .detailJson(buildDetailJson(statusEnum, reply))
                .build());
    }

    private FeedbackDO getRequiredFeedback(Long feedbackId) {
        FeedbackDO feedback = feedbackMapper.selectById(feedbackId);
        if (feedback == null) {
            throw new ClientException("反馈记录不存在");
        }
        return feedback;
    }

    private AdminFeedbackRespDTO toResp(FeedbackDO feedback) {
        FeedbackTypeEnum feedbackType = FeedbackTypeEnum.fromName(feedback.getFeedbackType());
        FeedbackStatusEnum status = FeedbackStatusEnum.fromName(feedback.getStatus());
        FeedbackAccountTypeEnum accountType = FeedbackAccountTypeEnum.fromName(feedback.getAccountType());
        return AdminFeedbackRespDTO.builder()
                .id(feedback.getId())
                .accountId(feedback.getAccountId())
                .nicknameSnapshot(feedback.getNicknameSnapshot())
                .accountType(feedback.getAccountType())
                .accountTypeDesc(accountType == null ? feedback.getAccountType() : accountType.getDesc())
                .feedbackType(feedback.getFeedbackType())
                .feedbackTypeDesc(feedbackType == null ? feedback.getFeedbackType() : feedbackType.getDesc())
                .content(feedback.getContent())
                .contentPreview(buildContentPreview(feedback.getContent()))
                .contact(feedback.getContact())
                .screenshotUrl(ossAssetUrlService.resolveAccessUrl(feedback.getScreenshotUrl()))
                .willingContact(feedback.getWillingContact() != null && feedback.getWillingContact() == 1)
                .sourcePage(feedback.getSourcePage())
                .sourceScene(feedback.getSourceScene())
                .status(feedback.getStatus())
                .statusDesc(status == null ? feedback.getStatus() : status.getDesc())
                .reply(feedback.getReply())
                .createTime(feedback.getCreateTime())
                .updateTime(feedback.getUpdateTime())
                .build();
    }

    private String buildContentPreview(String content) {
        if (!hasText(content)) {
            return "";
        }
        String trimmed = content.trim();
        return trimmed.length() <= 60 ? trimmed : trimmed.substring(0, 60) + "...";
    }

    private String buildTargetDisplay(FeedbackDO feedback) {
        String identity = hasText(feedback.getAccountId()) ? feedback.getAccountId() : "anonymous";
        return "feedback#" + feedback.getId() + " (" + identity + ")";
    }

    private String buildDetailJson(FeedbackStatusEnum statusEnum, String reply) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("status", statusEnum.name());
        detail.put("reply", reply);
        return JSONUtil.toJsonStr(detail);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private String trimToNull(String value) {
        String trimmed = trim(value);
        return trimmed == null || trimmed.isBlank() ? null : trimmed;
    }
}
