package com.flashchat.chatservice.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashchat.cache.DistributedCache;
import com.flashchat.cache.toolkit.CacheUtil;
import com.flashchat.chatservice.dao.entity.AccountDO;
import com.flashchat.chatservice.dao.enums.AccountStatusEnum;
import com.flashchat.chatservice.dao.mapper.AccountMapper;
import com.flashchat.chatservice.dto.resp.AccountInfoRespDTO;
import com.flashchat.chatservice.service.AccountService;
import com.flashchat.chatservice.toolkit.HashUtil;
import com.flashchat.convention.exception.ClientException;
import com.flashchat.convention.exception.ServiceException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@Slf4j
@AllArgsConstructor
public class AccountServiceImpl extends ServiceImpl<AccountMapper, AccountDO>
        implements AccountService {

    @Qualifier("flashChatAccountRegisterCachePenetrationBloomFilter")
    private final RBloomFilter<String> accountBloomFilter;

    private final DistributedCache distributedCache;

    private static final long CACHE_TIMEOUT = 60000L;

    // ==================== 随机昵称词库 ====================
    private static final String[] ADJ = {
            "神秘的", "可爱的", "暴躁的", "温柔的", "沉默的",
            "快乐的", "忧伤的", "勇敢的", "害羞的", "优雅的",
            "调皮的", "冷静的", "热情的", "迷糊的", "机智的"
    };
    private static final String[] NOUN = {
            "猫咪", "兔子", "企鹅", "熊猫", "水母",
            "狐狸", "松鼠", "鹦鹉", "猫头鹰", "海豚",
            "柴犬", "考拉", "树懒", "浣熊", "刺猬"
    };

    @Transactional
    @Override
    public AccountInfoRespDTO autoRegister() {
        // 1. 生成 accountId，确保唯一
        String accountId = generateUniqueAccountId();

        // 2. 生成随机昵称 + 头像色
        String nickname = generateNickname();
        String avatarColor = generateAvatarColor();

        // 3. 创建记录（匿名阶段）
        AccountDO account = AccountDO.builder()
                .accountId(accountId)
                .nickname(nickname)
                .avatarColor(avatarColor)
                .avatarUrl("")
                .password("")           // 未设置密码
                .email(null)            // 匿名用户
                .inviteCode(null)       // 匿名用户
                .invitedBy(null)
                .credits(0)
                .isRegistered(0)        // 匿名
                .status(1)              // 正常
                .build();

        try {
            this.save(account);

            // 布隆过滤器 + 缓存
            String cacheKeyByBizId = CacheUtil.buildKey("flashchat", "account", accountId);
            String cacheKeyByDbId = CacheUtil.buildKey("flashchat", "account", "id", String.valueOf(account.getId()));

            accountBloomFilter.add(cacheKeyByBizId);
            accountBloomFilter.add(cacheKeyByDbId);

            distributedCache.put(cacheKeyByBizId, account, CACHE_TIMEOUT);
            distributedCache.put(cacheKeyByDbId, account, CACHE_TIMEOUT);

        } catch (Exception e) {
            log.error("[注册失败] {}", e.getMessage(), e);
            throw new ServiceException(e.getMessage());
        }

        log.info("[匿名注册] id={}, accountId={}, nickname={}", account.getId(), accountId, nickname);

        return AccountInfoRespDTO.builder()
                .accountId(accountId)
                .nickname(nickname)
                .avatarColor(avatarColor)
                .avatarUrl("")
                .isRegistered(false)
                .build();
    }

    @Override
    public AccountInfoRespDTO getAccountInfoByAccountId(String accountId) {
        AccountDO account = getByAccountId(accountId);
        return AccountInfoRespDTO.builder()
                .accountId(account.getAccountId())
                .nickname(account.getNickname())
                .avatarColor(account.getAvatarColor())
                .avatarUrl(account.getAvatarUrl())
                .isRegistered(account.getIsRegistered() != null && account.getIsRegistered() == 1)
                .build();
    }

    @Override
    public AccountDO getByAccountId(String accountId) {
        AccountDO account = distributedCache.safeGet(
                CacheUtil.buildKey("flashchat", "account", accountId),
                AccountDO.class,
                () -> this.lambdaQuery().eq(AccountDO::getAccountId, accountId).one(),
                CACHE_TIMEOUT,
                accountBloomFilter
        );
        if (account == null) {
            throw new ClientException("账号不存在");
        }
        if (account.getStatus() != null && account.getStatus() == AccountStatusEnum.BANNED.getCode()) {
            throw new ClientException("账号已被封禁");
        }
        return account;
    }

    @Override
    public AccountDO getAccountByDbId(Long id) {
        if (id == null) {
            return null;
        }
        return distributedCache.safeGet(
                CacheUtil.buildKey("flashchat", "account", "id", String.valueOf(id)),
                AccountDO.class,
                () -> this.getById(id),
                CACHE_TIMEOUT,
                accountBloomFilter
        );
    }

    // ==================== 私有方法 ====================

    private String generateUniqueAccountId() {
        int retryCount = 0;
        String accountId;
        String SEED_PREFIX = "flashchat:AccountId:";
        while (true) {
            if (retryCount > 10) {
                throw new ServiceException("账号 ID 频繁生成，请稍后再试");
            }
            accountId = HashUtil.hashToBase62(SEED_PREFIX + UUID.randomUUID());
            if (!accountBloomFilter.contains(CacheUtil.buildKey("flashchat", "account", accountId))) {
                break;
            }
            retryCount++;
        }
        return "FC-" + accountId;
    }

    private String generateNickname() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return ADJ[r.nextInt(ADJ.length)] + NOUN[r.nextInt(NOUN.length)];
    }

    private String generateAvatarColor() {
        String[] colors = {
                "#FF6B6B", "#4ECDC4", "#45B7D1", "#96CEB4",
                "#FFEAA7", "#DDA0DD", "#98D8C8", "#F7DC6F",
                "#BB8FCE", "#85C1E9", "#F0B27A", "#82E0AA",
                "#D35400", "#8E44AD", "#2980B9", "#27AE60"
        };
        return colors[ThreadLocalRandom.current().nextInt(colors.length)];
    }
}