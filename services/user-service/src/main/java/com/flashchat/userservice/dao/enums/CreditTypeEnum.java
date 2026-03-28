package com.flashchat.userservice.dao.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 积分变动类型枚举
 */
@Getter
@AllArgsConstructor
public enum CreditTypeEnum {

    // ==================== 收入类型 (+) ====================

    /** 注册奖励：匿名用户升级为注册用户时赠送 */
    REGISTER_BONUS("注册奖励", 1),

    /** 邀请人奖励：被邀请人升级成功后，邀请人获得 */
    INVITE_REWARD_INVITER("邀请人奖励", 1),

    /** 被邀请人奖励：使用邀请码升级时额外获得 */
    INVITE_REWARD_INVITEE("被邀请人奖励", 1),

    /** 每日签到奖励 */
    DAILY_LOGIN("每日签到", 1),

    // ==================== 支出类型 (-) ====================

    /** 创建房间消费：根据房间时长档位扣除 */
    ROOM_CREATE_COST("创建房间消费", -1);

    /** 类型描述 */
    private final String desc;


    private final int direction;


    public boolean isIncome() {
        return direction == 1;
    }


    public boolean isExpense() {
        return direction == -1;
    }
}