package com.gamehub.sessionkafkanotifier.publisher;

import com.alibaba.fastjson2.JSON;
import com.gamehub.sessionkafkanotifier.event.SessionInvalidatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 会话事件发布器。
 * 
 * 用于发布会话失效事件到 Kafka，供各服务订阅处理。
 * 
 * 使用方式：
 * <pre>
 * {@code
 * @Autowired
 * private SessionEventPublisher publisher;
 * 
 * // 用户登出时
 * publisher.publishSessionInvalidated(userId, SessionInvalidatedEvent.EventType.LOGOUT);
 * }
 * </pre>
 */
@Slf4j
@Component
@ConditionalOnBean(name = "sessionKafkaTemplate")
public class SessionEventPublisher {
    
    private final KafkaTemplate<String, String> kafkaTemplate;
    
    @Value("${session.kafka.topic:session-invalidated}")
    private String topic;
    
    public SessionEventPublisher(@Qualifier("sessionKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }
    
    /**
     * 发布会话失效事件。
     * 
     * @param userId 用户 ID
     * @param eventType 事件类型
     * @param reason 可选原因
     */
    public void publishSessionInvalidated(String userId, SessionInvalidatedEvent.EventType eventType, String reason) {
        SessionInvalidatedEvent event = SessionInvalidatedEvent.of(userId, eventType, reason);
        publishSessionInvalidated(event);
    }
    
    /**
     * 发布会话失效事件（无原因）。
     */
    public void publishSessionInvalidated(String userId, SessionInvalidatedEvent.EventType eventType) {
        publishSessionInvalidated(userId, eventType, null);
    }
    
    /**
     * 发布会话失效事件（使用事件对象）。
     */
    public void publishSessionInvalidated(SessionInvalidatedEvent event) {
        try {
            String message = JSON.toJSONString(event);
            CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(topic, event.getUserId(), message);
            
            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.debug("会话失效事件发布成功: userId={}, offset={}", 
                            event.getUserId(), result.getRecordMetadata().offset());
                } else {
                    log.error("会话失效事件发布失败: userId={}", event.getUserId(), ex);
                }
            });
        } catch (Exception e) {
            log.error("发布会话失效事件异常: userId={}", event.getUserId(), e);
        }
    }
}

