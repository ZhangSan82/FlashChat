package com.flashchat.chatservice.service.crypto;

/**
 * 消息正文加密结果。
 */
public record EncryptedContent(String cipherText, String iv, int keyVersion) {
}
