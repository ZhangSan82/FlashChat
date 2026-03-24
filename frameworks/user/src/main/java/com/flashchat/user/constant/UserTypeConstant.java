package com.flashchat.user.constant;

import com.flashchat.user.core.LoginUserInfoDTO;

/**
 * 用户身份类型常量
 * <p>
 * 用于区分两类用户体系：
 * 使用场景：
 * <ul>
 *   <li>{@link LoginUserInfoDTO#getUserType()} 字段值</li>
 *   <li>{@link com.flashchat.user.core.UserContext#isMember()} 判断身份</li>
 *   <li>SaToken Session 中存储的用户类型标识</li>
 * </ul>
 */
public final class UserTypeConstant {
    private UserTypeConstant() {
    }

    /**
     * 匿名成员（t_account.is_registered = 0）
     */
    public static final int MEMBER = 0;

    /**
     * 注册用户（t_account.is_registered = 1）
     */
    public static final int USER = 1;
}
