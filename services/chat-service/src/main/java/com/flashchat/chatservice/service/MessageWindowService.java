package com.flashchat.chatservice.service;

import com.flashchat.chatservice.dto.resp.ChatBroadcastMsgRespDTO;
import com.flashchat.chatservice.dto.resp.WindowQueryResult;

import java.util.List;

/**
 * 消息滑动窗口服务
 * <p>
 * 为每个活跃房间在 Redis 中维护最近 100 条消息的 Sorted Set（score = dbId）。
 * 读操作：先查窗口 -> 命中直接返回 -> 未命中（返回 null）降级 DB
 * 写操作：发消息时同步写入窗口（在 WS 广播之前）
 * <p>
 * 接口设计原则：预检查逻辑（窗口是否存在、cursor 是否在范围内）
 * 全部内聚到查询方法内部，调用方只需判断返回值是否为 null。
 */
public interface MessageWindowService {

    /**
     * 写入窗口（Pipeline: ZADD + ZREMRANGEBYRANK + EXPIRE）
     * <p>
     * 失败不阻塞发消息流程，标记降级。
     *
     * @param roomId 房间 ID
     * @param dbId   消息的数据库自增 ID（作为 Sorted Set 的 score）
     * @param msg    广播消息 DTO（序列化为 JSON 作为 member）
     */
    void addToWindow(String roomId, Long dbId, ChatBroadcastMsgRespDTO msg);

    /**
     * 从窗口查历史消息（倒序）
     * <p>
     * 内部自动处理：窗口不存在、降级标记、cursor 超出窗口范围等场景。
     *
     * @param roomId   房间 ID
     * @param cursor   游标（null = 首次加载取最新消息）
     * @param pageSize 每页条数
     * @return 查询结果（含消息列表 + 窗口最小 score），null = 窗口不可用需降级 DB
     */
    WindowQueryResult getHistoryFromWindow(String roomId, Long cursor, int pageSize);

    /**
     * 从窗口拉新消息（正序，断线重连场景）
     *
     * @param roomId       房间 ID
     * @param lastAckMsgId 最后确认的消息 ID
     * @return 消息列表（正序），null = 窗口不可用需降级 DB
     */
    List<ChatBroadcastMsgRespDTO> getNewFromWindow(String roomId, Long lastAckMsgId);

    /**
     * 获取房间最新 N 条消息（时间正序）
     *
     * @param roomId 房间 ID
     * @param limit  条数上限
     * @return 消息列表；null 表示窗口不可用需要降级 DB
     */
    List<ChatBroadcastMsgRespDTO> getLatestFromWindow(String roomId, int limit);

    /**
     * 删除窗口（房间关闭时调用）
     *
     * @param roomId 房间 ID
     */
    void deleteWindow(String roomId);

    /**
     * 按 score 替换窗口中的消息 JSON（Lua 脚本原子操作）
     * @param roomId  房间 ID
     * @param score   消息的 dbId（Sorted Set 的 score）
     * @param newJson 替换后的完整消息 JSON
     */
    void updateMemberByScore(String roomId, Long score, String newJson);

    /**
     * 按 score 移除窗口中的消息（用于消息删除场景）
     * @param roomId 房间 ID
     * @param score  消息的 dbId
     */
    void removeMemberByScore(String roomId, Long score);
}
