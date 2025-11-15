package com.gamehub.sessionkafkanotifier.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * 会话事件 Kafka 配置。
 * 
 * 配置生产者和消费者，支持手动提交 offset。
 * 
 * 配置要求（application.yml）：
 * <pre>
 * session:
 *   kafka:
 *     bootstrap-servers: localhost:9092
 *     topic: session-invalidated
 *     consumer:
 *       group-id: session-invalidated-group
 * </pre>
 * 
 * 注意：
 * - 条件控制由 {@link SessionKafkaNotifierAutoConfiguration} 统一管理，此处不需要 @ConditionalOnProperty。
 */
@Configuration
public class SessionKafkaConfig {
    
    /**
     * Kafka 集群地址。
     * 
     * 配置示例：localhost:9092 或 localhost:9092,localhost:9093,localhost:9094（集群模式）
     * 从配置文件 session.kafka.bootstrap-servers 读取
     */
    @Value("${session.kafka.bootstrap-servers}")
    private String bootstrapServers;
    
    /**
     * 消费者组 ID。
     * 
     * 用于标识消费者组，同一组内的消费者会负载均衡消费消息。
     * 默认值：session-invalidated-group
     * 从配置文件 session.kafka.consumer.group-id 读取，未配置时使用默认值
     */
    @Value("${session.kafka.consumer.group-id:session-invalidated-group}")
    private String consumerGroupId;
    
    /**
     * 创建 Kafka 生产者工厂。
     * 
     * 配置生产者的序列化器、可靠性保证等参数。
     * 
     * @return ProducerFactory<String, String> 生产者工厂实例，Key 和 Value 均为 String 类型
     */
    @Bean
    public ProducerFactory<String, String> sessionKafkaProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        
        // Kafka 集群地址（多个 broker 用逗号分隔）
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        
        // Key 序列化器：使用 String 序列化器
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        
        // Value 序列化器：使用 String 序列化器（消息内容为 JSON 字符串）
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        
        // 消息确认机制：all 表示需要等待所有副本都确认收到消息才认为发送成功
        // 可选值：0（不等待）、1（只等待 leader）、all/-1（等待所有副本）
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        
        // 重试次数：发送失败时最多重试 3 次
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        
        // 启用幂等性：确保消息不会重复发送（即使重试也不会产生重复消息）
        // 注意：启用幂等性时，retries 会自动设置为 Integer.MAX_VALUE，acks 必须为 all
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        
        return new DefaultKafkaProducerFactory<>(props);
    }
    
    /**
     * KafkaTemplate（生产者）。
     */
    @Bean
    public KafkaTemplate<String, String> sessionKafkaTemplate() {
        return new KafkaTemplate<>(sessionKafkaProducerFactory());
    }
    
    /**
     * 消费者配置。
     */
    @Bean
    public ConsumerFactory<String, String> sessionKafkaConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroupId);
        // 手动提交 offset
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // 从最新位置开始消费（如果消费者组第一次启动）
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        // 每次拉取的最大记录数
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        return new DefaultKafkaConsumerFactory<>(props);
    }
    
    /**
     * 消费者监听器容器工厂（手动提交）。
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> sessionKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = 
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(sessionKafkaConsumerFactory());
        // 设置手动提交模式
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        // 并发消费者数量
        factory.setConcurrency(1);
        return factory;
    }
}

