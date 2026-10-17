FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY core/build.gradle.kts core/
COPY site/build.gradle.kts site/
# The workbench and auth are never in the image, but Gradle configures every project in settings.gradle.kts.
COPY workbench/build.gradle.kts workbench/
COPY auth/build.gradle.kts auth/
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon :core:dependencies :site:dependencies > /dev/null
COPY core/src core/src
COPY site/src site/src
# The API contract the controller interfaces and models are generated from: the repository's api/, passed as the
# build context named "api" (docker-compose.yml, and the images job in CI).
COPY --from=api openapi.yaml /api/openapi.yaml
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon :site:bootJar \
    && cp "$(find site/build/libs -name '*.jar' ! -name '*-plain.jar')" /app.jar

# Ubuntu 24.04: the 26.04 base the plain 25-jre tag now carries ships pebble, built with a Go that has a critical
# vulnerability, though this image never runs it. Back to 25-jre once a newer pebble lands there.
FROM eclipse-temurin:25-jre-noble
RUN useradd --system --home /app app
WORKDIR /app
COPY --from=build /app.jar app.jar
USER app
# The image holds no data: the release is mounted read-only at /app/release (docker-compose.yml).
ENV SPRING_PROFILES_ACTIVE=prod \
    APP_RELEASE_DIR=/app/release \
    JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
