package com.flashchat.chatservice.config;

import com.flashchat.chatservice.audit.AuditProperties;
import com.flashchat.chatservice.ratelimit.RateLimitProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        AuditProperties.class,
        RateLimitProperties.class
})
public class FlashChatPropertiesConfig {
}
