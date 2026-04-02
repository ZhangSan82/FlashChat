package com.flashchat.gameservice.ai.prompt;

import com.flashchat.gameservice.ai.llm.LlmMessage;
import com.flashchat.gameservice.ai.model.AiDescribeInput;
import com.flashchat.gameservice.ai.model.AiVoteInput;
import com.flashchat.gameservice.dao.enums.AiPersonaEnum;
import com.flashchat.gameservice.dao.enums.GameRoleEnum;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * 谁是卧底专用 PromptBuilder。
 * <p>
 * 第一版先保证结构清晰、输出稳定，后续可以持续迭代 prompt 文案。
 */
@Component
public class WhoIsSpyPromptBuilder implements AiPromptBuilder {

    @Override
    public List<LlmMessage> buildDescribePrompt(AiDescribeInput input) {
        String systemPrompt = """
                你正在参加一个“谁是卧底”游戏。
                游戏规则：所有玩家都会拿到一个词，平民拿到同一个词，卧底拿到一个相近但不同的词。每轮每个人用一句话描述自己的词，不能直接说出词本身。
                你的任务：像一个真实玩家一样发言，不要暴露自己的身份和词语。
                性格设定：%s
                输出要求：
                1. 只输出你的发言内容本身
                2. 不要加引号，不要解释，不要分析，不要换行
                3. 不要直接说出你拿到的词
                4. 控制在 30 个字以内，宁可模糊一点
                """.formatted(resolvePersonaInstruction(input.getPersona()));

        StringBuilder userPrompt = new StringBuilder()
                .append("你的身份：").append(resolveRoleName(input.getRole())).append('\n')
                .append("你拿到的词：").append(input.getWord()).append('\n')
                .append("当前是第 ").append(input.getRoundNumber()).append(" 轮").append('\n')
                .append("本轮在你之前的公开发言：").append('\n')
                .append(formatCurrentRoundDescriptions(input.getCurrentRoundDescriptions())).append('\n')
                .append("最近历史发言（用于保持前后连贯，避免重复）：").append('\n')
                .append(formatHistoryDescriptions(input.getHistoryDescriptions())).append('\n')
                .append("请基于以上局面给出你现在的发言。");

        return List.of(
                LlmMessage.system(systemPrompt),
                LlmMessage.user(userPrompt.toString())
        );
    }

    @Override
    public List<LlmMessage> buildVotePrompt(AiVoteInput input) {
        String systemPrompt = """
                你正在参加一个“谁是卧底”游戏，现在进入投票阶段。
                游戏规则：你需要从当前仍存活的其他玩家中选出一个最可疑的人投票。
                性格设定：%s
                投票策略：
                1. 如果你是平民，请优先投给描述最可疑、最不一致的人
                2. 如果你是卧底，请优先投给最可能威胁到你的人
                输出要求：
                1. 只返回一个候选编号数字
                2. 不要输出解释、标点或其他文字
                """.formatted(resolvePersonaInstruction(input.getPersona()));

        StringBuilder userPrompt = new StringBuilder()
                .append("你的身份：").append(resolveRoleName(input.getRole())).append('\n')
                .append("你拿到的词：").append(input.getWord()).append('\n')
                .append("当前是第 ").append(input.getRoundNumber()).append(" 轮投票").append('\n')
                .append("候选人列表：").append('\n')
                .append(formatVoteCandidates(input.getCandidates())).append('\n')
                .append("请从中选出一个你要投票的人，只返回编号数字。");

        return List.of(
                LlmMessage.system(systemPrompt),
                LlmMessage.user(userPrompt.toString())
        );
    }

    private String formatCurrentRoundDescriptions(List<AiDescribeInput.SpeechFact> descriptions) {
        if (descriptions == null || descriptions.isEmpty()) {
            return "暂无，轮到你先发言。";
        }
        return descriptions.stream()
                .map(description -> description.getSpeakerNickname() + "：" + formatSpeechContent(
                        description.getContent(), description.isSkipped()))
                .collect(Collectors.joining("\n"));
    }

    private String formatHistoryDescriptions(List<AiDescribeInput.SpeechFact> descriptions) {
        if (descriptions == null || descriptions.isEmpty()) {
            return "暂无历史发言。";
        }
        Map<Integer, List<AiDescribeInput.SpeechFact>> roundMap = descriptions.stream()
                .collect(Collectors.groupingBy(AiDescribeInput.SpeechFact::getRoundNumber,
                        LinkedHashMap::new, Collectors.toList()));

        StringJoiner joiner = new StringJoiner("\n");
        roundMap.forEach((roundNumber, facts) -> {
            joiner.add("第 " + roundNumber + " 轮：");
            for (AiDescribeInput.SpeechFact fact : facts) {
                joiner.add("- " + fact.getSpeakerNickname() + "：" + formatSpeechContent(
                        fact.getContent(), fact.isSkipped()));
            }
        });
        return joiner.toString();
    }

    private String formatVoteCandidates(List<AiVoteInput.VoteCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "当前没有可投票候选人。";
        }
        StringJoiner joiner = new StringJoiner("\n");
        for (AiVoteInput.VoteCandidate candidate : candidates) {
            joiner.add(candidate.getIndex() + ". " + candidate.getNickname());
            if (candidate.getDescriptionsByRound() == null || candidate.getDescriptionsByRound().isEmpty()) {
                joiner.add("   发言记录：暂无");
                continue;
            }
            for (AiVoteInput.RoundSpeechFact roundFact : candidate.getDescriptionsByRound()) {
                String speechText = roundFact.getSpeeches().stream()
                        .map(speech -> formatSpeechContent(speech.getContent(), speech.isSkipped()))
                        .collect(Collectors.joining("；"));
                joiner.add("   第 " + roundFact.getRoundNumber() + " 轮：" + speechText);
            }
        }
        return joiner.toString();
    }

    private String formatSpeechContent(String content, boolean skipped) {
        if (skipped) {
            return "跳过发言";
        }
        return content == null || content.isBlank() ? "未留下有效内容" : content.trim();
    }

    private String resolveRoleName(GameRoleEnum role) {
        if (role == null) {
            return "未知";
        }
        if (role == GameRoleEnum.CIVILIAN) {
            return "平民";
        }
        if (role == GameRoleEnum.SPY) {
            return "卧底";
        }
        return "白板";
    }

    private String resolvePersonaInstruction(AiPersonaEnum persona) {
        if (persona == null) {
            return "你性格冷静自然，尽量像普通真人玩家一样发言和投票。";
        }
        return switch (persona) {
            case CAUTIOUS -> "你性格谨慎保守，描述尽量模糊笼统，投票时倾向选择最稳妥的人。";
            case AGGRESSIVE -> "你性格直接果断，描述会给出较明确的特征，投票时相信自己的分析。";
            case MASTER -> "你是经验丰富的老手，描述巧妙克制，投票时会综合分析每个人的逻辑和节奏。";
        };
    }
}
