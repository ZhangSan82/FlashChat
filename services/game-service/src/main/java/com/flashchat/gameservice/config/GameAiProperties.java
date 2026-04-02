package com.flashchat.gameservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 游戏 AI 配置。
 * <p>
 * 第一版只接入 KIMI，因此当前只维护 KIMI 的连接参数。
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
     * KIMI API 基础地址。
     */
    private String baseUrl = "https://api.moonshot.cn";

    /**
     * KIMI API Key。
     */
    private String apiKey;

    /**
     * 调用模型名称。
     * <p>
     * 不在代码里写死，方便后续切换具体模型。
     */
    private String model;

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
