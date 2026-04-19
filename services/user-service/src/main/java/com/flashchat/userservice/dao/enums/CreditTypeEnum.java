package com.flashchat.userservice.dao.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 积分变动类型枚举。
 */
@Getter
@AllArgsConstructor
public enum CreditTypeEnum {

    // ==================== 收入类型 (+) ====================

    /** 匿名账号升级为注册用户后的注册奖励。 */
    REGISTER_BONUS("注册奖励", 1),

    /** 邀请人奖励。 */
    INVITE_REWARD_INVITER("邀请人奖励", 1),

    /** 被邀请人奖励。 */
    INVITE_REWARD_INVITEE("被邀请人奖励", 1),

    /** 每日签到奖励。 */
    DAILY_LOGIN("每日签到", 1),

    /** 管理员手动增加积分。 */
    ADMIN_ADJUST_INCREASE("管理员增加积分", 1),

    // ==================== 支出类型 (-) ====================

    /** 创建房间消耗积分。 */
    ROOM_CREATE_COST("创建房间消费", -1),

    /** 房间延期消耗积分。 */
    ROOM_EXTEND_COST("房间延期消费", -1),

    /** 管理员手动扣减积分。 */
    ADMIN_ADJUST_DECREASE("管理员扣减积分", -1);

    /** 类型描述。 */
    private final String desc;

    /** 方向：1=收入，-1=支出。 */
    private final int direction;

    public boolean isIncome() {
        return direction == 1;
    }

    public boolean isExpense() {
        return direction == -1;
    }
}
