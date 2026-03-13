package com.flashchat.chatservice.toolkit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JsonUtil {

    private static final Gson GSON = new GsonBuilder()
            // 序列化 null 字段（默认忽略null）
            .serializeNulls()
            // 日期格式
            .setDateFormat("yyyy-MM-dd HH:mm:ss")
            .disableHtmlEscaping()
            .create();

    /**
     * 对象 → JSON 字符串
     */
    public static String toJson(Object obj) {
        try {
            return GSON.toJson(obj);
        } catch (Exception e) {
            log.error("JSON序列化失败", e);
            return "{}";
        }
    }

    /**
     * JSON 字符串 → 对象
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        try {
            return GSON.fromJson(json, clazz);
        } catch (Exception e) {
            log.error("JSON反序列化失败: {}", json, e);
            return null;
        }
    }

    /**
     * JSON 字符串 → 带泛型的复杂类型
     *
     * 用法: List<User> list = JsonUtil.fromJson(json, new TypeToken<List<User>>(){});
     */
    public static <T> T fromJson(String json, TypeToken<T> typeToken) {
        try {
            return GSON.fromJson(json, typeToken.getType());
        } catch (Exception e) {
            log.error("JSON反序列化失败: {}", json, e);
            return null;
        }
    }
}
