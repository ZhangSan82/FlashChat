package com.flashchat.chatservice.stream;

import com.flashchat.chatservice.config.MessageCryptoProperties;
import com.flashchat.chatservice.dao.entity.MessageDO;
import com.flashchat.chatservice.dao.mapper.MessageMapper;
import com.flashchat.chatservice.service.crypto.MessageContentCodec;
import com.flashchat.chatservice.service.crypto.MessageCryptoService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class MessageStreamConsumerTest {

    @Test
    void encodeBatchForStorageShouldEncryptEveryMessage() {
        MessageStreamConsumer consumer = new MessageStreamConsumer(
                mock(StringRedisTemplate.class),
                mock(MessageMapper.class),
                buildCodec()
        );

        List<MessageDO> encoded = consumer.encodeBatchForStorage(List.of(buildMessage("hello"), buildMessage("world")));

        assertEquals(2, encoded.size());
        assertNull(encoded.get(0).getContent());
        assertNotNull(encoded.get(0).getContentCipher());
        assertNull(encoded.get(1).getContent());
        assertNotNull(encoded.get(1).getContentCipher());
    }

    private MessageDO buildMessage(String content) {
        return MessageDO.builder()
                .id(100L)
                .msgId("msg-" + content)
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

    private MessageContentCodec buildCodec() {
        MessageCryptoProperties properties = new MessageCryptoProperties();
        properties.setEnabled(true);
        properties.setKeyVersion(1);
        properties.setKey(Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        return new MessageContentCodec(new MessageCryptoService(properties));
    }
}
