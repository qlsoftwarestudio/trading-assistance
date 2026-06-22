# -------- Stage 1: Build
FROM gradle:8.8-jdk21 AS builder

WORKDIR /app
COPY . .

RUN gradle clean bootJar -x test --no-daemon

# -------- Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy jar from builder
COPY --from=builder /app/build/libs/trading-assistant.jar app.jar

EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/api/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
