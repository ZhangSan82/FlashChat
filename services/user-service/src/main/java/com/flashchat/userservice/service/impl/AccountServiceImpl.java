package com.flashchat.userservice.service.impl;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashchat.cache.MultistageCacheProxy;
import com.flashchat.cache.toolkit.CacheUtil;
import com.flashchat.convention.exception.ClientException;
import com.flashchat.convention.exception.ServiceException;
import com.flashchat.user.constant.UserTypeConstant;
import com.flashchat.user.core.LoginUserInfoDTO;
import com.flashchat.user.core.UserContext;
import com.flashchat.user.event.AccountDeletedEvent;
import com.flashchat.user.event.MemberInfoChangedEvent;
import com.flashchat.user.event.MemberLogoutEvent;
import com.flashchat.user.toolkit.LoginIdUtil;
import com.flashchat.userservice.contanst.CreditConstants;
import com.flashchat.userservice.dao.entity.AccountDO;
import com.flashchat.userservice.dao.enums.AccountStatusEnum;
import com.flashchat.userservice.dao.enums.CreditTypeEnum;
import com.flashchat.userservice.dao.mapper.AccountMapper;
import com.flashchat.userservice.dto.req.ChangePasswordReqDTO;
import com.flashchat.userservice.dto.req.SetPasswordReqDTO;
import com.flashchat.userservice.dto.req.UpdateProfileReqDTO;
import com.flashchat.userservice.dto.req.UpgradeAccountReqDTO;
import com.flashchat.userservice.dto.resp.AccountInfoRespDTO;
import com.flashchat.userservice.dto.resp.AuthRespDTO;
import com.flashchat.userservice.service.CreditService;
import com.flashchat.userservice.toolkit.HashUtil;
import com.flashchat.userservice.dto.resp.MyAccountRespDTO;
import com.flashchat.userservice.service.AccountService;
import com.flashchat.userservice.service.InviteCodeService;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

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
    private final BCryptPasswordEncoder bCryptPasswordEncoder;
    private CreditService  creditService;

    private final ApplicationEventPublisher applicationEventPublisher;
    @Lazy
    @Resource
    private InviteCodeService inviteCodeService;

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
     * 批量踢人场景应使用 StpUtil.kickout(loginId)，不走此方法。
     */
    @Override
    public void doLogout() {
        if (StpUtil.isLogin()) {
            String loginId = StpUtil.getLoginIdAsString();
            Long accountId = LoginIdUtil.extractId(loginId);
            String token = StpUtil.getTokenValue();
            applicationEventPublisher.publishEvent(
                    new MemberLogoutEvent(this, accountId, token));
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

    @Override
    public void updateProfile(UpdateProfileReqDTO request) {
        Long loginId = UserContext.getRequiredLoginId();
        AccountDO account = getAccountByDbId(loginId);
        if (account == null) {
            throw new ClientException("账号不存在");
        }
        boolean updated = false;
        String newNickname = null;
        String oldDisplayAvatar = resolveDisplayAvatar(account);
        // ===== 1. 校验并设置昵称 =====
        if (request.getNickname() != null && !request.getNickname().isBlank()) {
            String trimmed = request.getNickname().trim();
            if (trimmed.length() > 20) {
                throw new ClientException("昵称不能超过 20 个字符");
            }
            if (!trimmed.equals(account.getNickname())) {
                account.setNickname(trimmed);
                newNickname = trimmed;
                updated = true;
            }
        }
        // ===== 2. 校验并设置头像色 =====
        if (request.getAvatarColor() != null && !request.getAvatarColor().isBlank()) {
            String color = request.getAvatarColor().trim();
            if (!color.matches("^#[0-9A-Fa-f]{6}$")) {
                throw new ClientException("颜色格式错误，应为 #RRGGBB");
            }
            if (!color.equals(account.getAvatarColor())) {
                account.setAvatarColor(color);
                updated = true;
            }
        }
        // ===== 3. 设置头像 URL =====
        if (request.getAvatarUrl() != null) {
            // 允许传空串（清除头像，回退到 avatarColor 方案）
            if (!request.getAvatarUrl().equals(account.getAvatarUrl())) {
                account.setAvatarUrl(request.getAvatarUrl().trim());
                updated = true;
            }
        }
        // ===== 4. 无变更直接返回 =====
        if (!updated) {
            log.debug("[修改资料-无变更] accountId={}", account.getAccountId());
            return;
        }
        // ===== 5. 更新 DB =====
        this.updateById(account);
        // ===== 6. 失效多级缓存（两个 key） =====
        evictAccountCache(account);
        // ===== 7. 同步 SaSession（仅昵称变更时需要） =====
        if (newNickname != null) {
            syncSessionNickname(account);
        }
        // ===== 8. 更新 WS 内存（昵称/头像变更时需要） =====
        String newDisplayAvatar = resolveDisplayAvatar(account);
        String newAvatar = Objects.equals(oldDisplayAvatar, newDisplayAvatar) ? null : newDisplayAvatar;
        if (newNickname != null || newAvatar != null) {
            applicationEventPublisher.publishEvent(
                    new MemberInfoChangedEvent(this, loginId, newNickname, newAvatar)
            );
        }
        log.info("[修改资料] accountId={}, nickname={}, avatarColor={}, avatarUrl={}",
                account.getAccountId(),
                newNickname != null ? newNickname : "(未改)",
                request.getAvatarColor() != null ? "(已更新)" : "(未改)",
                request.getAvatarUrl() != null ? "(已更新)" : "(未改)");
    }

    private String resolveDisplayAvatar(AccountDO account) {
        if (account == null) {
            return null;
        }
        String avatarUrl = account.getAvatarUrl();
        if (avatarUrl != null && !avatarUrl.isBlank()) {
            return avatarUrl;
        }
        return account.getAvatarColor();
    }

    @Override
    public MyAccountRespDTO getMyAccount() {
        Long loginId = UserContext.getRequiredLoginId();
        AccountDO account = getAccountByDbId(loginId);
        if (account == null) {
            throw new ClientException("账号不存在");
        }

        return MyAccountRespDTO.builder()
                .id(account.getId())
                .accountId(account.getAccountId())
                .nickname(account.getNickname())
                .avatarColor(account.getAvatarColor())
                .avatarUrl(account.getAvatarUrl())
                .email(maskEmail(account.getEmail()))
                .credits(account.getCredits())
                .isRegistered(account.registered())
                .hasPassword(account.hasPassword())
                .inviteCode(account.getInviteCode())
                .createTime(account.getCreateTime())
                .build();
    }

    @Override
    public void setPassword(SetPasswordReqDTO request) {
        Long loginId = UserContext.getRequiredLoginId();
        AccountDO account = getAccountByDbId(loginId);
        if (account == null) {
            throw new ClientException("账号不存在");
        }

        if (account.hasPassword()) {
            throw new ClientException("已设置密码，请使用修改密码功能");
        }

        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new ClientException("两次输入的密码不一致");
        }

        account.setPassword(bCryptPasswordEncoder.encode(request.getPassword()));
        this.updateById(account);
        evictAccountCache(account);

        log.info("[设置密码] accountId={}", account.getAccountId());
    }

    @Override
    public void changePassword(ChangePasswordReqDTO request) {
        Long loginId = UserContext.getRequiredLoginId();
        AccountDO account = getAccountByDbId(loginId);
        if (account == null) {
            throw new ClientException("账号不存在");
        }
        if (!account.hasPassword()) {
            throw new ClientException("尚未设置密码，请先使用设置密码功能");
        }
        if (!bCryptPasswordEncoder.matches(request.getOldPassword(), account.getPassword())) {
            throw new ClientException("原密码错误");
        }
        if (!request.getNewPassword().equals(request.getConfirmNewPassword())) {
            throw new ClientException("两次输入的新密码不一致");
        }
        if (request.getOldPassword().equals(request.getNewPassword())) {
            throw new ClientException("新密码不能与原密码相同");
        }
        account.setPassword(bCryptPasswordEncoder.encode(request.getNewPassword()));
        this.updateById(account);
        evictAccountCache(account);
        log.info("[修改密码] accountId={}", account.getAccountId());
    }

    @Override
    public AuthRespDTO upgradeAccount(UpgradeAccountReqDTO request) {
        Long loginId = UserContext.getRequiredLoginId();
        AccountDO account = getAccountByDbId(loginId);
        if (account == null) {
            throw new ClientException("账号不存在");
        }
        // ===== 校验（事务外，快速失败） =====
        if (account.registered()) {
            throw new ClientException("已是注册用户，无需升级");
        }
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new ClientException("两次输入的密码不一致");
        }
        String email = request.getEmail().trim().toLowerCase();
        Long emailCount = this.lambdaQuery().eq(AccountDO::getEmail, email).count();
        if (emailCount > 0) {
            throw new ClientException("该邮箱已被注册");
        }
        AtomicReference<Long> inviterIdRef = new AtomicReference<>(null);
        // ===== DB 事务 =====
        transactionTemplate.executeWithoutResult(status -> {
            // 邀请码处理
            Long inviterId = null;
            if (request.getInviteCode() != null && !request.getInviteCode().isBlank()) {
                inviterId = inviteCodeService.useCode(request.getInviteCode(), account.getId());
            }
            // 更新账号
            account.setPassword(bCryptPasswordEncoder.encode(request.getPassword()));
            account.setEmail(email);
            account.setIsRegistered(1);
            if (inviterId != null) {
                account.setInvitedBy(inviterId);
            }
            this.updateById(account);
            //  注册赠送积分
            creditService.grantCredits(
                    account.getId(),
                    CreditConstants.REGISTER_BONUS_AMOUNT,
                    CreditTypeEnum.REGISTER_BONUS,
                    String.valueOf(account.getId()),
                    "注册赠送"
            );
            //  邀请码奖励
            if (inviterId != null) {
                // 被邀请人额外奖励
                // bizId 用自己的 accountId：一个账号只能领一次被邀请人奖励
                creditService.grantCredits(
                        account.getId(),
                        CreditConstants.INVITE_REWARD_INVITEE_AMOUNT,
                        CreditTypeEnum.INVITE_REWARD_INVITEE,
                        String.valueOf(account.getId()),
                        "邀请码奖励"
                );
                // 邀请人奖励
                // bizId 用被邀请人的 accountId：同一个被邀请人只触发一次邀请人奖励
                creditService.grantCredits(
                        inviterId,
                        CreditConstants.INVITE_REWARD_INVITER_AMOUNT,
                        CreditTypeEnum.INVITE_REWARD_INVITER,
                        String.valueOf(account.getId()),
                        "邀请奖励-被邀请人:" + account.getAccountId()
                );
            }
            // 生成邀请码
            inviteCodeService.generateForUser(account.getId(), 3);

            inviterIdRef.set(inviterId);
        });
        if (inviterIdRef.get() != null) {
            evictCacheByDbId(inviterIdRef.get());
        }
        // ===== 事务提交后：缓存 + SaToken =====
        evictAccountCache(account);
        StpUtil.logout();
        log.info("[账号升级] accountId={}, member → user", account.getAccountId());
        return doLogin(account, UserTypeConstant.USER);
    }

    //TODO如果房主注销,房间进入5分钟宽限期
    @Override
    public void deleteAccount() {
        Long loginId = UserContext.getRequiredLoginId();
        AccountDO account = getAccountByDbId(loginId);
        if (account == null) {
            throw new ClientException("账号不存在");
        }
        // 1. 标记封禁（逻辑删除，不物理删除）
        account.setStatus(AccountStatusEnum.BANNED.getCode());
        this.updateById(account);
        // 2. 失效缓存
        evictAccountCache(account);
        // 3.【改造】发布事件，由 chat-service 监听后关闭 WS 连接 + 清理房间
        applicationEventPublisher.publishEvent(new AccountDeletedEvent(this, loginId));
        // 4. 登出 SaToken
        StpUtil.logout();
        log.info("[注销账号] accountId={}", account.getAccountId());
    }

    @Override
    public void evictCacheByDbId(Long dbId) {
        if (dbId == null) {
            return;
        }
        AccountDO account = this.getById(dbId);
        if (account != null) {
            evictAccountCache(account);
        }
    }

    @Override
    public boolean dailyCheckIn() {
        Long accountId = UserContext.getRequiredLoginId();
        // bizId 用日期字符串，同一天重复调用会被 CreditService 的幂等键拦截
        String today = LocalDate.now()
                .format(DateTimeFormatter.ISO_LOCAL_DATE);
        String bizId = accountId + ":" + today;
        boolean granted = creditService.grantCredits(
                accountId,
                CreditConstants.DAILY_LOGIN_AMOUNT,
                CreditTypeEnum.DAILY_LOGIN,
                bizId,
                "每日签到-" + today
        );
        if (granted) {
            // 签到成功，积分余额变了，失效缓存
            evictCacheByDbId(accountId);
            log.info("[每日签到成功] accountId={}, +{} 积分", accountId, CreditConstants.DAILY_LOGIN_AMOUNT);
        } else {
            log.debug("[每日签到-已签] accountId={}, date={}", accountId, today);
        }
        return granted;
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

    /**
     * 失效账号的多级缓存
     * 两个缓存 key 必须同时失效：
     * MultistageCacheProxy.delete() 内部同时清 Redis + Caffeine
     */
    private void evictAccountCache(AccountDO account) {
        try {
            String keyByBizId = CacheUtil.buildKey("flashchat", "account",
                    account.getAccountId());
            String keyByDbId = CacheUtil.buildKey("flashchat", "account",
                    "id", String.valueOf(account.getId()));
            multistageCacheProxy.delete(keyByBizId);
            multistageCacheProxy.delete(keyByDbId);
        } catch (Exception e) {
            log.error("[缓存失效异常] accountId={}", account.getAccountId(), e);
        }
    }

    /**
     * 同步 SaToken Session 中的昵称
     * 修改昵称后必须同步，否则 UserContext.getNickname() 返回旧值。
     */
    private void syncSessionNickname(AccountDO account) {
        try {
            int userType = account.registered()
                    ? UserTypeConstant.USER
                    : UserTypeConstant.MEMBER;
            String saLoginId = LoginIdUtil.toLoginId(account.getId(), userType);

            SaSession session = StpUtil.getSessionByLoginId(saLoginId, false);
            if (session != null) {
                LoginUserInfoDTO userInfo = LoginUserInfoDTO.builder()
                        .loginId(account.getId())
                        .userType(userType)
                        .accountId(account.getAccountId())
                        .nickname(account.getNickname())
                        .build();
                session.set(LoginUserInfoDTO.SESSION_KEY, userInfo);
                log.debug("[Session 同步] accountId={}, nickname={}",
                        account.getAccountId(), account.getNickname());
            }
        } catch (Exception e) {
            log.warn("[Session 同步失败] accountId={}, 不影响业务",
                    account.getAccountId(), e);
        }
    }

    private String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            return email;
        }
        return email.charAt(0) + "***" + email.substring(atIndex);
    }

    /**
     * 给邀请人加积分
     */
    private void grantInviterCredits(Long inviterId, int credits) {
        try {
            boolean updated = this.lambdaUpdate()
                    .eq(AccountDO::getId, inviterId)
                    .setSql("credits = credits + " + credits)
                    .update();

            if (updated) {
                AccountDO inviter = this.getById(inviterId);
                if (inviter != null) {
                    evictAccountCache(inviter);
                    log.info("[邀请奖励] inviter={}, +{} 积分", inviter.getAccountId(), credits);
                }
            }
        } catch (Exception e) {
            log.error("[邀请奖励失败] inviterId={}", inviterId, e);
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
