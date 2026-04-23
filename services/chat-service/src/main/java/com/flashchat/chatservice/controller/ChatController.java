package com.flashchat.chatservice.controller;


import com.flashchat.chatservice.dto.req.*;
import com.flashchat.chatservice.dto.resp.ChatBroadcastMsgRespDTO;
import com.flashchat.chatservice.dto.resp.CursorPageBaseResp;
import com.flashchat.chatservice.service.ChatService;
import com.flashchat.convention.result.Result;
import com.flashchat.convention.result.Results;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/FlashChat/v1/chat")
@CrossOrigin(origins = "*")
@AllArgsConstructor
public class ChatController {


    private final ChatService chatService;

    /**
     * 发送消息
     */
    @PostMapping("/msg")
    public Result<ChatBroadcastMsgRespDTO> sendMsg(@Valid @RequestBody SendMsgReqDTO request) {
        // perf 压测高频发送场景下，入口每请求 INFO 会放大同步日志写盘成本。
        // 保留排查能力，但默认降到 DEBUG，避免污染端到端时延。
        log.debug("[发消息] roomId={}", request.getRoomId());

        return Results.success(chatService.sendMsg(request));

    }

    /**
     * @param roomId   房间 ID（必填）
     * @param request  游标分页参数（cursor 选填，pageSize 默认 20）
     */
    @GetMapping("/history")
    public Result<CursorPageBaseResp<ChatBroadcastMsgRespDTO>> getHistoryMessages(
            @RequestParam("roomId") String roomId,
            @Valid CursorPageBaseReq request) {

        log.info("[历史消息] roomId={}, cursor={}, pageSize={}",
                roomId, request.getCursor(), request.getPageSize());
        return Results.success(chatService.getHistoryMessages(roomId, request));
    }


    /**
     * 消息确认 ACK（★ 新增）
     * 语义："这个房间里 lastMsgId 以及之前的消息我都看到了"
     * 前端调用时机（建议做 1 秒防抖）：
     *   1. 打开/切换到某个聊天窗口
     *   2. 收到新消息且窗口在前台
     *   3. 从后台切回前台
     *   4. 离开房间前
     */
    @PostMapping("/ack")
    public Result<Void> ackMessages(@Valid @RequestBody MsgAckReqDTO request) {
        log.info("[ACK] roomId={}, lastMsgId={}",
                request.getRoomId(), request.getLastMsgId());
        chatService.ackMessages(request);
        return Results.success();
    }


    /**
     * 拉取新消息 — 断线重连（★ 新增）
     * 后端自动从 t_room_member.last_ack_msg_id 读取用户的已读位置
     * 返回已读位置之后的所有新消息（最多 100 条）
     * 前端使用流程：
     *   1. WebSocket 重连成功
     *   2. 对每个房间调用此接口
     *   3. 拿到新消息 → append 到聊天窗口
     *   4. 调用 ACK 接口更新已读位置
     * 如果 isLast=false（新消息超过 100 条）：
     *   → 用返回的 cursor 继续调此接口
     *   → 或者直接用 history 接口重新加载
     */
    @GetMapping("/new")
    public Result<CursorPageBaseResp<ChatBroadcastMsgRespDTO>> getNewMessages(
            @RequestParam("roomId") String roomId) {

        log.info("[拉新消息] roomId={}", roomId);
        return Results.success(chatService.getNewMessages(roomId));
    }

    /**
     * 查询所有房间的未读消息数（★ 新增）
     *
     * 返回示例：{ "room_abc": 3, "room_def": 1 }
     * 未读数为 0 的房间不返回
     */
    @GetMapping("/unread")
    public Result<Map<String, Integer>> getUnreadCounts() {
        log.info("[查未读]");
        return Results.success(chatService.getUnreadCounts());
    }

    /**
     * 撤回消息
     * 前端调用时机：用户长按自己发的消息 → 点击"撤回"
     */
    @PostMapping("/recall")
    public Result<Void> recallMsg(@Valid @RequestBody MsgRecallReqDTO request) {
        log.info("[撤回消息] roomId={}, msgId={}", request.getRoomId(), request.getMsgId());
        chatService.recallMsg(request);
        return Results.success();
    }

    /**
     * 删除消息
     * 前端调用时机：房主长按任意消息 → 点击"删除"
     */
    @PostMapping("/delete")
    public Result<Void> deleteMsg(@Valid @RequestBody MsgDeleteReqDTO request) {
        log.info("[删除消息] roomId={}, msgId={}", request.getRoomId(), request.getMsgId());
        chatService.deleteMsg(request);
        return Results.success();
    }

    /**
     * 消息表情回应
     */
    @PostMapping("/reaction")
    public Result<Void> toggleReaction(@Valid @RequestBody MsgReactionReqDTO request) {
        log.info("[表情回应] roomId={}, msgId={}, emoji={}",
                request.getRoomId(), request.getMsgId(), request.getEmoji());
        chatService.toggleReaction(request);
        return Results.success();
    }
}
