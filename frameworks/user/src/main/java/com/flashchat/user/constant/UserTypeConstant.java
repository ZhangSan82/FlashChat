package com.flashchat.user.constant;

import com.flashchat.user.core.LoginUserInfoDTO;

/**
 * 用户身份类型常量
 * <p>
 * 用于区分两类用户体系：
 * <ul>
 *   <li>MEMBER(0) — 匿名成员，通过 accountId 标识，对应 t_member 表</li>
 *   <li>USER(1) — 注册用户（主持人），通过 username 标识，对应 t_user 表</li>
 * </ul>
 * <p>
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
     * 匿名成员（t_member）
     */
    public static final int MEMBER = 0;

    /**
     * 注册用户/主持人（t_user）
     */
    public static final int USER = 1;
}
