package com.flashchat.gameservice.ai;

import com.flashchat.gameservice.ai.llm.LlmClient;
import com.flashchat.gameservice.ai.llm.LlmMessage;
import com.flashchat.gameservice.ai.llm.exception.LlmTimeoutException;
import com.flashchat.gameservice.ai.model.AiDescribeInput;
import com.flashchat.gameservice.ai.model.AiVoteInput;
import com.flashchat.gameservice.ai.prompt.AiPromptBuilder;
import com.flashchat.gameservice.dao.enums.AiPersonaEnum;
import com.flashchat.gameservice.dao.enums.AiProviderEnum;
import com.flashchat.gameservice.dao.enums.GameRoleEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiPlayerServiceImpl} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class AiPlayerServiceImplTest {

    @Mock
    private AiPromptBuilder promptBuilder;

    @Mock
    private LlmClient llmClient;

    private AiPlayerServiceImpl aiPlayerService;

    @BeforeEach
    void setUp() {
        aiPlayerService = new AiPlayerServiceImpl(promptBuilder, List.of(llmClient));
        when(llmClient.getProvider()).thenReturn(AiProviderEnum.KIMI);
    }

    @Test
    void generateDescription_shouldReturnNormalizedContent_whenLlmSucceeds() {
        AiDescribeInput input = buildDescribeInput("苹果");
        List<LlmMessage> messages = List.of(LlmMessage.system("system"), LlmMessage.user("user"));
        when(promptBuilder.buildDescribePrompt(input)).thenReturn(messages);
        when(llmClient.chat(eq(messages), isNull(Duration.class))).thenReturn("  “这个东西挺常见的” \n");

        String result = aiPlayerService.generateDescription(input);

        assertThat(result).isEqualTo("这个东西挺常见的");
        verify(promptBuilder).buildDescribePrompt(input);
        verify(llmClient).chat(eq(messages), isNull(Duration.class));
    }

    @Test
    void generateDescription_shouldReturnFallback_whenLlmTimesOut() {
        AiDescribeInput input = buildDescribeInput("苹果");
        List<LlmMessage> messages = List.of(LlmMessage.system("system"), LlmMessage.user("user"));
        when(promptBuilder.buildDescribePrompt(input)).thenReturn(messages);
        when(llmClient.chat(eq(messages), isNull(Duration.class)))
                .thenAnswer(invocation -> {
                    throw new LlmTimeoutException("timeout");
                });

        String result = aiPlayerService.generateDescription(input);

        assertThat(result).isNotBlank();
        assertThat(result).doesNotContain("苹果");
    }

    @Test
    void generateDescription_shouldReturnFallback_whenContentIsFiltered() {
        AiDescribeInput input = buildDescribeInput("苹果");
        List<LlmMessage> messages = List.of(LlmMessage.system("system"), LlmMessage.user("user"));
        when(promptBuilder.buildDescribePrompt(input)).thenReturn(messages);
        when(llmClient.chat(eq(messages), isNull(Duration.class))).thenReturn("我觉得苹果挺常见");

        String result = aiPlayerService.generateDescription(input);

        assertThat(result).isNotBlank();
        assertThat(result).doesNotContain("苹果");
        assertThat(result).isNotEqualTo("我觉得苹果挺常见");
    }

    @Test
    void generateVoteTarget_shouldReturnPlayerId_whenModelReturnsValidIndex() {
        AiVoteInput input = buildVoteInput();
        List<LlmMessage> messages = List.of(LlmMessage.system("system"), LlmMessage.user("user"));
        when(promptBuilder.buildVotePrompt(input)).thenReturn(messages);
        when(llmClient.chat(eq(messages), isNull(Duration.class))).thenReturn("我选 2 号");

        Long targetPlayerId = aiPlayerService.generateVoteTarget(input);

        assertThat(targetPlayerId).isEqualTo(102L);
    }

    @Test
    void generateVoteTarget_shouldReturnNull_whenResultCannotBeParsed() {
        AiVoteInput input = buildVoteInput();
        List<LlmMessage> messages = List.of(LlmMessage.system("system"), LlmMessage.user("user"));
        when(promptBuilder.buildVotePrompt(input)).thenReturn(messages);
        when(llmClient.chat(eq(messages), isNull(Duration.class))).thenReturn("我觉得第二个人可疑");

        Long targetPlayerId = aiPlayerService.generateVoteTarget(input);

        assertThat(targetPlayerId).isNull();
    }

    private AiDescribeInput buildDescribeInput(String word) {
        return AiDescribeInput.builder()
                .gameId("game-1")
                .aiPlayerId(101L)
                .provider(AiProviderEnum.KIMI)
                .persona(AiPersonaEnum.CAUTIOUS)
                .role(GameRoleEnum.CIVILIAN)
                .word(word)
                .roundNumber(2)
                .currentRoundDescriptions(List.of(
                        AiDescribeInput.SpeechFact.builder()
                                .roundNumber(2)
                                .speakerNickname("小明")
                                .content("这个东西生活里常能见到")
                                .skipped(false)
                                .build()
                ))
                .historyDescriptions(List.of(
                        AiDescribeInput.SpeechFact.builder()
                                .roundNumber(1)
                                .speakerNickname("小红")
                                .content("我上轮提到过它挺常见")
                                .skipped(false)
                                .build()
                ))
                .build();
    }

    private AiVoteInput buildVoteInput() {
        return AiVoteInput.builder()
                .gameId("game-1")
                .aiPlayerId(101L)
                .provider(AiProviderEnum.KIMI)
                .persona(AiPersonaEnum.MASTER)
                .role(GameRoleEnum.CIVILIAN)
                .word("苹果")
                .roundNumber(2)
                .candidates(List.of(
                        AiVoteInput.VoteCandidate.builder()
                                .index(1)
                                .playerId(101L)
                                .nickname("玩家一")
                                .descriptionsByRound(List.of(
                                        AiVoteInput.RoundSpeechFact.builder()
                                                .roundNumber(1)
                                                .speeches(List.of(
                                                        AiVoteInput.SpeechFact.builder()
                                                                .content("我觉得挺日常")
                                                                .skipped(false)
                                                                .build()
                                                ))
                                                .build()
                                ))
                                .build(),
                        AiVoteInput.VoteCandidate.builder()
                                .index(2)
                                .playerId(102L)
                                .nickname("玩家二")
                                .descriptionsByRound(List.of(
                                        AiVoteInput.RoundSpeechFact.builder()
                                                .roundNumber(1)
                                                .speeches(List.of(
                                                        AiVoteInput.SpeechFact.builder()
                                                                .content("我觉得不太常见")
                                                                .skipped(false)
                                                                .build()
                                                ))
                                                .build()
                                ))
                                .build()
                ))
                .build();
    }
}
