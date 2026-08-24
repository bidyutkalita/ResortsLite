# ============================================================
# ResortsLite - Multi-Stage Dockerfile
# Framework : Spring Boot 2.7.x
# Java      : 8 (eclipse-temurin:8-jdk runtime)
# Build Tool: Maven
# ============================================================

# ── Stage 1: Build ──────────────────────────────────────────
FROM maven:3.9.4-eclipse-temurin-8 AS builder

WORKDIR /workspace

# Copy dependency descriptor first for layer-cache optimisation
COPY pom.xml .

# Pre-download all dependencies (cached unless pom.xml changes)
RUN mvn dependency:go-offline -B

# Copy the full source tree
COPY src ./src

# Build the fat JAR (skip tests – tests run in CI pipeline)
RUN mvn clean package -DskipTests -B

# ── Stage 2: Runtime ────────────────────────────────────────
FROM eclipse-temurin:8-jdk

# Metadata
LABEL maintainer="ResortsLite Team" \
      application="resortsLite" \
      version="1.0.0"

# Timezone
ENV TZ=UTC

# JVM tuning – container-aware heap sizing
ENV JAVA_OPTS="-Xms256m -Xmx512m \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -Djava.security.egd=file:/dev/./urandom \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=UTC"

# Spring profile
ENV SPRING_PROFILES_ACTIVE=docker

# Application environment variables (override at runtime)
ENV SERVER_PORT=8080
ENV REPORT_BASE_PATH=/reports
ENV BACKUP_PATH=/backups/nightly
ENV PAYMENT_API_URL=http://payment-service.internal:9090/payments/charge
ENV REDIS_HOST=localhost
ENV REDIS_PORT=6379

WORKDIR /app

# Create non-root user for security
RUN groupadd -r appgroup && useradd -r -g appgroup -d /app -s /sbin/nologin appuser

# Create runtime directories
RUN mkdir -p /reports /backups/nightly /app/logs \
    && chown -R appuser:appgroup /app /reports /backups/nightly

# Copy the fat JAR from the builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Ensure the JAR is owned by the non-root user
RUN chown appuser:appgroup app.jar

USER appuser

EXPOSE 8080

# Graceful shutdown via SIGTERM
STOPSIGNAL SIGTERM

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
