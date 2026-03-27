package com.flashchat.chatservice.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashchat.chatservice.dao.entity.AccountDO;
import com.flashchat.chatservice.dao.entity.InviteCodeDO;
import com.flashchat.chatservice.dao.mapper.InviteCodeMapper;
import com.flashchat.chatservice.dto.resp.InviteCodeRespDTO;
import com.flashchat.chatservice.service.AccountService;
import com.flashchat.chatservice.service.InviteCodeService;
import com.flashchat.convention.exception.ClientException;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@Slf4j
@RequiredArgsConstructor
public class InviteCodeServiceImpl extends ServiceImpl<InviteCodeMapper, InviteCodeDO>
        implements InviteCodeService {

    /**
     * @Lazy 打破循环依赖：
     * AccountServiceImpl → InviteCodeService（A5 升级时调用）
     * InviteCodeServiceImpl → AccountService（useCode 查邀请人信息）
     */
    @Lazy
    @Resource
    private  AccountService accountService;

    /** 邀请码字符集：大写字母 + 数字，去掉易混淆的 0/O/1/I/L */
    private static final String CODE_CHARS = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final int CODE_LENGTH = 8;
    private static final int MAX_RETRY = 20;

    @Override
    public void generateForUser(Long accountId, int count) {
        List<InviteCodeDO> codes = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            String code = generateUniqueCode();
            codes.add(InviteCodeDO.builder()
                    .code(code)
                    .ownerId(accountId)
                    .status(0)
                    .build());
        }

        this.saveBatch(codes);
        log.info("[生成邀请码] accountId={}, count={}", accountId, count);
    }

    @Override
    public Long useCode(String code, Long usedByAccountId) {
        if (code == null || code.isBlank()) {
            throw new ClientException("邀请码不能为空");
        }

        // 1. 查询邀请码
        InviteCodeDO inviteCode = this.lambdaQuery()
                .eq(InviteCodeDO::getCode, code.trim().toUpperCase())
                .one();

        if (inviteCode == null) {
            throw new ClientException("邀请码不存在");
        }

        if (inviteCode.getStatus() != null && inviteCode.getStatus() == 1) {
            throw new ClientException("邀请码已被使用");
        }

        // 2. 不能使用自己的邀请码
        if (inviteCode.getOwnerId() != null && inviteCode.getOwnerId().equals(usedByAccountId)) {
            throw new ClientException("不能使用自己的邀请码");
        }

        // 3. 标记已使用
        inviteCode.setUsedBy(usedByAccountId);
        inviteCode.setStatus(1);
        this.updateById(inviteCode);

        log.info("[使用邀请码] code={}, usedBy={}, owner={}",
                code, usedByAccountId, inviteCode.getOwnerId());

        return inviteCode.getOwnerId();
    }

    @Override
    public List<InviteCodeRespDTO> listMyCodes(Long accountId) {
        List<InviteCodeDO> codes = this.lambdaQuery()
                .eq(InviteCodeDO::getOwnerId, accountId)
                .orderByAsc(InviteCodeDO::getCreateTime)
                .list();

        if (codes == null || codes.isEmpty()) {
            return List.of();
        }

        List<InviteCodeRespDTO> result = new ArrayList<>(codes.size());
        for (InviteCodeDO code : codes) {
            // 查使用者的 accountId（展示用）
            String usedByAccountBizId = null;
            if (code.getUsedBy() != null) {
                AccountDO usedByAccount = accountService.getAccountByDbId(code.getUsedBy());
                if (usedByAccount != null) {
                    usedByAccountBizId = usedByAccount.getAccountId();
                }
            }

            result.add(InviteCodeRespDTO.builder()
                    .code(code.getCode())
                    .used(code.getStatus() != null && code.getStatus() == 1)
                    .usedByAccountId(usedByAccountBizId)
                    .createTime(code.getCreateTime())
                    .build());
        }

        return result;
    }

    // ==================== 私有方法 ====================

    /**
     * 生成唯一邀请码
     * 8 位大写字母+数字，DB 唯一索引兜底
     */
    private String generateUniqueCode() {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int retry = 0; retry < MAX_RETRY; retry++) {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
            }
            String code = sb.toString();

            // 检查是否已存在
            Long count = this.lambdaQuery()
                    .eq(InviteCodeDO::getCode, code)
                    .count();
            if (count == 0) {
                return code;
            }
        }

        return UUID.randomUUID().toString().replace("-", "")
                .substring(0, CODE_LENGTH).toUpperCase();
    }
}