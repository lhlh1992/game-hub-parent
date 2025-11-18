package com.gamehub.sessionkafkanotifier.listener;

import com.alibaba.fastjson2.JSON;
import com.gamehub.session.event.SessionInvalidatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 会话事件消费者。
 * 
 * 自动监听 Kafka 中的会话失效事件，并调用所有注册的 {@link SessionEventListener} 实现。
 * 
 * 注意：使用手动提交 offset，只有所有监听器处理成功后才会提交。
 */
@Slf4j
@Component
public class SessionEventConsumer {
    
    /**
     * 所有注册的会话事件监听器列表。
     * 
     * Spring 会自动注入所有实现了 {@link SessionEventListener} 接口的 Bean。
     * 
     * 为什么使用 List？
     * - 可能有多个服务都实现了 SessionEventListener 接口（如 game-service、chat-service 等）
     * - 每个服务都有自己的业务逻辑需要处理会话失效事件
     * - Spring 的依赖注入机制会自动收集所有实现并注入到这个 List 中
     * - 收到 Kafka 消息后，需要遍历所有监听器并调用，确保所有服务都能处理事件
     * 
     * 示例：
     * - game-service 的 SessionInvalidatedListener：断开 WebSocket 连接
     * - chat-service 的 SessionInvalidatedListener：清理聊天会话
     * - 其他服务的监听器：各自的业务逻辑
     */
    private final List<SessionEventListener> listeners;
    
    @Autowired(required = false)
    public SessionEventConsumer(List<SessionEventListener> listeners) {
        this.listeners = listeners != null ? listeners : List.of();
        log.info("会话事件消费者初始化完成，发现 {} 个监听器", this.listeners.size());
    }
    
    /**
     * 消费会话失效事件。
     * 
     * @param message 消息内容（JSON 字符串）
     * @param ack 手动提交确认对象
     */
    @KafkaListener(topics = "${session.kafka.topic:session-invalidated}", 
                   containerFactory = "sessionKafkaListenerContainerFactory")
    public void consumeSessionInvalidated(String message, Acknowledgment ack) {
        try {
            // 解析事件
            SessionInvalidatedEvent event = JSON.parseObject(message, SessionInvalidatedEvent.class);
            log.debug("收到会话失效事件: userId={}, eventType={}", event.getUserId(), event.getEventType());
            
            // 如果没有监听器，记录警告但提交 offset（因为没有需要处理的逻辑）
            if (listeners.isEmpty()) {
                log.warn("收到会话失效事件，但未发现任何 SessionEventListener 实现: userId={}", event.getUserId());
                ack.acknowledge();
                return;
            }
            
            // 调用所有监听器
            boolean allSuccess = true;
            for (SessionEventListener listener : listeners) {
                try {
                    listener.onSessionInvalidated(event);
                } catch (Exception e) {
                    log.error("监听器处理会话失效事件失败: listener={}, userId={}", 
                            listener.getClass().getName(), event.getUserId(), e);
                    allSuccess = false;
                }
            }
            
            // 只有所有监听器都成功才提交 offset
            if (allSuccess) {
                ack.acknowledge();
                log.debug("会话失效事件处理完成并提交: userId={}", event.getUserId());
            } else {
                log.warn("会话失效事件部分监听器失败，不提交 offset: userId={}", event.getUserId());
                // 注意：不调用 ack.acknowledge()，消息会被重新消费
            }
            
        } catch (Exception e) {
            log.error("消费会话失效事件异常: message={}", message, e);
            // 解析失败也不提交，让消息重新消费
        }
    }
}

