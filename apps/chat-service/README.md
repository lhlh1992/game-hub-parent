# Chat-Service 服务文档

> 快速理解 chat-service 的代码和逻辑，无需逐个查看文件

---

## 📖 文档导航

本服务提供了**三层文档**，从概览到细节，帮助你快速理解代码：

### 🎯 推荐阅读顺序

1. **[架构概览](./ARCHITECTURE_OVERVIEW.md)** ⭐ **从这里开始**
   - 服务定位和核心功能
   - 架构分层图
   - 关键组件职责
   - 数据模型关系
   - **阅读时间：10-15 分钟**

2. **[核心流程](./CORE_FLOWS.md)** 🔄 **理解业务逻辑**
   - 私聊消息发送流程
   - 会话列表查询流程
   - 历史消息查询流程
   - WebSocket 连接管理
   - **阅读时间：15-20 分钟**

3. **[快速参考](./QUICK_REFERENCE.md)** 📋 **日常查阅**
   - API 接口速查
   - 数据结构说明
   - Redis Key 规范
   - 常见问题解答
   - **按需查阅**

---

## 🚀 快速开始

### 如果你是新接触这个服务

**第一步：阅读架构概览**
```bash
打开 → ARCHITECTURE_OVERVIEW.md
重点看：
  - 服务定位（支持哪些聊天类型）
  - 架构分层（Controller → Service → Repository）
  - 数据模型（三个核心实体）
```

**第二步：理解核心流程**
```bash
打开 → CORE_FLOWS.md
重点看：
  - 私聊消息发送流程（最复杂）
  - 会话列表查询流程（最常用）
```

**第三步：查看代码**
```bash
推荐阅读顺序：
1. ChatWsController（入口）
2. ChatMessagingServiceImpl（核心逻辑）
3. ChatSessionServiceImpl（会话管理）
4. Entity（数据模型）
```

### 如果你需要查找特定信息

- **API 接口** → [快速参考 - HTTP REST API](./QUICK_REFERENCE.md#-http-rest-api)
- **WebSocket 路由** → [快速参考 - WebSocket API](./QUICK_REFERENCE.md#-websocket-api)
- **数据库表结构** → [快速参考 - 数据库表结构](./QUICK_REFERENCE.md#-数据库表结构)
- **Redis Key 规范** → [快速参考 - Redis Key 规范](./QUICK_REFERENCE.md#-redis-key-规范)

---

## 📊 服务概览

**chat-service** 是平台的社交域核心服务，负责所有实时消息通信功能。

### 核心功能

| 功能 | 说明 | 存储 |
|------|------|------|
| 大厅聊天 | 全局频道，所有在线用户可见 | Redis（仅缓存） |
| 房间聊天 | 游戏房间内的实时交流 | Redis + PostgreSQL |
| 私聊消息 | 点对点通信，仅双方可见 | Redis + PostgreSQL |
| 系统通知 | 系统主动推送的通知 | WebSocket 推送 |

### 技术栈

- **框架**：Spring Boot 3.x + Spring WebSocket + STOMP
- **安全**：Spring Security + OAuth2 Resource Server (Keycloak JWT)
- **存储**：PostgreSQL (JPA/Hibernate) + Redis
- **服务调用**：Spring Cloud OpenFeign + Resilience4j 熔断
- **消息队列**：Kafka (房间事件订阅)

---

## 🎓 学习路径

### 路径1：快速理解（30分钟）

1. 阅读 [架构概览](./ARCHITECTURE_OVERVIEW.md)（15分钟）
2. 阅读 [核心流程 - 私聊消息发送](./CORE_FLOWS.md#1-私聊消息发送流程)（10分钟）
3. 查看 `ChatWsController` 和 `ChatMessagingServiceImpl` 代码（5分钟）

### 路径2：深入理解（1-2小时）

1. 阅读所有文档（45分钟）
2. 阅读关键代码文件（30-45分钟）
   - Controller 层：`ChatWsController`、`ChatSessionController`
   - Service 层：`ChatMessagingServiceImpl`、`ChatSessionServiceImpl`、`ChatHistoryServiceImpl`
   - Entity 层：`ChatSession`、`ChatMessage`、`ChatSessionMember`
3. 理解 WebSocket 配置（15分钟）
   - `WebSocketStompConfig`
   - `WebSocketAuthChannelInterceptor`
   - `WebSocketSessionManager`

### 路径3：全面掌握（半天）

1. 完成路径2的所有内容
2. 阅读完整技术文档（详细版）
   - [中文技术文档](../docs/zh/chat-service/chat-service技术文档.md)
   - [English Technical Doc](../docs/en/chat-service/chat-service-technical-doc.md)
3. 理解基础设施层
   - `SystemUserClient`（Feign 调用）
   - `UserProfileCacheService`（用户信息缓存）
   - `RoomEventConsumer`（Kafka 消费者）

---

## 🔍 关键文件速查

| 文件 | 职责 | 文档位置 |
|------|------|----------|
| `ChatWsController` | WebSocket 消息入口 | [架构概览 - 关键文件速查](./ARCHITECTURE_OVERVIEW.md#-关键文件速查) |
| `ChatMessagingServiceImpl` | 消息发送核心逻辑 | [核心流程 - 私聊消息发送](./CORE_FLOWS.md#1-私聊消息发送流程) |
| `ChatSessionServiceImpl` | 会话管理 | [核心流程 - 会话列表查询](./CORE_FLOWS.md#2-会话列表查询流程) |
| `ChatHistoryServiceImpl` | 历史消息管理 | [核心流程 - 历史消息查询](./CORE_FLOWS.md#3-历史消息查询流程) |
| `WebSocketSessionManager` | WebSocket 连接管理 | [核心流程 - WebSocket 连接建立](./CORE_FLOWS.md#4-websocket-连接建立流程) |

---

## 💡 设计亮点

1. **分层清晰**：Controller → Service → Repository，职责明确
2. **缓存策略**：Redis 缓存 + PostgreSQL 持久化，兼顾性能和可靠性
3. **幂等保证**：通过 `client_op_id` 防止消息重复
4. **连接管理**：后连踢前，确保单用户单连接
5. **降级策略**：Redis 故障时使用内存降级，Feign 调用失败时记录日志

---

## 📝 文档维护

- **架构概览**：当架构发生重大变化时更新
- **核心流程**：当业务流程发生变化时更新
- **快速参考**：当 API 或数据结构变化时更新

---

## 🔗 相关资源

- [项目总体架构文档](../docs/zh/项目总体蓝图与架构层级图.md)
- [详细技术文档（中文）](../docs/zh/chat-service/chat-service技术文档.md)
- [详细技术文档（英文）](../docs/en/chat-service/chat-service-technical-doc.md)

---

**最后更新**：2025-01-XX  
**文档版本**：v1.0




















