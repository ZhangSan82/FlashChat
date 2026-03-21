package com.flashchat.chatservice.service;

import com.flashchat.chatservice.dto.msg.FileDTO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传服务
 * 当前实现：FileServiceImpl（本地磁盘存储）
 * 未来演进：
 *   Phase 2 → OssFileServiceImpl（接入 MinIO 或 OSS）
 *   Phase 3 → 前端直传 OSS，后端只做签名
 */
public interface FileService {

    /**
     * 上传文件
     * 返回的 FileDTO 可直接放入 SendMsgReqDTO.files 数组
     * 上传接口能确定的字段：name, size, type, url, preview
     * 上传接口不确定的字段：audio, duration（由前端补充）
     * @param file Spring MultipartFile
     * @return FileDTO（对齐 vue-advanced-chat 的 file 对象格式）
     */
    FileDTO upload(MultipartFile file);
}