# 多阶段构建：第一阶段 - Maven 编译
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /app

# 先复制 pom.xml 利用 Docker 缓存层，依赖不常变
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 复制源码并编译
COPY src ./src
RUN mvn clean package -DskipTests -B

# 多阶段构建：第二阶段 - JRE 运行
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# 创建应用用户（非 root 运行更安全）
RUN addgroup -S spring && adduser -S spring -G spring

# 复制编译产物
COPY --from=builder /app/target/seckill-mall-0.0.1-SNAPSHOT.jar app.jar

# 创建日志目录
RUN mkdir -p /app/logs && chown -R spring:spring /app

# 切换到非 root 用户
USER spring:spring

# 暴露端口
EXPOSE 8080

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# 启动应用
ENTRYPOINT ["java", \
    "-Xms256m", \
    "-Xmx512m", \
    "-jar", \
    "app.jar", \
    "--spring.profiles.active=${SPRING_PROFILES_ACTIVE:-docker}"]