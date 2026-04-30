package com.flashchat.userservice.controller;

import cn.dev33.satoken.stp.StpUtil;

import com.flashchat.convention.exception.ClientException;
import com.flashchat.convention.result.Result;
import com.flashchat.convention.result.Results;
import com.flashchat.convention.storage.OssAssetUrlService;
import com.flashchat.user.constant.UserTypeConstant;
import com.flashchat.user.core.UserContext;
import com.flashchat.userservice.dao.entity.AccountDO;
import com.flashchat.userservice.dto.req.*;
import com.flashchat.userservice.dto.resp.*;
import com.flashchat.userservice.service.AccountService;
import com.flashchat.userservice.service.CreditService;
import com.flashchat.userservice.service.InviteCodeService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/FlashChat/v1/account")
@CrossOrigin(origins = "*")
@AllArgsConstructor
public class AccountController {

    private final AccountService accountService;
    private final BCryptPasswordEncoder bCryptPasswordEncoder;
    private final InviteCodeService inviteCodeService;
    private final CreditService  creditService;
    private final OssAssetUrlService ossAssetUrlService;

    /** 匿名用户自动注册 */
    @PostMapping("/auto-register")
    public Result<AuthRespDTO> autoRegister() {
        log.info("[自动注册] 收到注册请求");
        return Results.success(accountService.autoRegister());
    }

