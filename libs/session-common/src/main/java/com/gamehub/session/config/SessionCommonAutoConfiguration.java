package com.gamehub.session.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;

/**
 * 会话管理自动配置入口类。
 * 
 * 作为 session-common 模块的"入口开关"，负责：
 * - 条件加载（@ConditionalOnProperty）：只有配置了 session.redis.host 才启用
 * - 包扫描（@ComponentScan）：扫描 com.gamehub.session 包下的所有组件
 * 
 * 即使当前只有 @Configuration 类，使用这种方式也为未来扩展提供了灵活性：
 * - 当前：扫描到 SessionRedisConfig（@Configuration），正常加载
 * - 未来：如果添加 @Component 类，也会被自动扫描到，无需修改结构
 * 
 * 这是 Spring Boot Starter 的标准做法，与 session-kafka-notifier 保持一致。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "session.redis", name = "host")
@ComponentScan(basePackages = "com.gamehub.session")
public class SessionCommonAutoConfiguration {
    // 空的！不需要任何方法！
    // 它的作用是：开关 + 扫描器
    // - @ConditionalOnProperty：控制是否启用（开关）
    // - @ComponentScan：扫描包下的所有组件（扫描器）
}

