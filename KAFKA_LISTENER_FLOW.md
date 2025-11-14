# Game-Service Kafka 监听和 WebSocket 断连流程

## 完整调用链

```
1. Gateway 用户登出
   ↓
2. Gateway 发布事件到 Kafka Topic: session-invalidated
   ↓
3. SessionEventConsumer（在 session-kafka-notifier 模块中）
   ↓ 使用 @KafkaListener 注解监听 Kafka
   ↓
4. SessionEventConsumer.consumeSessionInvalidated() 收到消息
   ↓
5. 解析 JSON 为 SessionInvalidatedEvent
   ↓
6. 遍历所有 SessionEventListener 实现（通过 Spring 自动注入）
   ↓
7. 调用 SessionInvalidatedListener.onSessionInvalidated()（在 game-service 中）
   ↓
8. 查询用户所有 WebSocket 会话
   ↓
9. 发送踢人通知 + 强制断开连接
```

## 关键文件位置

### 1. Kafka 监听器（实际监听 Kafka 的地方）

**文件**：`libs/session-kafka-notifier/src/main/java/com/gamehub/sessionkafkanotifier/listener/SessionEventConsumer.java`

**关键代码**：
```java
@Component
public class SessionEventConsumer {
    
    // 自动注入所有实现了 SessionEventListener 的 Bean
    private final List<SessionEventListener> listeners;
    
    // 这是实际监听 Kafka 的地方！
    @KafkaListener(topics = "${session.kafka.topic:session-invalidated}", 
                   containerFactory = "sessionKafkaListenerContainerFactory")
    public void consumeSessionInvalidated(String message, Acknowledgment ack) {
        // 1. 解析消息
        SessionInvalidatedEvent event = JSON.parseObject(message, SessionInvalidatedEvent.class);
        
        // 2. 调用所有监听器（包括 game-service 的 SessionInvalidatedListener）
        for (SessionEventListener listener : listeners) {
            listener.onSessionInvalidated(event);  // ← 这里调用 game-service 的监听器
        }
    }
}
```

**关键点**：
- `@KafkaListener` 注解让这个方法自动监听 Kafka topic
- Spring 会自动注入所有 `SessionEventListener` 实现到 `listeners` 列表
- 收到消息后，遍历所有监听器并调用

### 2. 业务监听器（处理 WebSocket 断连）

**文件**：`apps/game-service/src/main/java/com/gamehub/gameservice/platform/ws/SessionInvalidatedListener.java`

**关键代码**：
```java
@Component  // ← 被 Spring 扫描并注册为 Bean
public class SessionInvalidatedListener implements SessionEventListener {
    
    @Override
    public void onSessionInvalidated(SessionInvalidatedEvent event) {
        // 1. 查询用户所有 WebSocket 会话
        List<WebSocketSessionInfo> wsSessions = sessionRegistry.getWebSocketSessions(userId);
        
        // 2. 过滤出 game-service 的会话
        List<WebSocketSessionInfo> gameServiceSessions = wsSessions.stream()
                .filter(session -> "game-service".equals(session.getService()))
                .toList();
        
        // 3. 对每个会话执行断连
        for (WebSocketSessionInfo session : gameServiceSessions) {
            sendKickMessage(userId, session.getSessionId(), event);  // 发送踢人通知
            forceDisconnect(session.getSessionId());                 // 强制断开连接
            sessionRegistry.unregisterWebSocketSession(session.getSessionId());  // 清理注册表
        }
    }
}
```

**关键点**：
- 实现了 `SessionEventListener` 接口
- 被 `@Component` 注解标记，Spring 会自动注册为 Bean
- 会被 `SessionEventConsumer` 自动发现并调用

## 连接机制：Spring 的依赖注入

### 启动时的 Bean 注册

```
1. Spring 扫描所有 @Component 注解
   ↓
2. 发现 SessionInvalidatedListener（在 game-service 中）
   ↓ 实现了 SessionEventListener 接口
   ↓ 被 @Component 标记
   ↓
3. 注册为 Bean，类型是 SessionEventListener
   ↓
4. SessionEventConsumer 构造函数需要 List<SessionEventListener>
   ↓
5. Spring 自动注入所有 SessionEventListener 实现
   ↓ 包括：SessionInvalidatedListener（game-service）
   ↓
6. listeners 列表包含 game-service 的监听器
```

### 运行时调用

```
Kafka 收到消息
  ↓
SessionEventConsumer.consumeSessionInvalidated() 被调用
  ↓
遍历 listeners 列表
  ↓
发现 SessionInvalidatedListener（game-service）
  ↓
调用 listener.onSessionInvalidated(event)
  ↓
执行 WebSocket 断连逻辑
```

## 为什么找不到监听的地方？

**可能的原因**：
1. **Kafka 监听器在公共模块中**：`SessionEventConsumer` 在 `session-kafka-notifier` 模块，不在 `game-service` 中
2. **业务逻辑分离**：实际的 Kafka 监听和业务处理是分开的
3. **通过接口连接**：通过 `SessionEventListener` 接口连接，不是直接调用

## 验证方法

### 1. 查看启动日志

启动 game-service 时，应该看到：
```
会话事件消费者初始化完成，发现 1 个监听器
```

如果看到这个日志，说明：
- `SessionEventConsumer` 已加载
- `SessionInvalidatedListener` 已被发现并注入

### 2. 查看 Bean 列表

在 `SessionEventConsumer` 构造函数中添加日志：
```java
@Autowired(required = false)
public SessionEventConsumer(List<SessionEventListener> listeners) {
    this.listeners = listeners != null ? listeners : List.of();
    log.info("会话事件消费者初始化完成，发现 {} 个监听器", this.listeners.size());
    // 打印所有监听器
    listeners.forEach(listener -> 
        log.info("监听器: {}", listener.getClass().getName())
    );
}
```

应该看到：
```
监听器: com.gamehub.gameservice.platform.ws.SessionInvalidatedListener
```

### 3. 测试流程

1. 启动 game-service
2. 用户登录并建立 WebSocket 连接
3. 在 Gateway 登出
4. 查看 game-service 日志，应该看到：
   ```
   收到会话失效事件，开始断开用户 WebSocket 连接: userId=xxx
   用户 xxx 在 game-service 中有 1 个 WebSocket 连接，开始断开
   已断开用户 xxx 的 WebSocket 连接
   ```

## 总结

**Kafka 监听在哪里？**
- **实际监听**：`SessionEventConsumer`（在 `session-kafka-notifier` 模块中）
- **业务处理**：`SessionInvalidatedListener`（在 `game-service` 中）

**如何连接？**
- 通过 `SessionEventListener` 接口
- Spring 自动注入所有实现到 `SessionEventConsumer.listeners` 列表
- 收到消息后，遍历列表并调用所有监听器

**关键点**：
- `@KafkaListener` 注解让 `SessionEventConsumer.consumeSessionInvalidated()` 自动监听 Kafka
- `@Component` 注解让 `SessionInvalidatedListener` 被 Spring 注册为 Bean
- Spring 的依赖注入机制自动连接它们

