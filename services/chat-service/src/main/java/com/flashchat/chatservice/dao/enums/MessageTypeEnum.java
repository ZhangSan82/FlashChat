package com.flashchat.chatservice.dao.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 消息类型枚举
 * 对应 t_message.msg_type
 * 编号规则：
 *   1-3：现有类型（不可修改，历史数据依赖）
 *   4-7：用户可发送的媒体类型（新增）
 *   10+：系统内部类型（预留）
 * 与 vue-advanced-chat 的关系：
 *   前端不传 msgType，后端根据请求中的 files 有无 + MIME type 自动推断
 *   前端渲染不依赖此枚举值，而是依赖 files[].type（MIME type）
 *   此枚举主要用于：后端 Handler 路由、DB 存储、日志/统计
 */
@Getter
@AllArgsConstructor
public enum MessageTypeEnum {

    // ==================== 现有类型（不可修改编号） ====================
    TEXT(1, "文本"),
    SYSTEM(2, "系统消息"),
    GAME(3, "游戏消息"),

    // ==================== 新增媒体类型 ====================
    IMAGE(4, "图片"),
    VOICE(5, "语音"),
    VIDEO(6, "视频"),
    FILE(7, "文件"),
    ;

    private final Integer type;
    private final String desc;

    /**
     * O(1) 查找表，启动时构建
     */
    private static final Map<Integer, MessageTypeEnum> CACHE;

    static {
        CACHE = Arrays.stream(MessageTypeEnum.values())
                .collect(Collectors.toMap(MessageTypeEnum::getType, Function.identity()));
    }

    /**
     * 根据类型编号查找枚举
     * @param type 类型编号
     * @return 枚举值，未找到返回 null
     */
    public static MessageTypeEnum of(Integer type) {
        return CACHE.get(type);
    }

    /**
     * 根据 MIME type 推断消息类型
     * 由 MsgHandlerFactory 调用，根据前端传来的 files[0].type 路由到对应 Handler
     * 推断规则：
     *   image/*  → IMAGE
     *   audio/*  → VOICE
     *   video/*  → VIDEO
     *   其他     → FILE
     * @param mimeType MIME 类型字符串（如 "image/jpeg"、"audio/mp3"）
     * @return 对应的消息类型枚举
     */
    public static MessageTypeEnum ofMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return FILE;
        }
        String lower = mimeType.toLowerCase();
        if (lower.startsWith("image/")) {
            return IMAGE;
        }
        if (lower.startsWith("audio/")) {
            return VOICE;
        }
        if (lower.startsWith("video/")) {
            return VIDEO;
        }
        return FILE;
    }
    public boolean isMediaType() {
        return this == IMAGE || this == VOICE || this == VIDEO || this == FILE;
    }

    /**
     * 是否为用户可发送的类型
     * SYSTEM 和 GAME 是系统内部类型，不走 Handler 体系
     */
    public boolean isUserSendable() {
        return this == TEXT || this == IMAGE || this == VOICE
                || this == VIDEO || this == FILE;
    }
}
