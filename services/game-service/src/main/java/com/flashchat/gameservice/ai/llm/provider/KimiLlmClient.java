package com.flashchat.gameservice.ai.llm.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.flashchat.convention.exception.ServiceException;
import com.flashchat.gameservice.ai.llm.LlmClient;
import com.flashchat.gameservice.ai.llm.LlmMessage;
import com.flashchat.gameservice.ai.llm.exception.LlmTimeoutException;
import com.flashchat.gameservice.config.GameAiProperties;
import com.flashchat.gameservice.dao.enums.AiProviderEnum;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * KIMI 大模型客户端。
 * <p>
 * 对上层暴露统一的 {@link LlmClient} 接口，
 * 内部负责参数校验、HTTP 请求封装、响应解析和异常收口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KimiLlmClient implements LlmClient {

    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
    private static final double DEFAULT_TEMPERATURE = 0.7D;
    private static final int DEFAULT_MAX_TOKENS = 128;
    private static final int DEFAULT_TIMEOUT_SECONDS = 15;

    private final WebClient.Builder webClientBuilder;
    private final GameAiProperties gameAiProperties;

    /**
     * KIMI 客户端为无状态对象，WebClient 在初始化时构建一次后长期复用。
     * <p>
     * 这样可以避免每次 chat 调用都重新 build，也避免污染共享的 Builder 状态。
     */
    private volatile WebClient webClient;

    @PostConstruct
    public void init() {
        this.webClient = webClientBuilder.clone()
                .baseUrl(trimTrailingSlash(gameAiProperties.getBaseUrl()))
                .build();
    }

    @Override
    public AiProviderEnum getProvider() {
        return AiProviderEnum.KIMI;
    }

    @Override
    public String chat(List<LlmMessage> messages, Duration timeout) {
        validateConfig();
        List<LlmMessage> sanitizedMessages = sanitizeMessages(messages);
        if (sanitizedMessages.isEmpty()) {
            throw new ServiceException("KIMI 调用失败：消息列表不能为空");
        }

        Duration effectiveTimeout = resolveTimeout(timeout);
        KimiChatRequest request = buildRequest(sanitizedMessages);
        long startTime = System.currentTimeMillis();

        try {
            KimiChatResponse response = webClient.post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .headers(headers -> headers.setBearerAuth(gameAiProperties.getApiKey()))
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(KimiChatResponse.class)
                    .block(effectiveTimeout);

            String content = extractContent(response);
            log.debug("[KIMI] 调用成功 model={}, cost={}ms",
                    gameAiProperties.getModel(), System.currentTimeMillis() - startTime);
            return content;
        } catch (LlmTimeoutException ex) {
            throw ex;
        } catch (WebClientResponseException ex) {
            handleResponseException(ex);
            throw new ServiceException("KIMI 调用失败");
        } catch (WebClientRequestException ex) {
            if (isTimeoutException(ex)) {
                log.warn("[KIMI] 调用超时 model={}, timeout={}ms",
                        gameAiProperties.getModel(), effectiveTimeout.toMillis());
                throw new LlmTimeoutException("KIMI 调用超时");
            }
            log.error("[KIMI] 网络请求异常 model={}", gameAiProperties.getModel(), ex);
            throw new ServiceException("KIMI 网络请求失败");
        } catch (IllegalStateException ex) {
            if (isTimeoutException(ex)) {
                log.warn("[KIMI] 调用超时 model={}, timeout={}ms",
                        gameAiProperties.getModel(), effectiveTimeout.toMillis());
                throw new LlmTimeoutException("KIMI 调用超时");
            }
            log.error("[KIMI] 调用异常 model={}", gameAiProperties.getModel(), ex);
            throw new ServiceException("KIMI 调用失败");
        } catch (Exception ex) {
            if (isTimeoutException(ex)) {
                log.warn("[KIMI] 调用超时 model={}, timeout={}ms",
                        gameAiProperties.getModel(), effectiveTimeout.toMillis());
                throw new LlmTimeoutException("KIMI 调用超时");
            }
            log.error("[KIMI] 调用异常 model={}", gameAiProperties.getModel(), ex);
            throw new ServiceException("KIMI 调用失败");
        }
    }

    private void validateConfig() {
        if (!gameAiProperties.isEnabled()) {
            throw new ServiceException("KIMI 能力未启用");
        }
        if (!StringUtils.hasText(gameAiProperties.getApiKey())) {
            throw new ServiceException("KIMI API Key 未配置");
        }
        if (!StringUtils.hasText(gameAiProperties.getBaseUrl())) {
            throw new ServiceException("KIMI baseUrl 未配置");
        }
        if (!StringUtils.hasText(gameAiProperties.getModel())) {
            throw new ServiceException("KIMI model 未配置");
        }
    }

    /**
     * 调用前剔除空消息，避免向外部模型发送无效 prompt。
     */
    private List<LlmMessage> sanitizeMessages(List<LlmMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .filter(Objects::nonNull)
                .filter(message -> StringUtils.hasText(message.getRole()))
                .filter(message -> StringUtils.hasText(message.getContent()))
                .collect(Collectors.toList());
    }

    private Duration resolveTimeout(Duration timeout) {
        if (timeout != null && !timeout.isNegative() && !timeout.isZero()) {
            return timeout;
        }
        int timeoutSeconds = gameAiProperties.getDefaultTimeoutSeconds() != null
                ? gameAiProperties.getDefaultTimeoutSeconds() : DEFAULT_TIMEOUT_SECONDS;
        return Duration.ofSeconds(timeoutSeconds);
    }

    private KimiChatRequest buildRequest(List<LlmMessage> messages) {
        KimiChatRequest request = new KimiChatRequest();
        request.setModel(gameAiProperties.getModel());
        request.setMessages(messages.stream()
                .map(message -> new KimiChatMessage(message.getRole(), message.getContent()))
                .toList());
        request.setTemperature(gameAiProperties.getTemperature() != null
                ? gameAiProperties.getTemperature() : DEFAULT_TEMPERATURE);
        request.setMaxTokens(gameAiProperties.getMaxTokens() != null
                ? gameAiProperties.getMaxTokens() : DEFAULT_MAX_TOKENS);
        request.setStream(Boolean.FALSE);
        return request;
    }

    private String extractContent(KimiChatResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            throw new ServiceException("KIMI 返回结果为空");
        }
        KimiChoice choice = response.getChoices().get(0);
        if (choice == null || choice.getMessage() == null
                || !StringUtils.hasText(choice.getMessage().getContent())) {
            throw new ServiceException("KIMI 返回内容为空");
        }
        return choice.getMessage().getContent().trim();
    }

    private void handleResponseException(WebClientResponseException ex) {
        int statusCode = ex.getStatusCode().value();
        if (statusCode == 401 || statusCode == 403) {
            log.error("[KIMI] 鉴权失败 status={}, body={}", statusCode, ex.getResponseBodyAsString(), ex);
            throw new ServiceException("KIMI 鉴权失败");
        }
        if (statusCode >= 400 && statusCode < 500) {
            log.warn("[KIMI] 请求参数异常 status={}, body={}", statusCode, ex.getResponseBodyAsString());
            throw new ServiceException("KIMI 请求失败");
        }
        log.error("[KIMI] 服务端异常 status={}, body={}", statusCode, ex.getResponseBodyAsString(), ex);
        throw new ServiceException("KIMI 服务异常");
    }

    private boolean isTimeoutException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            if (current.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
            if (current instanceof IllegalStateException
                    && current.getMessage() != null
                    && current.getMessage().contains("Timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String trimTrailingSlash(String baseUrl) {
        String value = baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class KimiChatRequest {
        private String model;
        private List<KimiChatMessage> messages;
        private Double temperature;
        @JsonProperty("max_tokens")
        private Integer maxTokens;
        private Boolean stream;
    }

    @Data
    @RequiredArgsConstructor
    private static class KimiChatMessage {
        private final String role;
        private final String content;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class KimiChatResponse {
        private List<KimiChoice> choices;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class KimiChoice {
        private KimiMessage message;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class KimiMessage {
        private String role;
        private String content;
    }
}
