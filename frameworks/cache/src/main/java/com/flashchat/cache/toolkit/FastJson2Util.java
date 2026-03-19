package com.flashchat.cache.toolkit;

import com.alibaba.fastjson2.util.ParameterizedTypeImpl;
import java.lang.reflect.Type;

/**
 * FastJson2 类型构建工具
 * 用于处理泛型类型的反序列化
 */
public final class FastJson2Util {

    private FastJson2Util() {
    }

    /**
     * 构建类型
     * 用法示例：
     * buildType(MemberDO.class)                    → MemberDO
     * buildType(List.class, MemberDO.class)         → List<MemberDO>
     * buildType(Map.class, String.class, MemberDO.class) → Map<String, MemberDO>
     */
    public static Type buildType(Type... types) {
        ParameterizedTypeImpl beforeType = null;
        if (types != null && types.length > 0) {
            if (types.length == 1) {
                return new ParameterizedTypeImpl(new Type[]{null}, null, types[0]);
            }
            for (int i = types.length - 1; i > 0; i--) {
                beforeType = new ParameterizedTypeImpl(
                        new Type[]{beforeType == null ? types[i] : beforeType},
                        null,
                        types[i - 1]);
            }
        }
        return beforeType;
    }
}