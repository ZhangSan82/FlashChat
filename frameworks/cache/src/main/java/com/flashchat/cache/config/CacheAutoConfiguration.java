package com.flashchat.cache.config;

import com.flashchat.cache.StringRedisTemplateProxy;
import lombok.AllArgsConstructor;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 缓存组件自动装配
 * <p>
 * 注意：
 * 1. 不修改 StringRedisTemplate 的全局 keySerializer，避免影响项目中直接使用 StringRedisTemplate 的代码
 * 2. 不创建通用布隆过滤器 Bean，项目中已有业务专用的布隆过滤器（RBloomFilterConfiguration）
 * 3. 布隆过滤器通过 safeGet/safePut 方法参数传入，由业务方决定用哪个
 */
@AllArgsConstructor
@EnableConfigurationProperties({RedisDistributedProperties.class})
public class CacheAutoConfiguration {

    private final RedisDistributedProperties redisDistributedProperties;

    /**
     * 核心代理类 — 业务代码通过注入 DistributedCache 接口使用
     */
    @Bean
    public StringRedisTemplateProxy stringRedisTemplateProxy(
            StringRedisTemplate stringRedisTemplate,
            RedissonClient redissonClient) {
        return new StringRedisTemplateProxy(
                stringRedisTemplate,
                redisDistributedProperties,
                redissonClient);
    }
}