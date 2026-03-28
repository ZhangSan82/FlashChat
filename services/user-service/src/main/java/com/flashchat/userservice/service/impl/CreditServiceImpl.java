package com.flashchat.userservice.service.impl;

import com.flashchat.convention.exception.ClientException;
import com.flashchat.userservice.dao.entity.AccountDO;
import com.flashchat.userservice.dao.entity.CreditTransactionDO;
import com.flashchat.userservice.dao.enums.CreditTypeEnum;
import com.flashchat.userservice.dao.mapper.AccountMapper;
import com.flashchat.userservice.dao.mapper.CreditTransactionMapper;
import com.flashchat.userservice.service.AccountService;
import com.flashchat.userservice.service.CreditService;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 积分服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditServiceImpl implements CreditService {

    private final CreditTransactionMapper creditTransactionMapper;
    private final AccountMapper accountMapper;


    @Lazy
    @Resource
    private AccountService accountService;

    @Override
    public boolean grantCredits(Long accountId, int amount, CreditTypeEnum type,
                                String bizId, String remark) {
        validateParams(accountId, amount, bizId);
        if (!type.isIncome()) {
            throw new ClientException(
                    "grantCredits 只接受收入类型，当前类型：" + type.name());
        }

        // ===== 1. 插入流水（幂等） =====
        String idempotentKey = buildIdempotentKey(type, bizId);
        CreditTransactionDO transaction = CreditTransactionDO.builder()
                .accountId(accountId)
                .amount(amount)
                .balanceAfter(null)
                .type(type.name())
                .bizId(bizId)
                .idempotentKey(idempotentKey)
                .remark(remark)
                .build();

        try {
            creditTransactionMapper.insert(transaction);
        } catch (DuplicateKeyException e) {
            log.warn("[积分-幂等跳过] grant accountId={}, type={}, bizId={}, key={}",
                    accountId, type.name(), bizId, idempotentKey);
            return false;
        }

        // ===== 2. 更新余额（收入无条件加） =====
        int affected = accountMapper.addCredits(accountId, amount);
        if (affected == 0) {
            throw new ClientException("账号不存在，无法增加积分");
        }

        // 不做缓存失效 — 由调用方在事务提交后处理
        log.info("[积分+{}] accountId={}, type={}, bizId={}, remark={}",
                amount, accountId, type.name(), bizId, remark);
        return true;
    }

    @Override
    public void deductCredits(Long accountId, int amount, CreditTypeEnum type,
                              String bizId, String remark) {
        validateParams(accountId, amount, bizId);
        if (!type.isExpense()) {
            throw new ClientException(
                    "deductCredits 只接受支出类型，当前类型：" + type.name());
        }

        // ===== 1. 插入流水（幂等） =====
        String idempotentKey = buildIdempotentKey(type, bizId);
        CreditTransactionDO transaction = CreditTransactionDO.builder()
                .accountId(accountId)
                .amount(-amount)
                .balanceAfter(null)
                .type(type.name())
                .bizId(bizId)
                .idempotentKey(idempotentKey)
                .remark(remark)
                .build();

        try {
            creditTransactionMapper.insert(transaction);
        } catch (DuplicateKeyException e) {
            log.warn("[积分-幂等跳过] deduct accountId={}, type={}, bizId={}, key={}",
                    accountId, type.name(), bizId, idempotentKey);
            return;
        }

        // ===== 2. CAS 扣减余额 =====
        int affected = accountMapper.deductCredits(accountId, amount);
        if (affected == 0) {
            throw new ClientException("积分不足，当前操作需要 " + amount + " 积分");
        }

        // 不做缓存失效 — 由调用方在事务提交后处理
        log.info("[积分-{}] accountId={}, type={}, bizId={}, remark={}",
                amount, accountId, type.name(), bizId, remark);
    }

    @Override
    public int getBalance(Long accountId) {
        if (accountId == null) {
            return 0;
        }
        AccountDO account = accountService.getAccountByDbId(accountId);
        return account != null && account.getCredits() != null
                ? account.getCredits() : 0;
    }

    // ==================== 私有方法 ====================

    private String buildIdempotentKey(CreditTypeEnum type, String bizId) {
        return type.name() + ":" + bizId;
    }

    private void validateParams(Long accountId, int amount, String bizId) {
        if (accountId == null) {
            throw new IllegalArgumentException("accountId 不能为 null");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount 必须大于 0，当前值：" + amount);
        }
        if (bizId == null || bizId.isBlank()) {
            throw new IllegalArgumentException("bizId 不能为空");
        }
    }
}