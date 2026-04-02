package com.flashchat.gameservice.ai;

import com.flashchat.gameservice.ai.llm.LlmClient;
import com.flashchat.gameservice.ai.llm.LlmMessage;
import com.flashchat.gameservice.ai.llm.exception.LlmTimeoutException;
import com.flashchat.gameservice.ai.model.AiDescribeInput;
import com.flashchat.gameservice.ai.model.AiVoteInput;
import com.flashchat.gameservice.ai.prompt.AiPromptBuilder;
import com.flashchat.gameservice.dao.enums.AiPersonaEnum;
import com.flashchat.gameservice.dao.enums.AiProviderEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 玩家服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiPlayerServiceImpl implements AiPlayerService {

    private static final int MAX_DESCRIPTION_LENGTH = 50;
    private static final Pattern FIRST_NUMBER_PATTERN = Pattern.compile("\\d+");
    private static final Map<AiPersonaEnum, List<String>> FALLBACK_DESCRIPTIONS = buildFallbackDescriptions();

    private final AiPromptBuilder promptBuilder;
    private final List<LlmClient> llmClients;

    @Override
    public String generateDescription(AiDescribeInput input) {
        long startTime = System.currentTimeMillis();
        LlmClient client = findClient(input.getProvider());
        if (client == null) {
            String fallback = randomFallbackDescription(input.getPersona());
            logDescribe(input, AiResultSource.LLM_ERROR_FALLBACK, startTime);
            return fallback;
        }

        try {
            List<LlmMessage> messages = promptBuilder.buildDescribePrompt(input);
            String rawContent = client.chat(messages, null);
            String finalContent = postProcessDescription(rawContent, input);
            if (finalContent == null) {
                finalContent = randomFallbackDescription(input.getPersona());
                logDescribe(input, AiResultSource.POST_PROCESS_FALLBACK, startTime);
                return finalContent;
            }
            logDescribe(input, AiResultSource.LLM_SUCCESS, startTime);
            return finalContent;
        } catch (LlmTimeoutException ex) {
            String fallback = randomFallbackDescription(input.getPersona());
            logDescribe(input, AiResultSource.LLM_TIMEOUT_FALLBACK, startTime);
            return fallback;
        } catch (Exception ex) {
            log.warn("[AiPlayer] 生成发言失败 gameId={}, aiPlayerId={}, provider={}, persona={}",
                    input.getGameId(), input.getAiPlayerId(), input.getProvider(), input.getPersona(), ex);
            String fallback = randomFallbackDescription(input.getPersona());
            logDescribe(input, AiResultSource.LLM_ERROR_FALLBACK, startTime);
            return fallback;
        }
    }

    @Override
    public Long generateVoteTarget(AiVoteInput input) {
        long startTime = System.currentTimeMillis();
        if (input.getCandidates() == null || input.getCandidates().isEmpty()) {
            logVote(input, AiResultSource.PARSE_FAIL_FALLBACK, startTime);
            return null;
        }

        LlmClient client = findClient(input.getProvider());
        if (client == null) {
            logVote(input, AiResultSource.LLM_ERROR_FALLBACK, startTime);
            return null;
        }

        try {
            List<LlmMessage> messages = promptBuilder.buildVotePrompt(input);
            String rawResult = client.chat(messages, null);
            Integer selectedIndex = parseVoteIndex(rawResult);
            if (selectedIndex == null) {
                logVote(input, AiResultSource.PARSE_FAIL_FALLBACK, startTime);
                return null;
            }
            for (AiVoteInput.VoteCandidate candidate : input.getCandidates()) {
                if (candidate.getIndex() == selectedIndex) {
                    logVote(input, AiResultSource.LLM_SUCCESS, startTime);
                    return candidate.getPlayerId();
                }
            }
            logVote(input, AiResultSource.PARSE_FAIL_FALLBACK, startTime);
            return null;
        } catch (LlmTimeoutException ex) {
            logVote(input, AiResultSource.LLM_TIMEOUT_FALLBACK, startTime);
            return null;
        } catch (Exception ex) {
            log.warn("[AiPlayer] 生成投票失败 gameId={}, aiPlayerId={}, provider={}, persona={}",
                    input.getGameId(), input.getAiPlayerId(), input.getProvider(), input.getPersona(), ex);
            logVote(input, AiResultSource.LLM_ERROR_FALLBACK, startTime);
            return null;
        }
    }

    private LlmClient findClient(AiProviderEnum provider) {
        if (provider == null) {
            return null;
        }
        return llmClients.stream()
                .filter(client -> client.getProvider() == provider)
                .findFirst()
                .orElse(null);
    }

    /**
     * 发言后处理。
     * <p>
     * 必做的保护：
     * 1. 去掉引号和换行
     * 2. 限制长度
     * 3. 直接说出自己词语则视为无效
     */
    private String postProcessDescription(String rawContent, AiDescribeInput input) {
        String normalized = normalizeModelText(rawContent);
        if (normalized.isBlank()) {
            return null;
        }
        if (input.getWord() != null && !input.getWord().isBlank()
                && normalized.contains(input.getWord())) {
            return null;
        }
        if (normalized.length() > MAX_DESCRIPTION_LENGTH) {
            normalized = normalized.substring(0, MAX_DESCRIPTION_LENGTH - 3) + "...";
        }
        return normalized;
    }

    private String normalizeModelText(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .trim();
        normalized = stripWrappingQuotes(normalized);
        normalized = stripKnownPrefix(normalized);
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return normalized;
    }

    private String stripWrappingQuotes(String value) {
        String result = value;
        boolean changed = true;
        while (changed && result.length() >= 2) {
            changed = false;
            if ((result.startsWith("\"") && result.endsWith("\""))
                    || (result.startsWith("'") && result.endsWith("'"))
                    || (result.startsWith("“") && result.endsWith("”"))
                    || (result.startsWith("‘") && result.endsWith("’"))) {
                result = result.substring(1, result.length() - 1).trim();
                changed = true;
            }
        }
        return result;
    }

    private String stripKnownPrefix(String value) {
        String result = value;
        String[] prefixes = {"描述：", "发言：", "回答：", "我会说：", "我选择："};
        for (String prefix : prefixes) {
            if (result.startsWith(prefix)) {
                return result.substring(prefix.length()).trim();
            }
        }
        return result;
    }

    private Integer parseVoteIndex(String rawResult) {
        String normalized = normalizeModelText(rawResult);
        if (normalized.isBlank()) {
            return null;
        }
        Matcher matcher = FIRST_NUMBER_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String randomFallbackDescription(AiPersonaEnum persona) {
        List<String> candidates = FALLBACK_DESCRIPTIONS.getOrDefault(persona, FALLBACK_DESCRIPTIONS.get(AiPersonaEnum.CAUTIOUS));
        int index = ThreadLocalRandom.current().nextInt(candidates.size());
        return candidates.get(index);
    }

    private void logDescribe(AiDescribeInput input, AiResultSource source, long startTime) {
        log.info("[AiPlayer] describe gameId={}, aiPlayerId={}, provider={}, persona={}, source={}, cost={}ms",
                input.getGameId(), input.getAiPlayerId(), input.getProvider(), input.getPersona(),
                source.name(), System.currentTimeMillis() - startTime);
    }

    private void logVote(AiVoteInput input, AiResultSource source, long startTime) {
        log.info("[AiPlayer] vote gameId={}, aiPlayerId={}, provider={}, persona={}, source={}, cost={}ms",
                input.getGameId(), input.getAiPlayerId(), input.getProvider(), input.getPersona(),
                source.name(), System.currentTimeMillis() - startTime);
    }

    private static Map<AiPersonaEnum, List<String>> buildFallbackDescriptions() {
        Map<AiPersonaEnum, List<String>> map = new EnumMap<>(AiPersonaEnum.class);
        map.put(AiPersonaEnum.CAUTIOUS, List.of(
                "这个东西在生活里挺常见的",
                "我觉得大家平时应该都接触过",
                "这个词让我想到很日常的场景",
                "从我的理解看，它算比较常见",
                "这个东西不算陌生，挺常见",
                "我会想到平时经常能见到的东西",
                "它给人的感觉比较普通日常",
                "这个词不稀奇，生活里能碰到",
                "我觉得它和日常体验关系很近",
                "这个方向挺贴近日常生活"
        ));
        map.put(AiPersonaEnum.AGGRESSIVE, List.of(
                "这东西特征其实挺明显的",
                "我觉得这个词不算太难描述",
                "这个方向已经挺好判断了",
                "有些人的描述我觉得不太对",
                "这个词应该不算特别偏门",
                "我觉得它的特点挺鲜明的",
                "这个描述方向已经很明确了",
                "我理解它有比较直接的特征",
                "这个词让我想到很具体的场景",
                "我觉得这个点其实挺好抓的"
        ));
        map.put(AiPersonaEnum.MASTER, List.of(
                "这个词可以从不同角度去理解",
                "我先给一个不那么直接的提示",
                "这个方向要结合大家的描述看",
                "它的特点不止一个层面",
                "我觉得它更像一种熟悉的印象",
                "这个词要看你从哪个角度理解",
                "我先说一个不容易暴露的信息",
                "这个东西的联想空间还挺大的",
                "我觉得它和大家生活距离不远",
                "这个词表面普通，其实挺有意思"
        ));
        return map;
    }

    /**
     * AI 结果来源，用于日志区分模型真实输出和各种降级路径。
     */
    private enum AiResultSource {
        LLM_SUCCESS,
        LLM_TIMEOUT_FALLBACK,
        LLM_ERROR_FALLBACK,
        POST_PROCESS_FALLBACK,
        PARSE_FAIL_FALLBACK
    }
}
