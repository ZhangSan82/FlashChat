package com.flashchat.chatservice.service.crypto;

import com.flashchat.chatservice.config.MessageCryptoProperties;
import com.flashchat.convention.errorcode.BaseErrorCode;
import com.flashchat.convention.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 消息正文加解密底层服务。
 *
 * <p>这一层负责维护密钥环、当前写入版本、AES-GCM 加解密细节。
 * 它不直接感知 MySQL 实体字段，只处理 codec 层传入的原始正文和 AAD。</p>
 */
@Slf4j
@Service
public class MessageCryptoService {

    private static final String KEY_ALGORITHM = "AES";

    private final MessageCryptoProperties properties;
    private final Map<Integer, SecretKey> keyRing;
    private final SecureRandom secureRandom;

    @Autowired
    public MessageCryptoService(MessageCryptoProperties properties) {
        this(properties, new SecureRandom());
    }

    // 保留这个包级构造器，方便单测注入可控 SecureRandom。
    MessageCryptoService(MessageCryptoProperties properties, SecureRandom secureRandom) {
        this.properties = properties;
        this.secureRandom = secureRandom;
        this.keyRing = buildKeyRing(properties);
    }

    public boolean isWriteEnabled() {
        return properties.isEnabled();
    }

    public boolean canDecrypt() {
        return !keyRing.isEmpty();
    }

    /**
     * 使用当前写入版本对应的密钥加密正文。
     */
    public EncryptedContent encrypt(String plaintext, byte[] aad) {
        if (!isWriteEnabled()) {
            throw new IllegalStateException("message crypto write is disabled");
        }
        if (plaintext == null) {
            return new EncryptedContent(null, null, properties.getKeyVersion());
        }

        SecretKey writeKey = keyRing.get(properties.getKeyVersion());
        if (writeKey == null) {
            throw new IllegalStateException("flashchat.message.crypto current key version is not configured");
        }

        try {
            byte[] iv = new byte[properties.getIvLengthBytes()];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(properties.getAlgorithm());
            cipher.init(Cipher.ENCRYPT_MODE, writeKey, new GCMParameterSpec(properties.getTagLengthBits(), iv));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }

            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new EncryptedContent(
                    Base64.getEncoder().encodeToString(encrypted),
                    Base64.getEncoder().encodeToString(iv),
                    properties.getKeyVersion()
            );
        } catch (Exception e) {
            log.error("[message crypto encrypt failed] keyVersion={}", properties.getKeyVersion(), e);
            throw new ServiceException("message crypto encrypt failed", e, BaseErrorCode.SERVICE_ERROR);
        }
    }

    /**
     * 按消息记录中的 {@code keyVersion} 选择对应密钥解密。
     *
     * <p>这也是密钥轮换后仍然能读取旧数据的关键能力。</p>
     */
    public String decrypt(String cipherText, String iv, Integer keyVersion, byte[] aad) {
        if (!canDecrypt()) {
            throw new ServiceException("message crypto keys are not configured", BaseErrorCode.SERVICE_ERROR);
        }
        if (cipherText == null || cipherText.isBlank()) {
            return null;
        }
        if (keyVersion == null) {
            throw new ServiceException("message crypto key version is missing", BaseErrorCode.SERVICE_ERROR);
        }

        SecretKey decryptKey = keyRing.get(keyVersion);
        if (decryptKey == null) {
            throw new ServiceException("message crypto key version is not supported", BaseErrorCode.SERVICE_ERROR);
        }

        try {
            Cipher cipher = Cipher.getInstance(properties.getAlgorithm());
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    decryptKey,
                    new GCMParameterSpec(properties.getTagLengthBits(), Base64.getDecoder().decode(iv))
            );
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            byte[] plaintext = cipher.doFinal(Base64.getDecoder().decode(cipherText));
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("[message crypto decrypt failed] keyVersion={}", keyVersion, e);
            throw new ServiceException("message crypto decrypt failed", e, BaseErrorCode.SERVICE_ERROR);
        }
    }

    /**
     * 在 Bean 初始化阶段一次性构建内存中的密钥环。
     */
    private Map<Integer, SecretKey> buildKeyRing(MessageCryptoProperties properties) {
        Map<Integer, SecretKey> resolved = new LinkedHashMap<>();
        parseKeyring(properties.getKeyring()).forEach((version, base64Key) ->
                resolved.put(version, buildSecretKey(base64Key, "flashchat.message.crypto.keyring[" + version + "]")));

        if (properties.getKey() != null && !properties.getKey().isBlank()) {
            resolved.put(properties.getKeyVersion(),
                    buildSecretKey(properties.getKey(), "flashchat.message.crypto.key"));
        }

        if (properties.isEnabled() && !resolved.containsKey(properties.getKeyVersion())) {
            throw new IllegalStateException("flashchat.message.crypto current key version is not configured");
        }
        return Collections.unmodifiableMap(resolved);
    }

    /**
     * 解析配置中的 {@code version:base64} 密钥对。
     */
    private Map<Integer, String> parseKeyring(String rawKeyring) {
        if (rawKeyring == null || rawKeyring.isBlank()) {
            return Collections.emptyMap();
        }

        Map<Integer, String> resolved = new LinkedHashMap<>();
        String[] entries = rawKeyring.split("[,;\\r\\n]+");
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String[] parts = entry.trim().split(":", 2);
            if (parts.length != 2) {
                throw new IllegalStateException("flashchat.message.crypto.keyring entry must use version:base64 format");
            }
            try {
                resolved.put(Integer.parseInt(parts[0].trim()), parts[1].trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("flashchat.message.crypto.keyring version must be integer", e);
            }
        }
        return resolved;
    }

    /**
     * 校验 Base64 密钥并转换成 AES Key 对象。
     */
    private SecretKey buildSecretKey(String base64Key, String propertyName) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(base64Key);
            int bits = keyBytes.length * 8;
            if (bits != 128 && bits != 192 && bits != 256) {
                throw new IllegalStateException("unsupported AES key length: " + bits);
            }
            return new SecretKeySpec(keyBytes, KEY_ALGORITHM);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(propertyName + " must be valid Base64", e);
        }
    }
}
