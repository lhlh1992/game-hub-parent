package com.gamehub.session.config;

import com.gamehub.session.SessionRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 会话管理专用的 Redis 配置。
 *
 * 说明：
 * - 本配置创建一个独立的 {@link RedisTemplate}，只用于“会话管理”（登录会话 + WebSocket 会话）。
 * - 这样做可以与业务缓存隔离（独立 database/实例），互不影响。
 */
@Configuration
@ConditionalOnProperty(prefix = "session.redis", name = "host")
public class SessionRedisConfig {

    /**
     * 供会话注册表注入的专用 RedisTemplate 的 Bean 名称。
     */
    public static final String SESSION_REDIS_TEMPLATE_BEAN = "sessionRedisTemplate";

    /**
     * 创建会话管理专用的 RedisTemplate（字符串 Key/Value 序列化）。
     *
     * @param host     Redis 主机
     * @param port     Redis 端口
     * @param database Redis 库编号（建议独立，例如 15）
     * @param password Redis 密码（可为空）
     * @return 专用的 RedisTemplate
     */
    @Bean(name = SESSION_REDIS_TEMPLATE_BEAN)
    public RedisTemplate<String, String> sessionRedisTemplate(
            @Value("${session.redis.host}") String host,
            @Value("${session.redis.port:6379}") int port,
            @Value("${session.redis.database:15}") int database,
            @Value("${session.redis.password:}") String password) {

        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host, port);
        if (password != null && !password.isEmpty()) {
            configuration.setPassword(password);
        }
        configuration.setDatabase(database);

        LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();

        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 创建会话注册表 Bean。
     * 
     * @param redis 会话管理专用的 RedisTemplate
     * @return SessionRegistry 实例
     */
    @Bean
    public SessionRegistry sessionRegistry(
            @Qualifier(SESSION_REDIS_TEMPLATE_BEAN) RedisTemplate<String, String> redis) {
        return new SessionRegistry(redis);
    }
}
