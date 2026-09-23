# Multi-stage build. The repo deliberately does not commit the Maven wrapper
# (see .gitignore), so the build stage brings its own pinned Maven + JDK 21
# rather than relying on a platform's toolchain auto-detection.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Resolve dependencies as their own layer so a source-only change does not
# re-download the world on every deploy.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests are skipped here on purpose: the integration suite needs a live
# PostgreSQL (see application-test.yml), which does not exist inside a build
# container. Tests run in CI and locally, not as a gate on image assembly.
RUN mvn -B -q clean package -DskipTests


FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Never run the application as root.
RUN groupadd --system spring && useradd --system --gid spring spring

COPY --from=build --chown=spring:spring /build/target/*.jar app.jar

USER spring

# MaxRAMPercentage rather than a fixed -Xmx: the JVM reads the container's
# memory limit, so the heap scales with whatever plan the service runs on.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

# Railway (and most platforms) inject the listening port via $PORT; the
# application.yml default keeps plain `docker run` working unchanged.
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
