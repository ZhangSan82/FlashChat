package com.flashchat.chatservice.service.strategy.msg;

import com.flashchat.chatservice.dao.enums.MessageTypeEnum;
import com.flashchat.chatservice.dto.msg.FileDTO;
import com.flashchat.convention.exception.ClientException;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息处理器工厂
 * 两个职责：
 *   1. 注册：Handler 的 @PostConstruct 调用 register()，启动时自动注册
 *   2. 路由：根据输入条件找到对应的 Handler
 * 路由机制
 *   FlashChat：前端传 files → Factory 根据 files 的 MIME type 推断 msgType → 返回 Handler
 *   原因：vue-advanced-chat 的 send-message 事件不传 msgType
 * 为什么是静态工具类而不是 @Component：
 *   注册方法被 Handler 的 @PostConstruct 调用，发生在 Spring 容器初始化阶段
 *   用静态 Map 最简单可靠，不存在循环依赖问题
 */
@Slf4j
public class MsgHandlerFactory {

    private static final Map<Integer, AbstractMsgHandler> STRATEGY_MAP = new HashMap<>();

    private MsgHandlerFactory() {
    }

    /**
     * 注册处理器
     * 由 AbstractMsgHandler.init()（@PostConstruct）调用
     * 新增消息类型时无需修改此方法——开闭原则
     */
    public static void register(Integer type, AbstractMsgHandler handler) {
        STRATEGY_MAP.put(type, handler);
    }

    /**
     * 根据 files 路由到对应的 Handler
     * 用于 ChatServiceImpl.sendMsg()
     * 路由规则：
     *   files 为空                        → TextMsgHandler
     *   files[0].type 以 "image/" 开头    → ImageMsgHandler
     *   files[0].type 以 "audio/" 开头    → VoiceMsgHandler
     *   files[0].type 以 "video/" 开头    → VideoMsgHandler
     *   其他 MIME type                    → FileMsgHandler
     * 为什么只看 files[0]：
     *   vue-advanced-chat 一次可选多个文件，但同一条消息的文件类型一致
     *   （都是图片 或 都是文件），用第一个文件的 type 即可判断
     * @param files 前端传来的文件列表（可为 null 或空）
     * @return 对应的消息处理器
     */
    public static AbstractMsgHandler getHandler(List<FileDTO> files) {
        MessageTypeEnum type;
        if (files == null || files.isEmpty()) {
            type = MessageTypeEnum.TEXT;
        } else {
            type = MessageTypeEnum.ofMimeType(files.get(0).getType());
        }
        return getHandlerByType(type.getType());
    }

    /**
     * 根据 msgType 编号获取 Handler
     * 用于内部场景（如根据 DB 的 msg_type 查找 Handler）
     * @param msgType 消息类型编号
     * @return 对应的消息处理器
     * @throws ClientException 如果类型未注册
     */
    public static AbstractMsgHandler getHandlerByType(Integer msgType) {
        AbstractMsgHandler handler = STRATEGY_MAP.get(msgType);
        if (handler == null) {
            throw new ClientException("不支持的消息类型: " + msgType);
        }
        return handler;
    }
}