package com.flashchat.chatservice.controller;

import com.flashchat.chatservice.dao.entity.AccountDO;
import com.flashchat.chatservice.dto.resp.AccountInfoRespDTO;
import com.flashchat.chatservice.service.AccountService;
import com.flashchat.convention.result.Result;
import com.flashchat.convention.result.Results;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/FlashChat/v1/account")
@CrossOrigin(origins = "*")
@AllArgsConstructor
public class AccountController {

    private final AccountService accountService;

    /** 匿名用户自动注册 */
    @PostMapping("/auto-register")
    public Result<AccountInfoRespDTO> autoRegister() {
        log.info("[自动注册] 收到注册请求");
        return Results.success(accountService.autoRegister());
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
}