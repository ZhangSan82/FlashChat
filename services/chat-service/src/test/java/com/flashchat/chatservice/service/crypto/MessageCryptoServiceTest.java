package com.flashchat.chatservice.service.crypto;

import com.flashchat.chatservice.config.MessageCryptoProperties;
import com.flashchat.convention.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageCryptoServiceTest {

    private static final byte[] AAD = "msg-100|room-1|1|1".getBytes(StandardCharsets.UTF_8);

    @Test
    void encryptShouldUseCurrentWriteKeyVersion() {
        MessageCryptoService service = new MessageCryptoService(properties(true, 2, keyOf("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), null));

        EncryptedContent encrypted = service.encrypt("hello flashchat", AAD);

        assertEquals(2, encrypted.keyVersion());
    }

    @Test
    void decryptShouldSupportLegacyKeyVersionsFromKeyring() {
        MessageCryptoService legacyService = new MessageCryptoService(
                properties(true, 1, keyOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), null));
        EncryptedContent legacyEncrypted = legacyService.encrypt("legacy payload", AAD);

        MessageCryptoService currentService = new MessageCryptoService(
                properties(true, 2, keyOf("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
                        "1:" + keyOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")));

        String plaintext = currentService.decrypt(
                legacyEncrypted.cipherText(),
                legacyEncrypted.iv(),
                legacyEncrypted.keyVersion(),
                AAD
        );

        assertEquals("legacy payload", plaintext);
    }

    @Test
    void decryptShouldFailWhenRequestedVersionMissing() {
        MessageCryptoService legacyService = new MessageCryptoService(
                properties(true, 1, keyOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), null));
        EncryptedContent legacyEncrypted = legacyService.encrypt("legacy payload", AAD);

        MessageCryptoService currentService = new MessageCryptoService(
                properties(true, 2, keyOf("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), null));

        assertThrows(ServiceException.class, () -> currentService.decrypt(
                legacyEncrypted.cipherText(),
                legacyEncrypted.iv(),
                legacyEncrypted.keyVersion(),
                AAD
        ));
    }

    private MessageCryptoProperties properties(boolean enabled, int keyVersion, String key, String keyring) {
        MessageCryptoProperties properties = new MessageCryptoProperties();
        properties.setEnabled(enabled);
        properties.setKeyVersion(keyVersion);
        properties.setKey(key);
        properties.setKeyring(keyring);
        return properties;
    }

    private String keyOf(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
