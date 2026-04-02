package com.flashchat.gameservice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 游戏模块配置项注册。
 */
@Configuration
@EnableConfigurationProperties({
        GameAiProperties.class
})
public class GamePropertiesConfig {
}
