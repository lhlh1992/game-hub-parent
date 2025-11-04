package com.gamehub.gameservice.infrastructure.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.*;
/**
 * RedisConfig
 * -------------------------------------------------------
 * 全局 Redis 连接与序列化配置类（通用基础设施层）
 * -------------------------------------------------------
 * Responsibilities:
 *  - 提供统一的 RedisTemplate 和 StringRedisTemplate Bean；
 *  - 配置序列化策略（Key: String，Value: JSON）；
 *  - 保证不同模块在操作 Redis 时行为一致；
 *  - 可在任意微服务中复用，无业务耦合。
 * -------------------------------------------------------
 * 使用说明：
 *  - RedisTemplate<String, Object>：适用于存取对象（自动 JSON 序列化）；
 *  - StringRedisTemplate：适用于轻量字符串键值（如计数器、标志位等）。
 * -------------------------------------------------------
 * 未来迁移：
 *  - 该类可直接放入公共模块（如 infra-redis），所有服务共享。
 */
@Configuration
public class RedisConfig {

    /**
     * 通用 RedisTemplate（Key 为 String，Value 为任意对象，自动 JSON 序列化）
     * -------------------------------------------------------
     * Key 采用 StringRedisSerializer，保证键名可读；
     * Value 使用 GenericJackson2JsonRedisSerializer，
     *  可序列化任意对象并携带类型信息（兼容性强）。
     *
     * @param factory Spring Data Redis 提供的连接工厂（Lettuce）
     * @return RedisTemplate<String, Object> Bean
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> tpl = new RedisTemplate<>();
        tpl.setConnectionFactory(factory);

        StringRedisSerializer keySer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valSer = new GenericJackson2JsonRedisSerializer();

        tpl.setKeySerializer(keySer);
        tpl.setValueSerializer(valSer);
        tpl.setHashKeySerializer(keySer);
        tpl.setHashValueSerializer(valSer);

        tpl.afterPropertiesSet();
        return tpl;
    }

    /**
     * 纯字符串操作模板（StringRedisTemplate）
     * -------------------------------------------------------
     * 适合计数、标志位、锁等轻量操作。
     *
     * @param factory Redis 连接工厂
     * @return StringRedisTemplate Bean
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
