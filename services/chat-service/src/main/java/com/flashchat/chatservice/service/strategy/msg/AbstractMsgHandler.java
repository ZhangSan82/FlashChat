package com.flashchat.chatservice.service.strategy.msg;

import com.flashchat.chatservice.dao.enums.MessageTypeEnum;
import com.flashchat.chatservice.dto.msg.FileDTO;
import com.flashchat.chatservice.toolkit.JsonUtil;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 抽象消息处理器
 * 职责边界（只管消息体相关的事情）：
 * 类型专属的校验（敏感词、URL 合法性、时长限制等）
 * 数据修正/补充（如语音消息补 audio=true）
 * 构建 body JSON（媒体消息的 files 序列化）
 * 构建 content 摘要（文本=原文，图片="[图片]"）
 * 用顺序（由 ChatServiceImpl 编排）：
 *   1. checkMsg()              → 校验（纯检查，不修改数据）
 *   2. enrichFiles()           → 数据修正（可修改 files 内容）
 *   3. buildBodyJson()         → 序列化 files 为 JSON
 *   4. buildContentSummary()   → 构建文本摘要
 * 自动注册机制
 *   每个子类是 @Component，Spring 启动时实例化
 *   → @PostConstruct 触发 init()
 *   → init() 调用 MsgHandlerFactory.register() 把自己注册进去
 *   → 新增消息类型时只需新建 Handler 子类，Factory 代码零改动
 *   所有 Handler 的输入都是 String content + List<FileDTO> files，不需要类型参数
 */
@Slf4j
public abstract class AbstractMsgHandler {

    /**
     * 自动注册到工厂
     * Spring 启动 → 实例化 @Component 子类 → @PostConstruct → 注册
     */
    @PostConstruct
    private void init() {
        MsgHandlerFactory.register(getMsgTypeEnum().getType(), this);
        log.info("[MsgHandler 注册] {} → {}", getMsgTypeEnum().getDesc(),
                this.getClass().getSimpleName());
    }

    /**
     * 声明自己处理哪种消息类型
     * 子类必须实现，返回对应的枚举值
     */
    public abstract MessageTypeEnum getMsgTypeEnum();

    /**
     * 类型专属校验
     * 默认空实现，子类按需重写
     * 约定：此方法只做校验，不修改入参
     * 如需修改 files 内容（如补字段），请重写 enrichFiles()
     * 调用时机：ChatServiceImpl.sendMsg() 中，公共校验之后、enrichFiles 之前
     * 校验失败直接抛 ClientException，阻断发送流程
     * @param content 文本内容（文本消息有值，纯媒体消息可能为 null/空）
     * @param files   文件列表（文本消息为 null/空，媒体消息有值）
     */
    public void checkMsg(String content, List<FileDTO> files) {
        // 默认不校验，子类按需重写
    }

    /**
     * 数据修正/补充
     * 默认空实现，子类按需重写
     * 与 checkMsg 的职责划分：
     *   checkMsg   → 纯校验，不改数据，失败抛异常
     *   enrichFiles → 修正/补充 files 中的字段，不抛异常
     * 典型用途：
     *   VoiceMsgHandler：补 audio=true（vue-advanced-chat 语音播放器依赖此字段）
     *   未来 VideoMsgHandler：可补 preview 默认封面
     * 调用时机：checkMsg 之后、buildBodyJson 之前
     * 修正后的 files 会被 buildBodyJson 序列化存入 DB
     * @param files 文件列表（可直接修改其中 FileDTO 的字段）
     */
    public void enrichFiles(List<FileDTO> files) {
        // 默认不处理，子类按需重写
    }

    /**
     * 构建消息体 JSON（存入 t_message.body）
     * 默认实现：将 files 列表序列化为 JSON 字符串
     *   文本消息：files 为空 → 返回 null → DB 的 body 列存 NULL
     *   媒体消息：files 非空 → 返回 JSON 数组字符串
     * 格式对齐 vue-advanced-chat 的 files 结构，存什么返什么，零转换
     * 一般不需要子类重写。除非某种类型需要在 files 基础上额外加工
     * @return JSON 字符串，或 null（文本消息）
     */
    @Nullable
    public String buildBodyJson(List<FileDTO> files) {
        if (files == null || files.isEmpty()) {
            return null;
        }
        return JsonUtil.toJson(files);
    }

    /**
     * 构建消息文本摘要（存入 t_message.content）
     * 子类必须实现
     * 用途：搜索、通知预览、日志打印、降级展示
     * 规则：
     *   文本消息 → 原文
     *   纯媒体消息 → "[图片]" / "[视频]" / "[语音]" / "[文件] xxx.pdf"
     *   文本+媒体 → 原文（附带的文字优先展示）
     * @param content 文本内容
     * @param files   文件列表
     * @return 非空字符串（t_message.content 是 NOT NULL）
     */
    public abstract String buildContentSummary(String content, List<FileDTO> files);
}