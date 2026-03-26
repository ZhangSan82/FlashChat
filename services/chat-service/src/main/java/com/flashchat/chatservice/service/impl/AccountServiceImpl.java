package com.flashchat.chatservice.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashchat.cache.MultistageCacheProxy;
import com.flashchat.cache.toolkit.CacheUtil;
import com.flashchat.chatservice.dao.entity.AccountDO;
import com.flashchat.chatservice.dao.enums.AccountStatusEnum;
import com.flashchat.chatservice.dao.mapper.AccountMapper;
import com.flashchat.chatservice.dto.resp.AccountInfoRespDTO;
import com.flashchat.chatservice.dto.resp.AuthRespDTO;
import com.flashchat.chatservice.service.AccountService;
import com.flashchat.chatservice.toolkit.HashUtil;
import com.flashchat.convention.exception.ClientException;
import com.flashchat.convention.exception.ServiceException;
import com.flashchat.user.constant.UserTypeConstant;
import com.flashchat.user.core.LoginUserInfoDTO;
import com.flashchat.user.toolkit.LoginIdUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@Slf4j
@AllArgsConstructor
public class AccountServiceImpl extends ServiceImpl<AccountMapper, AccountDO>
        implements AccountService {

    @Qualifier("flashChatAccountRegisterCachePenetrationBloomFilter")
    private final RBloomFilter<String> accountBloomFilter;

    /** 编程式事务，替代 @Transactional 解决同类内部调用不走代理的问题 */
    private final TransactionTemplate transactionTemplate;

    private final MultistageCacheProxy multistageCacheProxy;

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


    /**
     * 匿名注册 + 自动登录（门面方法）
     * <p>
     * 不加 @Transactional，内部编排三步调用，每步职责独立：
     * <ol>
     *   <li>doRegister：编程式事务内完成 DB save，事务提交后才继续</li>
     *   <li>postRegisterCache：Redis 操作（布隆过滤器 + 缓存），失败不影响注册</li>
     *   <li>doLogin：SaToken 登录，签发 token</li>
     * </ol>
     * <p>
     * 为什么 doRegister 用编程式事务而不是 @Transactional：
     * Spring 的声明式事务基于 AOP 代理，同类内部方法调用不走代理，
     * 导致 @Transactional 失效。TransactionTemplate 不依赖代理，直接控制事务边界。
     */
    @Override
    public AuthRespDTO autoRegister() {
        // 1. DB 事务
        AccountDO account = doRegister();

        // 2. 缓存（事务已提交，Redis 失败不影响注册结果）
        postRegisterCache(account);

        // 3. SaToken 登录
        return doLogin(account, UserTypeConstant.MEMBER);
    }


    /**
     * 执行 SaToken 登录
     * <p>
     * 三步操作必须配对：login → session.set → getTokenValue
     * <ol>
     *   <li>StpUtil.login(loginId)：在 Redis 中创建 token↔loginId 双向映射</li>
     *   <li>session.set：将用户信息存入 SaSession（Redis 中以 loginId 为 key 的 Hash）</li>
     *   <li>getTokenValue：获取刚签发的 token，放入响应返回前端</li>
     * </ol>
     */
    @Override
    public AuthRespDTO doLogin(AccountDO account, int userType) {
        // 1. 构建 loginId（前缀格式：member_7 或 user_7）
        String loginId = LoginIdUtil.toLoginId(account.getId(), userType);

        // 2. SaToken 登录 → Redis 写入 token↔loginId 映射
        StpUtil.login(loginId);

        // 3. 构建用户信息并存入 SaSession
        //    后续每次 HTTP 请求，UserContextInterceptor 从 Session 取出此对象
        LoginUserInfoDTO userInfo = LoginUserInfoDTO.builder()
                .loginId(account.getId())
                .userType(userType)
                .accountId(account.getAccountId())
                .nickname(account.getNickname())
                .build();
        StpUtil.getSession().set(LoginUserInfoDTO.SESSION_KEY, userInfo);

        log.info("[登录成功] accountId={}, loginId={}", account.getAccountId(), loginId);

        // 4. 构建带 token 的响应
        return AuthRespDTO.from(account, StpUtil.getTokenValue());
    }

    /**
     * 登出当前用户
     * <p>
     * 当前阶段：仅失效 SaToken（Redis 中删除 token 和 Session）。
     * <p>
     * TODO Phase D/6：登出前通知 RoomChannelManager 关闭 WS 连接。
     *   当前阶段 WS 还没改造为 token 认证，所以暂不处理。
     *   拆模块后改为发布 MemberLogoutEvent，由 chat-service 的 @EventListener 处理。
     *   此方法依赖 SaHolder，只能在 HTTP 请求线程中调用。
     * 批量踢人场景应使用 StpUtil.kickout(loginId)，不走此方法。
     */
    @Override
    public void doLogout() {
        if (StpUtil.isLogin()) {
            String loginId = StpUtil.getLoginIdAsString();
            StpUtil.logout();
            log.info("[登出成功] loginId={}", loginId);
        }
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
        AccountDO account = multistageCacheProxy.safeGet(
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
        return multistageCacheProxy.safeGet(
                CacheUtil.buildKey("flashchat", "account", "id", String.valueOf(id)),
                AccountDO.class,
                () -> this.getById(id),
                CACHE_TIMEOUT,
                accountBloomFilter
        );
    }

    // ==================== 私有方法 ====================


    /**
     * 创建匿名账号 — 编程式事务
     * <p>
     * 使用 TransactionTemplate 而非 @Transactional：
     * 此方法被同类的 autoRegister() 调用，声明式事务在同类内部调用时不走代理，
     * @Transactional 会失效。TransactionTemplate 直接控制事务，不依赖代理。
     */
    private AccountDO doRegister() {
        return transactionTemplate.execute(status -> {
            String accountId = generateUniqueAccountId();
            String nickname = generateNickname();
            String avatarColor = generateAvatarColor();

            AccountDO account = AccountDO.builder()
                    .accountId(accountId)
                    .nickname(nickname)
                    .avatarColor(avatarColor)
                    .avatarUrl("")
                    .password("")
                    .email(null)
                    .inviteCode(null)
                    .invitedBy(null)
                    .credits(0)
                    .isRegistered(0)
                    .status(1)
                    .build();

            this.save(account);

            log.info("[匿名注册] id={}, accountId={}, nickname={}",
                    account.getId(), accountId, nickname);

            return account;
        });
    }

    /**
     * 注册后的缓存操作
     * <p>
     * 即使 Redis 异常，账号已在 DB 中，后续查询通过 CacheLoader 从 DB 加载回填。
     * 布隆过滤器缺失的影响仅是"多穿透一次到 DB"。
     */
    private void postRegisterCache(AccountDO account) {
        try {
            String cacheKeyByBizId = CacheUtil.buildKey("flashchat", "account",
                    account.getAccountId());
            String cacheKeyByDbId = CacheUtil.buildKey("flashchat", "account",
                    "id", String.valueOf(account.getId()));

            accountBloomFilter.add(cacheKeyByBizId);
            accountBloomFilter.add(cacheKeyByDbId);
            multistageCacheProxy.put(cacheKeyByBizId, account, CACHE_TIMEOUT);
            multistageCacheProxy.put(cacheKeyByDbId, account, CACHE_TIMEOUT);
        } catch (Exception e) {
            log.error("[注册缓存写入失败] accountId={}, 不影响注册，后续查询自动回填",
                    account.getAccountId(), e);
        }
    }

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