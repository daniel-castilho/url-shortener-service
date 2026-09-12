# Multi-stage build for optimized Docker image (multi-arch: linux/amd64, linux/arm64)
# Build with: docker buildx build --platform linux/amd64,linux/arm64 -t url-shortener .

# Stage 1: Build (Java 25 platform; Maven wrapper 3.9.16)
# VERSION (Epic 8 story 8.2): release builds pass --build-arg VERSION=<semver> so the image
# carries the release identity (org.opencontainers.image.version label). Default "local".
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-25 AS build
ARG VERSION=local
WORKDIR /app

# Copy wrapper + pom.xml and download dependencies (cached layer)
COPY .mvn .mvn
COPY mvnw .
COPY pom.xml .
RUN ./mvnw dependency:go-offline -B

# Copy source code and build
COPY src ./src
RUN ./mvnw package -DskipTests -B -Drevision=$VERSION

# Stage 2: Runtime
FROM --platform=$TARGETPLATFORM eclipse-temurin:25-jre-alpine
ARG VERSION=local
WORKDIR /app

# Release identity (Epic 8 story 8.2): consumed by the release job (Trivy/SBOM read it too)
LABEL org.opencontainers.image.version="${VERSION}"
LABEL org.opencontainers.image.title="URL Shortener Service"
LABEL org.opencontainers.image.description="High-performance link-shortening API with hexagonal architecture"
LABEL org.opencontainers.image.source="https://github.com/daniel-castilho/url-shortener-service"
LABEL org.opencontainers.image.licenses="MIT"

# Create non-root user for security
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copy JAR from build stage
COPY --from=build /app/target/*.jar app.jar

# Expose port
EXPOSE 8080

# Health check - use curl which is available in the base image
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health/liveness || exit 1

# JVM optimization flags
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

# Run application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
