package com.itjiye.chatservice.toolkit;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

/**
 * Channel 属性存取工具
 *
 * 每个 WebSocket 连接（Channel）需要绑定一些业务信息
 * 比如这个连接属于哪个房间、用户昵称是什么、是不是房主等
 *
 * 用法：
 *   设置：ChannelAttrUtil.setRoomId(channel, "room_abc")
 *   获取：String roomId = ChannelAttrUtil.getRoomId(channel)
 */
public class ChannelAttrUtil {

    //认证令牌
    public static final AttributeKey<String> TOKEN = AttributeKey.valueOf("token");
    //用户IP
    public static final AttributeKey<String> IP = AttributeKey.valueOf("ip");
    //房间ID
    public static final AttributeKey<String> ROOM_ID = AttributeKey.valueOf("roomId");
    //用户ID
    public static final AttributeKey<Long> MEMBER_ID = AttributeKey.valueOf("memberId");
    //昵称
    public static final AttributeKey<String> NICKNAME = AttributeKey.valueOf("nickname");
    //头像URL
    public static final AttributeKey<String> AVATAR = AttributeKey.valueOf("avatar");
    //是否为房主
    public static final AttributeKey<Boolean> IS_HOST = AttributeKey.valueOf("isHost");
    //是否被禁言
    public static final AttributeKey<Boolean> IS_MUTED = AttributeKey.valueOf("isMuted");

    // ===== 通用方法 =====
    public static <T> void set(Channel channel, AttributeKey<T> key, T value) {
        channel.attr(key).set(value);
    }

    public static <T> T get(Channel channel, AttributeKey<T> key) {
        Attribute<T> attr = channel.attr(key);
        return attr.get();
    }

    // ===== 便捷方法 =====
    public static String getRoomId(Channel ch) {
        return get(ch, ROOM_ID);
    }

    public static Long getMemberId(Channel ch) {
        return get(ch, MEMBER_ID);
    }

    public static String getNickname(Channel ch) {
        return get(ch, NICKNAME);
    }

    public static String getAvatar(Channel ch) {
        return get(ch, AVATAR);
    }

    public static boolean isHost(Channel ch) {
        return Boolean.TRUE.equals(get(ch, IS_HOST));
    }

    public static boolean isMuted(Channel ch) {
        return Boolean.TRUE.equals(get(ch, IS_MUTED));
    }

    public static void setMuted(Channel ch, boolean muted) {
        set(ch, IS_MUTED, muted);
    }

    /**
     * 一次性绑定完整身份
     */
    public static void bindIdentity(Channel ch, Long memberId,
                                    String nickname, String avatar,
                                    String roomId, boolean isHost) {
        set(ch, MEMBER_ID, memberId);
        set(ch, NICKNAME, nickname);
        set(ch, AVATAR, avatar);
        set(ch, ROOM_ID, roomId);
        set(ch, IS_HOST, isHost);
        set(ch, IS_MUTED, false);
    }
}