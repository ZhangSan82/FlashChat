package com.flashchat.gameservice.controller;

import com.flashchat.gameservice.ai.llm.LlmClient;
import com.flashchat.gameservice.ai.llm.LlmMessage;
import com.flashchat.gameservice.dao.enums.AiProviderEnum;
import com.flashchat.gameservice.dto.req.AiDebugChatReqDTO;
import com.flashchat.gameservice.dto.resp.AiDebugChatRespDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * AI 调试接口。
 * <p>
 * 注意：
 * 1. 仅用于本地联调，不走 /api/** 路由，避免依赖登录态
 * 2. 上线前应删除或限制访问
 */
@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
@RequestMapping("/debug/game/ai")
public class GameAiDebugController {

    private static final String DEFAULT_SYSTEM_PROMPT = "你是一个简洁、自然的中文助手，请直接回答用户问题。";

    /**
     * 当前只有一个真实实现：KIMI。
     */
    private final LlmClient llmClient;

    /**
     * 直接发一条聊天请求到真实 AI。
     */
    @PostMapping("/chat")
    public com.flashchat.convention.result.Result<AiDebugChatRespDTO> debugChat(@Valid @RequestBody AiDebugChatReqDTO request) {
        String systemPrompt = request.getSystemPrompt() == null || request.getSystemPrompt().isBlank()
                ? DEFAULT_SYSTEM_PROMPT : request.getSystemPrompt().trim();

        Duration timeout = request.getTimeoutSeconds() != null
                ? Duration.ofSeconds(request.getTimeoutSeconds())
                : null;

        List<LlmMessage> messages = List.of(
                LlmMessage.system(systemPrompt),
                LlmMessage.user(request.getPrompt().trim())
        );

        String answer = llmClient.chat(messages, timeout);
        log.info("[AI调试] provider={}, promptLength={}",
                AiProviderEnum.KIMI.getCode(), request.getPrompt().trim().length());

        return com.flashchat.convention.result.Results.success(AiDebugChatRespDTO.builder()
                .provider(AiProviderEnum.KIMI.getCode())
                .systemPrompt(systemPrompt)
                .prompt(request.getPrompt().trim())
                .answer(answer)
                .build());
    }
}
