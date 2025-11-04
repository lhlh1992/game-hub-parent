# 使用多阶段构建
FROM maven:3.9-eclipse-temurin-21 AS builder

# 设置工作目录
WORKDIR /build

# 复制父 POM
COPY pom.xml ./pom.xml

# 复制 Gateway 模块
COPY gateway/pom.xml ./gateway/pom.xml
COPY gateway/src ./gateway/src

# 下载依赖（利用 Docker 缓存层）
RUN mvn dependency:go-offline -f pom.xml -pl gateway

# 构建应用
RUN mvn clean package -f pom.xml -pl gateway -DskipTests

# 运行阶段
FROM eclipse-temurin:21-jre-alpine

# 安装 wget 用于健康检查
RUN apk add --no-cache wget

# 创建应用用户（安全最佳实践）
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

WORKDIR /app

# 从构建阶段复制 JAR
COPY --from=builder --chown=spring:spring /build/gateway/target/gateway-*.jar app.jar

# 暴露端口
EXPOSE 8080

# 健康检查
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# 启动应用
ENTRYPOINT ["java", "-jar", "app.jar"]

