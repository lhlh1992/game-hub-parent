# Session Kafka Notifier

会话失效事件通知库：基于 Kafka 实现跨服务的会话失效事件广播，支持手动提交 offset。

> 说明：本库只负责“如何通过 Kafka 发送/消费事件”，**会话领域事件本身（`SessionInvalidatedEvent`）定义在 `session-common` 中**，与 Kafka 解耦。

## 核心功能

- **事件发布**：通过 `SessionEventPublisher` 发布 `com.gamehub.session.event.SessionInvalidatedEvent` 到 Kafka
- **事件消费**：自动监听 Kafka 消息，解析为领域事件并调用所有注册的 `SessionEventListener` 实现
- **手动提交**：使用手动提交 offset，确保消息处理成功后才提交
- **自动配置**：通过 Spring Boot AutoConfiguration 自动启用（需配置 `session.kafka.bootstrap-servers`）

## 快速开始

> 注意：库已通过 Spring Boot AutoConfiguration 自动注册（Spring Boot 2.7+/3.x 支持 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 机制），只需添加依赖并配置 `session.kafka.*` 即可。

### 1. 引入依赖

```xml
<dependency>
    <groupId>com.gamehub</groupId>
    <artifactId>session-kafka-notifier</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. 配置 Kafka

```yaml
session:
  kafka:
    bootstrap-servers: localhost:9092
    topic: session-invalidated  # 可选，默认 session-invalidated
    consumer:
      group-id: session-invalidated-group  # 可选，默认 session-invalidated-group
```

### 3. 使用方式

#### 3.1 发布事件（生产者端，如 gateway、system-service）

```java
@Autowired
private SessionEventPublisher publisher;

// 用户登出时
publisher.publishSessionInvalidated(userId, SessionInvalidatedEvent.EventType.LOGOUT);

// 修改密码时
publisher.publishSessionInvalidated(userId, SessionInvalidatedEvent.EventType.PASSWORD_CHANGED, "用户修改密码");

// 管理员强制下线
publisher.publishSessionInvalidated(userId, SessionInvalidatedEvent.EventType.FORCE_LOGOUT);
```

#### 3.2 监听事件（消费者端，如 game-service）

实现 `SessionEventListener` 接口：

```java
@Component
public class GameServiceSessionListener implements SessionEventListener {
    
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    
    @Autowired
    private MessageChannel clientInboundChannel;
    
    @Override
    public void onSessionInvalidated(SessionInvalidatedEvent event) {
        String userId = event.getUserId();
        
        // 1. 发送踢人通知
        messagingTemplate.convertAndSendToUser(
            userId, 
            "/queue/kick", 
            Map.of("reason", event.getReason())
        );
        
        // 2. 强制断开 WebSocket 连接
        // ... 断开逻辑
    }
}
```

**注意**：可以有多个服务同时实现 `SessionEventListener`，事件消费者会自动调用所有实现。

## 事件类型

- `LOGOUT`：用户登出
- `PASSWORD_CHANGED`：修改密码
- `USER_DISABLED`：用户被禁用
- `FORCE_LOGOUT`：管理员强制下线
- `OTHER`：其他原因

## 手动提交机制

- 消费者配置为手动提交模式（`enable-auto-commit: false`，`ack-mode: manual`）
- 只有当所有 `SessionEventListener` 实现都成功处理事件后，才会调用 `ack.acknowledge()` 提交 offset
- 如果任何监听器处理失败，offset 不会提交，消息会被重新消费（注意：需要配置重试策略避免无限重试）

## 自动配置机制

- 自动装配入口文件：`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- 配置类：`SessionKafkaNotifierAutoConfiguration`
- 触发条件：配置了 `session.kafka.bootstrap-servers`
- 仅在 Spring Boot 2.7+ / 3.x 生效；低版本需改用 `spring.factories` 或手动 `@Import`

## 架构说明

```
┌─────────────┐
│   Gateway   │ ──发布事件──> Kafka ──消费事件──> ┌──────────────┐
│ system-svc  │                                    │ game-service │
└─────────────┘                                    │ chat-service │
                                                   │  ...         │
                                                   └──────────────┘
```

1. **发布端**（gateway/system-service）：调用 `SessionEventPublisher` 发布事件
2. **Kafka**：作为消息中间件，保证消息可靠传递
3. **消费端**（game-service 等）：实现 `SessionEventListener`，自动接收并处理事件

## 注意事项

1. **Kafka 集群**：需要提前部署 Kafka 集群，并创建对应的 topic
2. **消费者组**：每个服务实例应使用不同的 `group-id`，或使用相同的 `group-id` 实现负载均衡
3. **消息可靠性**：生产者配置了 `acks=all` 和 `enable.idempotence=true`，确保消息不丢失
4. **错误处理**：监听器处理失败时，消息不会提交 offset，会被重新消费；建议在监听器中实现幂等性，避免重复处理造成问题
5. **性能考虑**：如果监听器处理较慢，可能影响 Kafka 消费速度，建议异步处理或优化监听器逻辑

