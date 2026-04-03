package com.flashchat.gameservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 游戏 AI 配置。
 * <p>
 * 第一版只接入 KIMI，当前通过硅基流动的 OpenAI 兼容接口访问。
 */
@Data
@ConfigurationProperties(prefix = "flashchat.ai.kimi")
public class GameAiProperties {

    /**
     * 是否启用 KIMI 调用能力。
     * <p>
     * 默认关闭，避免未配置 API Key 时误发外部请求。
     */
    private boolean enabled = false;

    /**
     * 硅基流动 OpenAI 兼容 API 基础地址。
     */
    private String baseUrl = "https://api.siliconflow.cn";

    /**
     * 硅基流动 API Key。
     */
    private String apiKey;

    /**
     * 调用模型名称。
     * <p>
     * 默认使用当前已验证可用的 Kimi-K2-Instruct-0905。
     */
    private String model = "moonshotai/Kimi-K2-Instruct-0905";

    /**
     * 默认采样温度。
     */
    private Double temperature = 0.7D;

    /**
     * 默认最大输出 token 数。
     */
    private Integer maxTokens = 128;

    /**
     * 默认单次调用超时时间（秒）。
     */
    private Integer defaultTimeoutSeconds = 15;
}
