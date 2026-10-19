# ---- 阶段1：构建后端 Jar 包 ----
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /app

# 复制 pom.xml 并下载依赖（利用 Docker 缓存层）
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 复制源码并打包
COPY src ./src
RUN mvn clean package -DskipTests -B

# ---- 阶段2：运行 ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
LABEL description="流年小账后端服务"

# 时区：alpine 默认 UTC，必须装 tzdata 才能让 TZ 环境变量生效；
# 同时用 -Duser.timezone 锁定 JVM（JDK 自带 tzdb，双保险）
RUN apk add --no-cache tzdata
ENV TZ=Asia/Shanghai

# 创建非 root 用户运行应用
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# 从 builder 阶段复制后端 jar
COPY --from=builder /app/target/account-backend-1.0.0.jar app.jar

# 创建日志目录、图片上传目录并修正所有者
RUN mkdir -p /app/logs /app/data/upload && chown -R appuser:appgroup /app

# 切换到非 root 用户
USER appuser

# 监听端口由运行时环境变量 SERVER_PORT 决定（dev compose 用 8666，本地默认 8445）。
# 刻意不写 EXPOSE：镜像不应假定端口，否则 docker ps 会显示与实际监听不一致的端口。
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/logs/heapdump.hprof -Duser.timezone=Asia/Shanghai -Dapp.time-zone=Asia/Shanghai"

# 健康检查：跟随 SERVER_PORT（未设置时回落到 application.yml 的默认 8445）
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider "http://localhost:${SERVER_PORT:-8445}/api/health" || exit 1

# 启动应用
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar app.jar"]
