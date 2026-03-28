package com.flashchat.userservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flashchat.convention.exception.ClientException;
import com.flashchat.userservice.dao.entity.AccountDO;
import com.flashchat.userservice.dao.entity.CreditTransactionDO;
import com.flashchat.userservice.dao.enums.CreditTypeEnum;
import com.flashchat.userservice.dao.mapper.AccountMapper;
import com.flashchat.userservice.dao.mapper.CreditTransactionMapper;
import com.flashchat.userservice.dto.resp.CreditTransactionRespDTO;
import com.flashchat.userservice.service.AccountService;
import com.flashchat.userservice.service.CreditService;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.util.List;

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
    @Override
    public List<CreditTransactionRespDTO> getTransactions(Long accountId, Integer page, Integer size) {
        if (accountId == null) {
            return List.of();
        }
        // 分页查询
        Page<CreditTransactionDO> pageParam = new Page<>(page, size, false);
        LambdaQueryWrapper<CreditTransactionDO> wrapper = new LambdaQueryWrapper<CreditTransactionDO>()
                .eq(CreditTransactionDO::getAccountId, accountId)
                .orderByDesc(CreditTransactionDO::getCreateTime);

        Page<CreditTransactionDO> result = creditTransactionMapper.selectPage(pageParam, wrapper);
        List<CreditTransactionDO> records = result.getRecords();
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return records.stream()
                .map(this::convertToRespDTO)
                .toList();
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

    /**
     * DO → DTO 转换
     * <p>
     * typeDesc 从枚举取中文描述，找不到就用原始 type 值兜底。
     */
    private CreditTransactionRespDTO convertToRespDTO(CreditTransactionDO record) {
        String typeDesc = record.getType();
        try {
            CreditTypeEnum typeEnum = CreditTypeEnum.valueOf(record.getType());
            typeDesc = typeEnum.getDesc();
        } catch (IllegalArgumentException ignored) {
            // 枚举名不匹配（可能是历史遗留数据），用原始值兜底
        }

        return CreditTransactionRespDTO.builder()
                .id(record.getId())
                .amount(record.getAmount())
                .typeDesc(typeDesc)
                .type(record.getType())
                .bizId(record.getBizId())
                .remark(record.getRemark())
                .createTime(record.getCreateTime())
                .build();
    }
}