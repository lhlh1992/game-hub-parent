package com.gamehub.sessionkafkanotifier.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;

/**
 * 会话 Kafka 通知器自动配置类。
 * 
 * 当配置了 session.kafka.bootstrap-servers 时自动启用。
 * 
 * 自动扫描并注册：
 * - {@link SessionKafkaConfig}：Kafka 配置
 * - {@link com.gamehub.sessionkafkanotifier.publisher.SessionEventPublisher}：事件发布器
 * - {@link com.gamehub.sessionkafkanotifier.listener.SessionEventConsumer}：事件消费者
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "session.kafka", name = "bootstrap-servers")
@ComponentScan(basePackages = "com.gamehub.sessionkafkanotifier")
public class SessionKafkaNotifierAutoConfiguration {
    // 自动配置类，无需额外代码
    // 通过 @ComponentScan 自动发现并注册相关组件
}

