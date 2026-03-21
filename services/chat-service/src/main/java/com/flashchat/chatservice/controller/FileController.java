package com.flashchat.chatservice.controller;

import com.flashchat.chatservice.dto.msg.FileDTO;
import com.flashchat.chatservice.service.FileService;
import com.flashchat.convention.result.Result;
import com.flashchat.convention.result.Results;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传控制器
 * 定位：消息发送的前置依赖
 *   用户选择文件 → 调用此接口上传 → 拿到 URL → 放入发消息请求的 files 数组
 * 当前实现：最简版（本地磁盘存储 + Spring 静态资源映射）
 * 未来演进：
 *   Phase 2 → 接入 MinIO 或 OSS，加缩略图生成
 *   Phase 3 → 前端直传 OSS（绕过后端），后端只做签名
 * 安全措施：
 *   1. 文件大小：Spring multipart 配置限制（默认 10MB）
 *   2. 文件类型：黑名单拦截危险后缀（.jsp/.sh/.exe 等）
 *   3. 文件名：UUID 重命名，消除路径穿越风险
 *
 * TODO: 接口限流（防止恶意大量上传）
 * TODO: 身份认证（当前无鉴权，任何人可上传）
 */
@Slf4j
@RestController
@RequestMapping("/api/FlashChat/v1/file")
@CrossOrigin(origins = "*")
@AllArgsConstructor
public class FileController {

    private final FileService fileService;

    /**
     * 上传文件
     * 返回的 FileDTO 可直接放入 SendMsgReqDTO.files 数组
     * 前端可能需要补充的字段：
     *   audio：语音消息设为 true（Handler 的 enrichFiles 会兜底补）
     *   duration：语音/视频时长（前端通过 HTML5 Audio/Video API 获取）
     * @param file 上传的文件（multipart/form-data）
     * @return FileDTO（含 name, size, type, url, preview）
     */
    @PostMapping("/upload")
    public Result<FileDTO> upload(@RequestParam("file") MultipartFile file) {
        log.info("[文件上传] name={}, size={}, type={}",
                file.getOriginalFilename(), file.getSize(), file.getContentType());
        return Results.success(fileService.upload(file));
    }
}