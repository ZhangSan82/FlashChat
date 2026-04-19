package com.flashchat.userservice.dao.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 系统级账号角色。
 * <p>
 * 这里的角色和房间内的房主/成员是两套维度：
 * systemRole 控制系统管理权限，room role 只控制某个房间内的协作权限。
 */
@Getter
@AllArgsConstructor
public enum AccountRoleEnum {

    USER(0, "普通用户"),
    ADMIN(1, "系统管理员");

    private final int code;
    private final String desc;

    public static AccountRoleEnum of(Integer code) {
        if (code == null) {
            return USER;
        }
        for (AccountRoleEnum value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return USER;
    }
}
