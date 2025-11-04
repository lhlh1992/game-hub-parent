# 🌀 Gateway 模块说明

> 模块路径：`game-platform/gateway`  
> 技术栈：**Spring Cloud Gateway (WebFlux + Netty)**  
> 主要作用：**系统统一入口与网关层**

---

## 📖 模块职责

- **统一入口**：所有前端、客户端请求都先经过 Gateway，再路由到后端微服务。
- **路由与负载均衡**：根据路径 `/auth/**`、`/game/**` 等匹配规则，将请求转发到不同服务。
- **统一跨域 (CORS)**：集中处理跨域逻辑，前端只配置网关域名。
- **统一鉴权过滤**：
    - 校验 JWT Token；
    - 游客模式放行；
    - 拦截未授权请求；
    - 统一响应错误格式。
- **限流与防刷**：通过 `RequestRateLimiter` 实现接口限流。
- **日志与 Trace**：
    - 打印访问日志；
    - 注入 TraceId / RequestId；
    - 统一输出请求耗时、状态码。
- **熔断与重试**：下游服务异常时快速响应或自动重试。
- **WebSocket 透传支持**：可转发前端 WebSocket 请求至 `game-service`。
- **监控与健康检查**：提供 `/actuator/**` 指标接口，便于 Prometheus / Grafana 监控。

---

## ⚙️ 主要依赖

| 依赖 | 作用 |
|------|------|
| `spring-cloud-starter-gateway` | 网关核心，基于 WebFlux + Netty |
| `spring-cloud-starter-loadbalancer` | 服务间调用的客户端负载均衡 |
| `spring-boot-starter-actuator` | 健康检查与监控指标 |
| `spring-boot-starter-test` | 测试基础依赖 |

---


