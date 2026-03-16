package com.flashchat.chatservice.controller;


import com.flashchat.chatservice.dao.entity.MemberDO;
import com.flashchat.chatservice.dto.resp.MemberInfoRespDTO;
import com.flashchat.chatservice.service.MemberService;
import com.flashchat.convention.result.Result;
import com.flashchat.convention.result.Results;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/FlashChat/v1/member")
@CrossOrigin(origins = "*")
@AllArgsConstructor
public class MemberController {

    private final MemberService memberService;

    /**
     * 匿名成员自动注册
     */
    @PostMapping("/auto-register")//TODO限流避免恶意注册账号
    public Result<MemberInfoRespDTO> autoRegister() {
        log.info("[自动注册] 收到注册请求");
        return Results.success(memberService.autoRegister());
    }

    /**
     * 获取成员信息
     */
    @GetMapping("/info/{accountId}")
    public Result<MemberInfoRespDTO> getMemberInfo(@PathVariable("accountId") String accountId) {
        return Results.success(memberService.getMemberByAccountId(accountId));
    }


    /**
     * 获取成员完整信息
     */
    @GetMapping("/full/{accountId}")
    public Result<MemberDO> getMemberDO(@PathVariable("accountId") String accountId) {
        return Results.success(memberService.getByAccountId(accountId));
    }


}