package com.flashchat.chatservice.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.flashchat.chatservice.dao.entity.AccountDO;
import com.flashchat.chatservice.dto.req.*;
import com.flashchat.chatservice.dto.resp.AccountInfoRespDTO;
import com.flashchat.chatservice.dto.resp.AuthRespDTO;
import com.flashchat.chatservice.dto.resp.InviteCodeRespDTO;
import com.flashchat.chatservice.service.AccountService;
import com.flashchat.chatservice.service.InviteCodeService;
import com.flashchat.convention.exception.ClientException;
import com.flashchat.convention.result.Result;
import com.flashchat.convention.result.Results;
import com.flashchat.user.constant.UserTypeConstant;
import com.flashchat.user.core.UserContext;
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

        // 1. 查账号
        AccountDO account = accountService.getByAccountId(request.getAccountId());

        // 2. 密码校验
        if (!account.hasPassword()) {
            throw new ClientException("该账号未设置密码，token 丢失请重新注册");
        }
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new ClientException("请输入密码");
        }
        if (!bCryptPasswordEncoder.matches(request.getPassword(), account.getPassword())) {
            throw new ClientException("密码错误");
        }

        // 3. 登录
        int userType = account.registered()
                ? UserTypeConstant.USER
                : UserTypeConstant.MEMBER;
        return Results.success(accountService.doLogin(account, userType));
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
        return Results.success(AuthRespDTO.from(account, StpUtil.getTokenValue()));
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
}