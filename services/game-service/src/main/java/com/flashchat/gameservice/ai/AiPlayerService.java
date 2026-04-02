package com.flashchat.gameservice.ai;

import com.flashchat.gameservice.ai.model.AiDescribeInput;
import com.flashchat.gameservice.ai.model.AiVoteInput;

/**
 * AI 玩家服务。
 * <p>
 * 对引擎暴露统一入口，内部封装：
 * 1. prompt 构建
 * 2. LLM 调用
 * 3. 结果后处理
 * 4. 超时和异常降级
 */
public interface AiPlayerService {

    /**
     * 生成 AI 发言内容。
     */
    String generateDescription(AiDescribeInput input);

    /**
     * 生成 AI 投票目标。
     *
     * @return 目标 playerId；返回 null 表示交由调用方执行随机降级
     */
    Long generateVoteTarget(AiVoteInput input);
}
