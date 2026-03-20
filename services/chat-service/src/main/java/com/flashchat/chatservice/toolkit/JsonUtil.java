package com.flashchat.chatservice.toolkit;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Type;

/**
 * JSON 工具类（FastJSON2 实现）
 */
@Slf4j
public class JsonUtil {

    /**
     * 全局序列化特性
     * WriteMapNullValue: null 字段也输出（兼容原 Gson 的 serializeNulls 行为）
     */
    private static final JSONWriter.Feature[] WRITE_FEATURES = {
            JSONWriter.Feature.WriteMapNullValue
    };

    private JsonUtil() {
    }

    /**
     * 对象 → JSON 字符串
     */
    public static String toJson(Object obj) {
        try {
            return JSON.toJSONString(obj, WRITE_FEATURES);
        } catch (Exception e) {
            log.error("JSON 序列化失败", e);
            return "{}";
        }
    }

    /**
     * JSON 字符串 → 对象
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        try {
            return JSON.parseObject(json, clazz);
        } catch (Exception e) {
            log.error("JSON 反序列化失败: {}", json, e);
            return null;
        }
    }

    /**
     * JSON 字符串 → 带泛型的复杂类型
     */
    public static <T> T fromJson(String json, Type type) {
        try {
            return JSON.parseObject(json, type);
        } catch (Exception e) {
            log.error("JSON 反序列化失败: {}", json, e);
            return null;
        }
    }
}