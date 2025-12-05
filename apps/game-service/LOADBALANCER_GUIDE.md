# Spring Cloud LoadBalancer 使用指南

## 📖 什么是 LoadBalancer？

**Spring Cloud LoadBalancer** 是 Spring Cloud 2020+ 默认的负载均衡器，替代了已停用的 Ribbon。

### 核心特点：
- ✅ **不需要注册中心**（如 Eureka/Nacos），可以通过配置文件或服务发现（K8s/Docker DNS）解析服务名
- ✅ **自动负载均衡**：支持多实例自动轮询（Round Robin）
- ✅ **云原生友好**：配合 K8s Service 或 Docker Compose DNS 使用，无需额外配置
- ✅ **轻量级**：相比 Ribbon，更简洁、性能更好

---

## 🚀 当前项目中的使用方式

### 1. FeignClient 配置

```java
@FeignClient(
    name = "system-service",  // 服务名，LoadBalancer 会根据此名称查找服务实例
    path = "/api/users"        // 统一路径前缀
)
public interface SystemUserClient {
    // ...
}
```

**关键点**：只写 `name`，不写 `url`，LoadBalancer 会自动解析服务名。

---

### 2. 配置文件（application.yml）

#### 本地开发环境（单实例）

```yaml
spring:
  cloud:
    loadbalancer:
      clients:
        system-service:
          instances:
            - uri: http://127.0.0.1:8082
```

#### 本地开发环境（多实例测试）

```yaml
spring:
  cloud:
    loadbalancer:
      clients:
        system-service:
          instances:
            - uri: http://127.0.0.1:8082
            - uri: http://127.0.0.1:8083  # 第二个实例
```

**效果**：LoadBalancer 会自动在 8082 和 8083 之间轮询。

---

### 3. Docker Compose 环境

在 `application-docker.yml` 中：

```yaml
spring:
  cloud:
    loadbalancer:
      clients:
        system-service:
          # 不需要配置 instances，容器 DNS 会自动解析服务名
          # LoadBalancer 会自动将 "system-service" 解析为 http://system-service:8082
```

**说明**：
- Docker Compose 会自动为同一服务名的多个容器提供负载均衡
- 如果 `system-service` 有 3 个容器实例，Compose 会自动在它们之间分配流量

---

### 4. Kubernetes 环境

在 `application-k8s.yml` 中：

```yaml
spring:
  cloud:
    loadbalancer:
      clients:
        system-service:
          # 不需要配置 instances，K8s Service + DNS 会自动解析服务名
          # LoadBalancer 会自动将 "system-service" 解析为 http://system-service（K8s Service 名）
```

**说明**：
- K8s Service 会自动为多个 Pod 提供负载均衡（默认是轮询）
- 如果 `system-service` 有 5 个 Pod，K8s Service 会自动在它们之间分配流量

---

## 🔄 多实例负载均衡原理

### 本地开发环境

1. **配置多个实例地址**：
   ```yaml
   spring:
     cloud:
       loadbalancer:
         clients:
           system-service:
             instances:
               - uri: http://127.0.0.1:8082
               - uri: http://127.0.0.1:8083
   ```

2. **启动多个服务实例**：
   ```bash
   # 终端1：启动第一个实例
   java -jar system-service.jar --server.port=8082
   
   # 终端2：启动第二个实例
   java -jar system-service.jar --server.port=8083
   ```

3. **LoadBalancer 自动轮询**：
   - 第一次请求 → `http://127.0.0.1:8082`
   - 第二次请求 → `http://127.0.0.1:8083`
   - 第三次请求 → `http://127.0.0.1:8082`
   - 以此类推...

### Docker Compose 环境

1. **docker-compose.yml 配置**：
   ```yaml
   services:
     system-service:
       build: ./system-service
       deploy:
         replicas: 3  # 3 个实例
   ```

2. **Compose 自动负载均衡**：
   - Compose 会自动为 3 个容器提供负载均衡
   - LoadBalancer 通过容器 DNS 解析服务名，自动在 3 个实例之间分配流量

### Kubernetes 环境

1. **K8s Deployment 配置**：
   ```yaml
   apiVersion: apps/v1
   kind: Deployment
   metadata:
     name: system-service
   spec:
     replicas: 5  # 5 个 Pod
     # ...
   ```

2. **K8s Service 自动负载均衡**：
   - K8s Service 会自动为 5 个 Pod 提供负载均衡（默认是轮询）
   - LoadBalancer 通过 K8s DNS 解析服务名，自动在 5 个实例之间分配流量

---

## 📊 对比：Nacos vs LoadBalancer

| 特性 | Nacos（注册中心） | LoadBalancer（无注册中心） |
|------|------------------|---------------------------|
| **服务发现** | 需要 Nacos Server | 通过配置文件或服务发现（K8s/Docker DNS） |
| **负载均衡** | 自动（轮询/随机） | 自动（轮询/随机） |
| **多实例** | 自动注册和发现 | 通过配置或服务发现 |
| **依赖** | 需要 Nacos Server | 无需额外服务 |
| **云原生** | 需要额外部署 | 配合 K8s/Docker 原生能力 |

---

## 🎯 最佳实践

1. **本地开发**：使用配置文件指定服务地址（单实例或多实例）
2. **Docker Compose**：利用容器 DNS，无需配置 instances
3. **Kubernetes**：利用 K8s Service + DNS，无需配置 instances
4. **多实例测试**：在本地配置多个 instances，验证负载均衡效果

---

## 🔍 调试技巧

### 查看 LoadBalancer 日志

```yaml
logging:
  level:
    org.springframework.cloud.loadbalancer: DEBUG
```

### 验证负载均衡

在 `system-service` 的 Controller 中添加日志：

```java
@GetMapping("/api/users/me")
public ResponseEntity<?> getCurrentUser() {
    log.info("请求到达实例：{}", System.getProperty("server.port"));
    // ...
}
```

然后观察不同实例的日志，确认请求被分配到不同实例。

---

## 📚 参考文档

- [Spring Cloud LoadBalancer 官方文档](https://docs.spring.io/spring-cloud-commons/docs/current/reference/html/#spring-cloud-loadbalancer)
- [Spring Cloud OpenFeign 官方文档](https://docs.spring.io/spring-cloud-openfeign/docs/current/reference/html/)

