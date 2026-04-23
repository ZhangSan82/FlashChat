package com.flashchat.chatservice.service.crypto;

import com.flashchat.chatservice.config.MessageCryptoProperties;
import com.flashchat.chatservice.dao.entity.MessageDO;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MessageContentCodecTest {

    @Test
    void encodeForStorageShouldKeepLegacyContentWhenCryptoDisabled() {
        MessageContentCodec codec = new MessageContentCodec(new MessageCryptoService(disabledProperties()));
        MessageDO source = buildMessage("hello flashchat");

        MessageDO encoded = codec.encodeForStorage(source);

        assertEquals("hello flashchat", encoded.getContent());
        assertNull(encoded.getContentCipher());
        assertNull(encoded.getContentIv());
        assertNull(encoded.getKeyVersion());
    }

    @Test
    void encodeForStorageShouldEncryptContentWhenCryptoEnabled() {
        MessageContentCodec codec = new MessageContentCodec(new MessageCryptoService(enabledProperties()));
        MessageDO source = buildMessage("hello flashchat");

        MessageDO encoded = codec.encodeForStorage(source);

        assertNull(encoded.getContent());
        assertNotNull(encoded.getContentCipher());
        assertNotNull(encoded.getContentIv());
        assertEquals(1, encoded.getKeyVersion());
    }

    @Test
    void decodeContentShouldReturnPlaintextFromEncryptedRow() {
        MessageContentCodec codec = new MessageContentCodec(new MessageCryptoService(enabledProperties()));
        MessageDO source = buildMessage("hello flashchat");

        MessageDO encoded = codec.encodeForStorage(source);

        assertEquals("hello flashchat", codec.decodeContent(encoded));
    }

    @Test
    void decodeContentShouldFallbackToLegacyContent() {
        MessageContentCodec codec = new MessageContentCodec(new MessageCryptoService(disabledProperties()));
        MessageDO source = buildMessage("legacy content");

        assertEquals("legacy content", codec.decodeContent(source));
    }

    private MessageDO buildMessage(String content) {
        return MessageDO.builder()
                .id(100L)
                .msgId("msg-100")
                .roomId("room-1")
                .senderId(1L)
                .nickname("tester")
                .avatarColor("#000000")
                .content(content)
                .msgType(1)
                .status(0)
                .isHost(0)
                .build();
    }

    private MessageCryptoProperties enabledProperties() {
        MessageCryptoProperties properties = new MessageCryptoProperties();
        properties.setEnabled(true);
        properties.setKeyVersion(1);
        properties.setKey(Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        return properties;
    }

    private MessageCryptoProperties disabledProperties() {
        return new MessageCryptoProperties();
    }
}