    /**
     * accountId + 密码登录
     * <p>
     * 仅限设了密码的用户。未设密码的匿名用户 token 丢失 = 身份丢失，需重新注册。
     */
    @PostMapping("/login")
    public Result<AuthRespDTO> login(@Valid @RequestBody MemberLoginReqDTO request) {
        log.info("[登录] accountId={}", request.getAccountId());
        AccountDO account = accountService.getByAccountId(request.getAccountId());
        if (!account.hasPassword()) {
            throw new ClientException("该账号未设置密码，token 丢失请重新注册");
        }
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new ClientException("请输入密码");
        }
        if (!bCryptPasswordEncoder.matches(request.getPassword(), account.getPassword())) {
            throw new ClientException("密码错误");
        }
        int userType = account.registered()
                ? UserTypeConstant.USER
                : UserTypeConstant.MEMBER;
        return Results.success(accountService.doLogin(account, userType));
    }

    /**
     * 邮箱 + 密码登录
     * <p>
     * 仅限已注册（绑定邮箱 + 设了密码）的用户。邮箱查找失败、密码错误、账号封禁均抛 ClientException。
     * 日志中不打印完整邮箱，避免 PII 外泄。
     */
    @PostMapping("/login-by-email")
    public Result<AuthRespDTO> loginByEmail(@Valid @RequestBody EmailLoginReqDTO request) {
        log.info("[邮箱登录] email={}", maskEmailForLog(request.getEmail()));
        AccountDO account = accountService.getByEmail(request.getEmail());
        if (!account.hasPassword()) {
            throw new ClientException("该账号未设置密码，无法使用邮箱登录");
        }
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new ClientException("请输入密码");
        }
        if (!bCryptPasswordEncoder.matches(request.getPassword(), account.getPassword())) {
            throw new ClientException("密码错误");
        }
        int userType = account.registered()
                ? UserTypeConstant.USER
                : UserTypeConstant.MEMBER;
        return Results.success(accountService.doLogin(account, userType));
    }

    private static String maskEmailForLog(String email) {
        if (email == null || email.isBlank()) {
            return "-";
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    /**
     * 登出
     */
    @PostMapping("/logout")
    public Result<Void> logout() {
        log.info("[登出] loginId={}", StpUtil.getLoginIdAsString());
        accountService.doLogout();
        return Results.success();
    }

    /**
     * 检查登录状态
     * <p>
     * 不在放行列表中，走到这里说明 token 有效。
     * 401 = token 失效，前端自动调 /auto-register。
     */
    @GetMapping("/check")
    public Result<AuthRespDTO> checkLogin() {
        Long loginId = UserContext.getRequiredLoginId();
        AccountDO account = accountService.getAccountByDbId(loginId);
        if (account == null) {
            throw new ClientException("账号不存在");
        }
        AuthRespDTO resp = AuthRespDTO.from(account, StpUtil.getTokenValue());
        resp.setAvatarUrl(ossAssetUrlService.resolveAccessUrl(resp.getAvatarUrl()));
        return Results.success(resp);
    }

    /** 获取账号信息 */
    @GetMapping("/info/{accountId}")
    public Result<AccountInfoRespDTO> getAccountInfo(@PathVariable("accountId") String accountId) {
        return Results.success(accountService.getAccountInfoByAccountId(accountId));
    }

    /** 获取完整账号信息 */
    @GetMapping("/full/{accountId}")
    public Result<AccountDO> getAccountFull(@PathVariable("accountId") String accountId) {
        return Results.success(accountService.getByAccountId(accountId));
    }

    /**
     * 修改个人资料
     * 字段为 null 表示不修改。
     */
    @PutMapping("/profile")
    public Result<Void> updateProfile(@Valid @RequestBody UpdateProfileReqDTO request) {
        log.info("[修改资料] loginId={}", UserContext.getRequiredLoginId());
        accountService.updateProfile(request);
        return Results.success();
    }

    /**
     * 获取当前登录用户的完整信息
     */
    @GetMapping("/me")
    public Result<MyAccountRespDTO> getMyAccount() {
        return Results.success(accountService.getMyAccount());
    }

    /**
     * 首次设置密码
     */
    @PostMapping("/set-password")
    public Result<Void> setPassword(@Valid @RequestBody SetPasswordReqDTO request) {
        log.info("[设置密码] loginId={}", UserContext.getRequiredLoginId());
        accountService.setPassword(request);
        return Results.success();
    }

    /**
     * 修改密码
     */
    @PostMapping("/change-password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordReqDTO request) {
        log.info("[修改密码] loginId={}", UserContext.getRequiredLoginId());
        accountService.changePassword(request);
        return Results.success();
    }

    /**
     * 查询我的邀请码列表
     */
    @GetMapping("/invite-codes")
    public Result<List<InviteCodeRespDTO>> getMyInviteCodes() {
        Long loginId = UserContext.getRequiredLoginId();
        return Results.success(inviteCodeService.listMyCodes(loginId));
    }

    /**
     * 匿名用户升级为注册用户
     */
    @PostMapping("/upgrade")
    public Result<AuthRespDTO> upgradeAccount(@Valid @RequestBody UpgradeAccountReqDTO request) {
        log.info("[账号升级] loginId={}", UserContext.getRequiredLoginId());
        return Results.success(accountService.upgradeAccount(request));
    }

    /**
     * 注销账号
     */
    @DeleteMapping("/delete")
    public Result<Void> deleteAccount() {
        log.info("[注销账号] loginId={}", UserContext.getRequiredLoginId());
        accountService.deleteAccount();
        return Results.success();
    }

    /**
     * 查询积分余额
     */
    @GetMapping("/credits/balance")
    public Result<Integer> getCreditBalance() {
        Long loginId = UserContext.getRequiredLoginId();
        return Results.success(creditService.getBalance(loginId));
    }

    /**
     * 查询积分流水（按时间倒序）
     */
    @GetMapping("/credits/transactions")
    public Result<List<CreditTransactionRespDTO>> getCreditTransactions(
            @RequestParam(value = "page",defaultValue = "1") Integer page,
            @RequestParam(value = "size",defaultValue = "20") Integer size) {
        Long loginId = UserContext.getRequiredLoginId();
        // 参数边界保护
        if (page < 1) page = 1;
        if (size < 1) size = 1;
        if (size > 50) size = 50;
        log.info("[积分流水] loginId={}, page={}, size={}", loginId, page, size);
        return Results.success(creditService.getTransactions(loginId, page, size));
    }

    /**
     * 每日签到
     * 前端调用时机：登录后自动调用，或用户手动点击签到按钮。
     */
    @PostMapping("/daily-check-in")
    public Result<Boolean> dailyCheckIn() {
        log.info("[每日签到] loginId={}", UserContext.getRequiredLoginId());
        return Results.success(accountService.dailyCheckIn());
    }
}
