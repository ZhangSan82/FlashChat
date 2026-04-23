package com.flashchat.chatservice.config;

import com.flashchat.chatservice.audit.AuditProperties;
import com.flashchat.chatservice.ratelimit.RateLimitProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        AuditProperties.class,
        RateLimitProperties.class,
        FileStorageProperties.class,
        MailboxProperties.class,
        MessageCryptoProperties.class,
        WebSocketProperties.class
})
public class FlashChatPropertiesConfig {
}
