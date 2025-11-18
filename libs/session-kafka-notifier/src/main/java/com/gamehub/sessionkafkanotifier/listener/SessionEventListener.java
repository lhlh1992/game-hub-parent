package com.gamehub.sessionkafkanotifier.listener;

import com.gamehub.session.event.SessionInvalidatedEvent;

/**
 * 会话事件监听器接口。
 * 
 * 各服务实现此接口，处理会话失效事件（如断开 WebSocket 连接）。
 * 
 * 使用方式：
 * <pre>
 * {@code
 * @Component
 * public class GameServiceSessionListener implements SessionEventListener {
 *     @Override
 *     public void onSessionInvalidated(SessionInvalidatedEvent event) {
 *         // 断开该用户的 WebSocket 连接
 *         // ...
 *     }
 * }
 * }
 * </pre>
 */
public interface SessionEventListener {
    
    /**
     * 处理会话失效事件。
     * 
     * @param event 会话失效事件
     */
    void onSessionInvalidated(SessionInvalidatedEvent event);
}

