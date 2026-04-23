package com.flashchat.chatservice.service.crypto;

import com.flashchat.chatservice.config.MessageCryptoProperties;
import com.flashchat.chatservice.dao.entity.MessageDO;
import com.flashchat.chatservice.dao.mapper.MessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageCryptoBackfillServiceTest {

    @Mock
    private MessageMapper messageMapper;

    private MessageCryptoBackfillService backfillService;

    @BeforeEach
    void setUp() {
        MessageCryptoProperties properties = new MessageCryptoProperties();
        properties.setEnabled(true);
        properties.setKeyVersion(1);
        properties.setKey(Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        MessageContentCodec codec = new MessageContentCodec(new MessageCryptoService(properties));
        backfillService = new MessageCryptoBackfillService(messageMapper, codec, properties);
    }

    @Test
    void backfillNextBatchShouldEncryptLegacyRowsAndPersistBatch() {
        when(messageMapper.selectList(any())).thenReturn(List.of(buildMessage(101L, "legacy-1"), buildMessage(102L, "legacy-2")));
        when(messageMapper.updateCryptoBatch(anyList())).thenReturn(2);

        int updated = backfillService.backfillNextBatch(100);

        assertEquals(2, updated);
        ArgumentCaptor<List<MessageDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(messageMapper).updateCryptoBatch(captor.capture());
        List<MessageDO> persisted = captor.getValue();
        assertEquals(2, persisted.size());
        assertNull(persisted.get(0).getContent());
        assertNotNull(persisted.get(0).getContentCipher());
        assertNotNull(persisted.get(0).getContentIv());
        assertEquals(1, persisted.get(0).getKeyVersion());
    }

    @Test
    void backfillNextBatchShouldSkipWhenNoLegacyRowsRemain() {
        when(messageMapper.selectList(any())).thenReturn(List.of());

        int updated = backfillService.backfillNextBatch(100);

        assertEquals(0, updated);
        verify(messageMapper, never()).updateCryptoBatch(anyList());
    }

    @Test
    void backfillNextBatchShouldRequireCryptoWriteEnabled() {
        MessageCryptoProperties properties = new MessageCryptoProperties();
        MessageContentCodec codec = new MessageContentCodec(new MessageCryptoService(properties));
        MessageCryptoBackfillService disabledService = new MessageCryptoBackfillService(messageMapper, codec, properties);

        assertThrows(IllegalStateException.class, () -> disabledService.backfillNextBatch(100));
    }

    private MessageDO buildMessage(Long id, String content) {
        return MessageDO.builder()
                .id(id)
                .msgId("msg-" + id)
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
}
