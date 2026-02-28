# ─────────────────────────────────────────────────────────────────────────────
# Crypto Sipper — Multi-stage Dockerfile
# ─────────────────────────────────────────────────────────────────────────────
#
# Stage 1 (builder): compiles the Spring Boot fat-jar with Maven.
# Stage 2 (runtime): copies only the jar into a slim JRE image.
#
# Railway auto-detects this Dockerfile and builds/deploys it automatically.
# ─────────────────────────────────────────────────────────────────────────────

# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /build

# Copy Maven wrapper and pom first (layer-cache friendly)
COPY mvnw pom.xml ./
COPY .mvn .mvn

# Download dependencies (cached unless pom.xml changes)
RUN ./mvnw dependency:go-offline -q

# Copy source and build the fat-jar
COPY src src
RUN ./mvnw package -DskipTests -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Copy the fat-jar from the builder stage
COPY --from=builder /build/target/*.jar app.jar

# Railway injects PORT; Spring Boot reads it via ${PORT:8080}
EXPOSE 8080

# JVM tuning for container environments:
#   -XX:+UseContainerSupport  → respect cgroup memory limits
#   -XX:MaxRAMPercentage=75   → use up to 75% of container RAM for heap
ENTRYPOINT ["java", \
            "-XX:+UseContainerSupport", \
            "-XX:MaxRAMPercentage=75.0", \
            "-jar", "app.jar"]
