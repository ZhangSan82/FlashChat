package com.flashchat.userservice.contanst;

/**
 * 积分系统常量
 */
public final class CreditConstants {

    private CreditConstants() {
    }


    /** 注册奖励：匿名用户升级为注册用户 */
    public static final int REGISTER_BONUS_AMOUNT = 1000;

    /** 被邀请人额外奖励：使用邀请码升级 */
    public static final int INVITE_REWARD_INVITEE_AMOUNT = 100;

    /** 邀请人奖励：被邀请人成功升级后 */
    public static final int INVITE_REWARD_INVITER_AMOUNT = 50;

    /** 每日签到 */
    public static final int DAILY_LOGIN_AMOUNT = 50;
}