package com.flashchat.gameservice.dao.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 轮内阶段枚举（内存层）
 * <p>
 * DESCRIBING → VOTING → JUDGING → (下一轮 DESCRIBING 或游戏结束)
 * </pre>
 * <p>
 * JUDGING 是瞬时阶段：统计票数、处理淘汰、判定胜负，完成后立即切换，
 * 不等待任何外部输入。
 */
@Getter
@AllArgsConstructor
public enum RoundPhaseEnum {

    /** 发言阶段：玩家按顺序轮流描述自己的词语 */
    DESCRIBING("发言阶段"),

    /** 投票阶段：所有存活玩家并行投票选择淘汰目标 */
    VOTING("投票阶段"),

    /** 判定阶段：统计票数、执行淘汰、判断胜负（瞬时，不等待输入） */
    JUDGING("判定阶段");

    private final String desc;
}