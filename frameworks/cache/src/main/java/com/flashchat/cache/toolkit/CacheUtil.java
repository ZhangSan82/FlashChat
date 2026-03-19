package com.flashchat.cache.toolkit;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * 缓存工具类
 */
public final class CacheUtil {

    private static final String SPLICING_OPERATOR = "_";

    private CacheUtil() {
    }

    /**
     * 构建缓存标识
     * 用法：CacheUtil.buildKey("flashchat", "member", "FC-8A3D7K")
     * 结果："flashchat_member_FC-8A3D7K"
     */
    public static String buildKey(String... keys) {
        Stream.of(keys).forEach(each ->
                Optional.ofNullable(Strings.emptyToNull(each))
                        .orElseThrow(() -> new RuntimeException("构建缓存 key 不允许为空")));
        return Joiner.on(SPLICING_OPERATOR).join(keys);
    }

    /**
     * 判断结果是否为空或空的字符串
     */
    public static boolean isNullOrBlank(Object cacheVal) {
        return cacheVal == null
                || (cacheVal instanceof String && Strings.isNullOrEmpty((String) cacheVal));
    }
}