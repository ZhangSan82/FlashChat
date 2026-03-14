package com.flashchat.chatservice.toolkit;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

/**
 * Channel 属性存取工具
 *
 * 每个 WebSocket 连接（Channel）绑定的是【用户级】信息
 * 一个用户只有一个 Channel，但可以同时在多个房间中
 *
 * 因此 Channel 上只存用户维度的属性（userId、昵称、头像等）
 * 房间维度的属性（是否房主、是否禁言）不存在 Channel 上
 * 因为同一个用户在不同房间中角色和状态不同，由 RoomChannelManager 管理
 *
 * 用法：
 *   设置：ChannelAttrUtil.set(channel, ChannelAttrUtil.USER_ID, 10001L)
 *   获取：Long userId = ChannelAttrUtil.getUserId(channel)
 */
public class ChannelAttrUtil {

    // ==================== 用户级属性 ====================

    //认证令牌（WebSocket 连接时从 URL 参数提取）
    public static final AttributeKey<String> TOKEN = AttributeKey.valueOf("token");

    //账号ID
    public static final AttributeKey<String> ACCOUNT_ID = AttributeKey.valueOf("accountId");


    //用户IP
    public static final AttributeKey<String> IP = AttributeKey.valueOf("ip");

    //用户数据库ID（游客=t_guest.id，注册用户=t_user.id）
    public static final AttributeKey<Long> USER_ID = AttributeKey.valueOf("userId");

    //用户类型：0=游客 1=注册用户
    public static final AttributeKey<Integer> USER_TYPE = AttributeKey.valueOf("userType");

    //昵称（所有房间共用同一个昵称）
    public static final AttributeKey<String> NICKNAME = AttributeKey.valueOf("nickname");

    //头像（颜色值或URL，所有房间共用）
    public static final AttributeKey<String> AVATAR = AttributeKey.valueOf("avatar");

    // ==================== 通用方法 ====================

    public static <T> void set(Channel channel, AttributeKey<T> key, T value) {
        channel.attr(key).set(value);
    }

    public static <T> T get(Channel channel, AttributeKey<T> key) {
        Attribute<T> attr = channel.attr(key);
        return attr.get();
    }

    // ==================== 便捷读取方法 ====================

    public static Long getUserId(Channel ch) {
        return get(ch, USER_ID);
    }

    public static Integer getUserType(Channel ch) {
        return get(ch, USER_TYPE);
    }

    public static String getNickname(Channel ch) {
        return get(ch, NICKNAME);
    }

    public static String getAvatar(Channel ch) {
        return get(ch, AVATAR);
    }

    public static String getAccountId(Channel ch) {
        return get(ch, ACCOUNT_ID);
    }

    public static String getToken(Channel ch) {
        return get(ch, TOKEN);
    }

    public static String getIp(Channel ch) {
        return get(ch, IP);
    }

    // ==================== 身份绑定 ====================

    /**
     * 用户认证成功后，一次性绑定身份信息到 Channel
     *
     * @param ch       WebSocket 连接
     * @param userId   用户数据库ID
     * @param userType 用户类型（0=游客 1=注册用户）
     * @param nickname 昵称
     * @param avatar   头像
     */
    public static void bindIdentity(Channel ch, Long userId, Integer userType,
                                    String nickname, String avatar) {
        set(ch, USER_ID, userId);
        set(ch, USER_TYPE, userType);
        set(ch, NICKNAME, nickname);
        set(ch, AVATAR, avatar);
    }

    /**
     * 判断 Channel 是否已完成身份认证
     */
    public static boolean isAuthenticated(Channel ch) {
        return getUserId(ch) != null;
    }
}