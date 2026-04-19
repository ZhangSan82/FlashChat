package com.flashchat.userservice.controller;

import com.flashchat.convention.result.Result;
import com.flashchat.convention.result.Results;
import com.flashchat.user.core.UserContext;
import com.flashchat.userservice.dto.req.AdminAccountQueryReqDTO;
import com.flashchat.userservice.dto.req.AdminCreditAdjustReqDTO;
import com.flashchat.userservice.dto.req.AdminOperationReasonReqDTO;
import com.flashchat.userservice.dto.resp.AdminAccountRespDTO;
import com.flashchat.userservice.dto.resp.AdminPageRespDTO;
import com.flashchat.userservice.service.admin.AdminAccountService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员账号接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/FlashChat/v1/admin/accounts")
@CrossOrigin(origins = "*")
@AllArgsConstructor
public class AdminAccountController {

    private final AdminAccountService adminAccountService;

    @GetMapping
    public Result<AdminPageRespDTO<AdminAccountRespDTO>> searchAccounts(@Valid AdminAccountQueryReqDTO request) {
        Long operatorId = UserContext.getRequiredLoginId();
        log.info("[admin search accounts] operator={}, keyword={}", operatorId, request.getKeyword());
        return Results.success(adminAccountService.searchAccounts(operatorId, request));
    }

    @GetMapping("/{accountId}")
    public Result<AdminAccountRespDTO> getAccountDetail(@PathVariable("accountId") String accountId) {
        Long operatorId = UserContext.getRequiredLoginId();
        log.info("[admin account detail] operator={}, target={}", operatorId, accountId);
        return Results.success(adminAccountService.getAccountDetail(operatorId, accountId));
    }

    @PostMapping("/{accountId}/ban")
    public Result<Void> banAccount(@PathVariable("accountId") String accountId,
                                   @Valid @RequestBody AdminOperationReasonReqDTO request) {
        Long operatorId = UserContext.getRequiredLoginId();
        log.info("[admin ban account] operator={}, target={}", operatorId, accountId);
        adminAccountService.banAccount(operatorId, accountId, request);
        return Results.success();
    }

    @PostMapping("/{accountId}/unban")
    public Result<Void> unbanAccount(@PathVariable("accountId") String accountId,
                                     @Valid @RequestBody AdminOperationReasonReqDTO request) {
        Long operatorId = UserContext.getRequiredLoginId();
        log.info("[admin unban account] operator={}, target={}", operatorId, accountId);
        adminAccountService.unbanAccount(operatorId, accountId, request);
        return Results.success();
    }

    @PostMapping("/{accountId}/kickout")
    public Result<Void> kickoutAccount(@PathVariable("accountId") String accountId,
                                       @Valid @RequestBody AdminOperationReasonReqDTO request) {
        Long operatorId = UserContext.getRequiredLoginId();
        log.info("[admin kickout account] operator={}, target={}", operatorId, accountId);
        adminAccountService.kickoutAccount(operatorId, accountId, request);
        return Results.success();
    }

    @PostMapping("/{accountId}/credits/adjust")
    public Result<Void> adjustCredits(@PathVariable("accountId") String accountId,
                                      @Valid @RequestBody AdminCreditAdjustReqDTO request) {
        Long operatorId = UserContext.getRequiredLoginId();
        log.info("[admin adjust credits] operator={}, target={}, amount={}, direction={}",
                operatorId, accountId, request.getAmount(), request.getDirection());
        adminAccountService.adjustCredits(operatorId, accountId, request);
        return Results.success();
    }

    @PostMapping("/{accountId}/grant-admin")
    public Result<Void> grantAdmin(@PathVariable("accountId") String accountId,
                                   @Valid @RequestBody AdminOperationReasonReqDTO request) {
        Long operatorId = UserContext.getRequiredLoginId();
        log.info("[admin grant admin] operator={}, target={}", operatorId, accountId);
        adminAccountService.grantAdmin(operatorId, accountId, request);
        return Results.success();
    }

    @PostMapping("/{accountId}/revoke-admin")
    public Result<Void> revokeAdmin(@PathVariable("accountId") String accountId,
                                    @Valid @RequestBody AdminOperationReasonReqDTO request) {
        Long operatorId = UserContext.getRequiredLoginId();
        log.info("[admin revoke admin] operator={}, target={}", operatorId, accountId);
        adminAccountService.revokeAdmin(operatorId, accountId, request);
        return Results.success();
    }
}
