# libs 公共模块使用指南

## 目录

1. [Spring Boot 自动配置机制](#spring-boot-自动配置机制)
2. [AutoConfiguration.imports 详解](#autoconfigurationimports-详解)
3. [公共模块引入方式](#公共模块引入方式)
4. [自动配置入口类详解](#自动配置入口类详解)
5. [如何在应用中使用公共模块](#如何在应用中使用公共模块)
6. [常见问题](#常见问题)

---

## Spring Boot 自动配置机制

### 什么是自动配置？

Spring Boot 的自动配置（Auto-Configuration）是一种"约定优于配置"的机制，让开发者只需要：
1. 引入依赖
2. 配置必要的属性
3. Spring Boot 自动创建和配置所需的 Bean

### 自动配置的工作流程

```
1. Spring Boot 启动
   ↓
2. 扫描所有 jar 包中的 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
   ↓
3. 读取文件内容，获取所有自动配置类的全限定名
   ↓
4. 根据条件注解（@ConditionalOnProperty 等）决定是否加载
   ↓
5. 加载配置类，执行配置逻辑
   ↓
6. 创建和注册 Bean
```

### 关键文件位置

```
your-module.jar
└── META-INF/
    └── spring/
        └── org.springframework.boot.autoconfigure.AutoConfiguration.imports  ← 关键文件
```

---

## AutoConfiguration.imports 详解

### 文件作用

`AutoConfiguration.imports` 是一个**清单文件**，告诉 Spring Boot：
- **要加载哪些自动配置类**
- **按什么顺序加载**

### 文件格式

每行一个类的全限定名（Fully Qualified Name）：

```
com.gamehub.session.config.SessionRedisConfig
com.gamehub.sessionkafkanotifier.config.SessionKafkaNotifierAutoConfiguration
```

### 文件位置

必须在以下路径：
```
src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

### 工作原理

```
AutoConfiguration.imports 文件内容：
com.gamehub.session.config.SessionRedisConfig

Spring Boot 看到后：
"好的，我要加载 SessionRedisConfig 这个类"
  ↓
检查类上的条件注解（如 @ConditionalOnProperty）
  ↓
如果条件满足 → 加载类，执行配置逻辑
如果条件不满足 → 跳过
```

### 重要限制

**`AutoConfiguration.imports` 可以写任何类，但 Spring Boot 只会把它当作"配置类"来处理！**

**核心机制**：
- Spring Boot 启动时会把你写进去的类当作 **Configuration Class** 来解析
- 如果类没有 `@Configuration`、`@AutoConfiguration` 或 `@Bean` 方法，Spring Boot 不会做任何处理
- 写 `@Component`、`@Service`、`@Repository` 等类**技术上可以写，但不会生效，也不会变成 Bean**

**正确的理解**：
- ✅ **推荐写法**：写自动配置类（包含 `@AutoConfiguration`、`@Configuration` 或 `@Bean` 方法的类）
- ❌ **不推荐写法**：写普通组件类（`@Component`、`@Service`、`@Repository`），因为不会生效

**为什么"技术上能写，但不生效"？**
- `AutoConfiguration.imports` 的机制是：Spring Boot 会把写进去的类当作 **Configuration Class** 来解析
- 如果你写 `com.xx.MyService`（只有 `@Service` 注解），Spring Boot 会尝试把它当作配置类处理
- 但它没有 `@Configuration` 或 `@Bean` 方法，所以 Spring Boot 不会注册任何 Bean
- 结果：这个类不会被注册为 Bean，也不会生效

---

## 公共模块引入方式

### 方式一：直接加载配置类（适用于只有 @Configuration 的模块）

**适用场景**：所有 Bean 都通过 `@Bean` 方法创建，没有 `@Component` 类

**说明**：这是传统方式，适用于简单的模块。但**不推荐**，因为未来扩展性不好。

#### 1. AutoConfiguration.imports 文件

```
com.gamehub.session.config.SessionRedisConfig
```

#### 2. 配置类

```java
@Configuration
@ConditionalOnProperty(prefix = "session.redis", name = "host")
public class SessionRedisConfig {
    
    @Bean
    public RedisTemplate<String, String> sessionRedisTemplate(...) {
        // 创建 RedisTemplate
    }
    
    @Bean
    public SessionRegistry sessionRegistry(...) {
        // 创建 SessionRegistry
    }
}
```

#### 3. 工作流程

```
AutoConfiguration.imports → SessionRedisConfig（@Configuration）
  ↓
Spring Boot 加载 SessionRedisConfig
  ↓
执行 @Bean 方法
  ↓
创建所有 Bean
  ↓
完成！
```

**缺点**：
- ❌ 如果未来要加 `@Component` 类，需要改成方式二
- ❌ 扩展性不好

---

### 方式二：通过自动配置入口类 ⭐ **推荐方式**

**适用场景**：**所有情况**（包括只有 `@Configuration` 的情况）

**说明**：这是**推荐的、通用的方式**，适用于所有场景。即使当前只有 `@Configuration` 类，也推荐使用这种方式，因为：
- ✅ 未来扩展性好（加 `@Component` 类不需要改结构）
- ✅ 统一模式（所有模块都用同一种方式）
- ✅ 符合 Spring Boot Starter 最佳实践

**当前使用情况**：`session-common` 和 `session-kafka-notifier` 都使用这种方式。

#### 1. AutoConfiguration.imports 文件

```
com.gamehub.session.config.SessionCommonAutoConfiguration
com.gamehub.sessionkafkanotifier.config.SessionKafkaNotifierAutoConfiguration
```

#### 2. 自动配置入口类

```java
@AutoConfiguration
@ConditionalOnProperty(prefix = "session.redis", name = "host")
@ComponentScan(basePackages = "com.gamehub.session")
public class SessionCommonAutoConfiguration {
    // 空的！不需要任何方法！
    // 它的作用是：开关 + 扫描器
}
```

**关键注解说明**：
- `@AutoConfiguration`：标记为自动配置类
- `@ConditionalOnProperty`：条件加载（开关），控制整个模块是否启用
- `@ComponentScan`：包扫描（扫描器），扫描包下的所有组件

#### 3. 配置类（不需要 @ConditionalOnProperty）

```java
@Configuration  // 只保留 @Configuration，条件由入口类统一管理
public class SessionRedisConfig {
    
    @Bean
    public RedisTemplate<String, String> sessionRedisTemplate(...) {
        // 创建 RedisTemplate
    }
    
    @Bean
    public SessionRegistry sessionRegistry(...) {
        // 创建 SessionRegistry
    }
}
```

**注意**：配置类上不需要 `@ConditionalOnProperty`，因为条件控制由入口类统一管理。

#### 4. 工作流程

```
AutoConfiguration.imports → SessionCommonAutoConfiguration
  ↓
Spring Boot 加载 SessionCommonAutoConfiguration
  ↓
检查 @ConditionalOnProperty（开关）
  ↓
如果条件满足 → 执行 @ComponentScan(basePackages = "com.gamehub.session")
  ↓
扫描包，发现所有组件：
  - SessionRedisConfig（@Configuration）← 会被扫描到
  - SessionRegistry（普通类，通过 @Bean 创建）← 正常
  - 未来可能的 @Component 类 ← 也能被扫描到
  ↓
加载所有组件
  ↓
完成！
```

**优势**：
- ✅ **通用性强**：适用于所有情况（只有 `@Configuration` 或 有 `@Component`）
- ✅ **未来扩展性好**：加 `@Component` 类不需要改结构
- ✅ **统一模式**：所有公共模块都用同一种方式
- ✅ **职责清晰**：入口类负责"开关"，配置类负责"配置"

---

## 自动配置入口类详解

### 什么是自动配置入口类？

**自动配置入口类（Auto Configuration Entry Class）** 是一个特殊的配置类，它的作用是：
1. **开关**：通过 `@ConditionalOnProperty` 控制是否启用
2. **扫描器**：通过 `@ComponentScan` 扫描包下的所有组件

### 为什么需要它？

**核心原因**：`AutoConfiguration.imports` 会把类当作配置类处理，写 `@Component` 类不会生效，也不会变成 Bean。

如果有 `@Component` 类需要被加载，就需要：
1. 一个自动配置入口类（在 `AutoConfiguration.imports` 中列出，带 `@Configuration` 或 `@AutoConfiguration`）
2. 这个类提供 `@ComponentScan` 来扫描包，发现并注册 `@Component` 类

### 标准写法

```java
@AutoConfiguration  // 标记为自动配置类
@ConditionalOnProperty(prefix = "xxx", name = "xxx")  // 条件加载（开关）
@ComponentScan(basePackages = "com.gamehub.xxx")  // 包扫描（扫描器）
public class XxxAutoConfiguration {
    // 空的！不需要任何方法！
    // 它的作用是：开关 + 扫描器
}
```

### 关键理解

**这个类本身可以是空的，不需要任何业务代码！**

它的作用就是：
- **作为"启动开关"**：告诉 Spring Boot 要启用这个模块
- **作为"扫描器"**：扫描包下的所有组件并注册

---

## 如何在应用中使用公共模块

### 步骤 1：引入依赖

在应用的 `pom.xml` 中添加依赖：

```xml
<dependency>
    <groupId>com.gamehub</groupId>
    <artifactId>session-common</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<dependency>
    <groupId>com.gamehub</groupId>
    <artifactId>session-kafka-notifier</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 步骤 2：配置属性

在 `application.yml` 中配置必要的属性：

```yaml
# session-common 配置
session:
  redis:
    host: localhost
    port: 6379
    database: 15
    password: ""

# session-kafka-notifier 配置
session:
  kafka:
    bootstrap-servers: localhost:9092
    topic: session-invalidated
    consumer:
      group-id: my-service-session-group
```

### 步骤 3：自动生效

**无需任何额外配置！** Spring Boot 会自动：
1. 读取 `AutoConfiguration.imports` 文件
2. 加载自动配置类
3. 根据条件注解决定是否启用
4. 创建和注册 Bean

### 步骤 4：使用 Bean

直接注入使用：

```java
@Service
public class MyService {
    
    @Autowired
    private SessionRegistry sessionRegistry;  // session-common 提供的
    
    @Autowired
    private SessionEventPublisher sessionEventPublisher;  // session-kafka-notifier 提供的
}
```

---

## 常见问题

### Q1: 为什么 `SessionKafkaNotifierAutoConfiguration` 类是空的？

**A**: 因为它的作用是"开关 + 扫描器"，不需要业务逻辑。

- `@ConditionalOnProperty`：开关（条件加载）
- `@ComponentScan`：扫描器（扫描包）

实际的业务逻辑在：
- `SessionKafkaConfig`：Kafka 配置
- `SessionEventPublisher`：事件发布
- `SessionEventConsumer`：事件消费

### Q2: 可以直接在 `AutoConfiguration.imports` 中写 `@Component` 类吗？

**A**: ❌ 技术上可以写，但不会生效！

`AutoConfiguration.imports` 会把类当作配置类处理。如果你写 `@Component` 类，Spring Boot 会尝试把它当作配置类解析，但它没有 `@Configuration` 或 `@Bean` 方法，所以不会注册任何 Bean，也不会生效。

如果有 `@Component` 类，需要：
1. 创建一个自动配置入口类（`@Configuration` 或 `@AutoConfiguration`）
2. 在入口类上添加 `@ComponentScan` 来扫描包
3. 在 `AutoConfiguration.imports` 中写入口类（不是 `@Component` 类）

### Q3: 为什么推荐使用自动配置入口类方式？

**A**: 因为这种方式更通用、扩展性更好。

**优势**：
- ✅ 适用于所有情况（只有 `@Configuration` 或 有 `@Component`）
- ✅ 未来扩展性好（加 `@Component` 类不需要改结构）
- ✅ 统一模式（所有模块都用同一种方式）
- ✅ 符合 Spring Boot Starter 最佳实践

即使当前只有 `@Configuration` 类，也推荐使用自动配置入口类方式。

### Q4: 如何验证自动配置是否生效？

**A**: 查看启动日志：

```
# session-common 生效
Creating shared instance of singleton bean 'sessionRedisTemplate'
Creating shared instance of singleton bean 'sessionRegistry'

# session-kafka-notifier 生效
会话事件消费者初始化完成，发现 X 个监听器
```

### Q5: 自动配置不生效怎么办？

**检查清单**：
1. ✅ 依赖是否正确引入？
2. ✅ `AutoConfiguration.imports` 文件是否存在且路径正确？
3. ✅ 文件内容是否正确（类的全限定名）？
4. ✅ 条件注解是否满足（如 `@ConditionalOnProperty`）？
5. ✅ 配置属性是否正确？

### Q6: Spring Boot 版本要求

**A**: 
- **Spring Boot 2.7+ 和 3.x**：支持 `AutoConfiguration.imports`（推荐方式）
- **Spring Boot 2.6 及以下**：需要使用 `spring.factories`（已废弃，但仍在低版本中使用）

#### 低版本 Spring Boot 的替代方式（spring.factories）

如果你的项目使用的是 **Spring Boot 2.6 及以下版本**，需要使用 `spring.factories` 文件替代 `AutoConfiguration.imports`。

##### 1. 文件位置

```
src/main/resources/META-INF/spring.factories
```

##### 2. 文件格式

```properties
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
com.gamehub.session.config.SessionCommonAutoConfiguration,\
com.gamehub.sessionkafkanotifier.config.SessionKafkaNotifierAutoConfiguration
```

**注意**：
- 使用 `=` 分隔键值对
- 使用 `\` 进行换行（可选，也可以写在一行）
- 多个类用 `,` 分隔

##### 3. 完整示例

**session-common 的 spring.factories**：
```properties
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
com.gamehub.session.config.SessionCommonAutoConfiguration
```

**session-kafka-notifier 的 spring.factories**：
```properties
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
com.gamehub.sessionkafkanotifier.config.SessionKafkaNotifierAutoConfiguration
```

##### 4. 兼容性处理

如果你的公共模块需要同时支持高版本和低版本 Spring Boot，可以**同时提供两个文件**：

```
src/main/resources/
├── META-INF/
│   ├── spring/
│   │   └── org.springframework.boot.autoconfigure.AutoConfiguration.imports  ← Spring Boot 2.7+
│   └── spring.factories  ← Spring Boot 2.6 及以下
```

**文件内容相同**（只是格式不同）：

**AutoConfiguration.imports**（Spring Boot 2.7+）：
```
com.gamehub.session.config.SessionCommonAutoConfiguration
```

**spring.factories**（Spring Boot 2.6 及以下）：
```properties
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
com.gamehub.session.config.SessionCommonAutoConfiguration
```

Spring Boot 会优先使用 `AutoConfiguration.imports`（如果存在），否则回退到 `spring.factories`。

##### 5. 版本兼容性总结

| Spring Boot 版本 | 使用方式 | 文件路径 |
|-----------------|---------|---------|
| **2.7+ / 3.x** | `AutoConfiguration.imports` | `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` |
| **2.6 及以下** | `spring.factories` | `META-INF/spring.factories` |
| **同时支持** | 两个文件都提供 | 两个文件都存在，Spring Boot 优先使用 `AutoConfiguration.imports` |

##### 6. 迁移建议

- **新项目**：直接使用 `AutoConfiguration.imports`（Spring Boot 2.7+）
- **老项目（Spring Boot 2.6 及以下）**：使用 `spring.factories`
- **需要兼容的项目**：同时提供两个文件

---

## 总结

### 核心要点

1. **`AutoConfiguration.imports` 是清单文件**：告诉 Spring Boot 要加载哪些自动配置类
2. **Spring Boot 会把类当作配置类处理**：技术上可以写任何类，但只有 `@Configuration`、`@AutoConfiguration` 或包含 `@Bean` 方法的类才会生效
3. **写 `@Component` 类不会生效**：Spring Boot 不会注册这些类为 Bean
4. **自动配置入口类的作用**：开关 + 扫描器
5. **推荐使用自动配置入口类方式**：
   - ✅ 通用性强，适用于所有情况
   - ✅ 未来扩展性好
   - ✅ 统一模式，易于维护
   - ✅ 符合 Spring Boot Starter 最佳实践

### 设计原则

- **约定优于配置**：引入依赖 + 配置属性 = 自动生效
- **条件加载**：通过 `@ConditionalOnProperty` 控制是否启用
- **开箱即用**：无需手动 `@Import` 或 `@ComponentScan`

---

## 参考

- [Spring Boot Auto-Configuration](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.developing-auto-configuration)
- [Spring Boot Starter 最佳实践](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.developing-auto-configuration.custom-starter)

