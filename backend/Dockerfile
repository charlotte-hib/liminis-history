# Three images from one build: the site's API (the default, last stage), and the workbench and auth for the review
# on the server (--target workbench, --target auth; compose.review.yaml).
# Their builds share Gradle's cache, one at a time (sharing=locked): two Gradles at once time out on its lock.
FROM eclipse-temurin:25-jdk AS sources
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY core/build.gradle.kts core/
COPY site/build.gradle.kts site/
# Gradle configures every project in settings.gradle.kts, whichever one is built.
COPY workbench/build.gradle.kts workbench/
COPY auth/build.gradle.kts auth/

FROM sources AS build
RUN --mount=type=cache,target=/root/.gradle,sharing=locked ./gradlew --no-daemon :core:dependencies :site:dependencies > /dev/null
COPY core/src core/src
COPY site/src site/src
# The API contract the controller interfaces and models are generated from: the repository's api/, passed as the
# build context named "api" (docker-compose.yml, and the images job in CI).
COPY --from=api openapi.yaml /api/openapi.yaml
RUN --mount=type=cache,target=/root/.gradle,sharing=locked ./gradlew --no-daemon :site:bootJar \
    && cp "$(find site/build/libs -name '*.jar' ! -name '*-plain.jar')" /app.jar

FROM sources AS workbench-build
COPY core/src core/src
COPY workbench/src workbench/src
COPY --from=api workbench.yaml /api/workbench.yaml
RUN --mount=type=cache,target=/root/.gradle,sharing=locked ./gradlew --no-daemon :workbench:bootJar \
    && cp "$(find workbench/build/libs -name '*.jar' ! -name '*-plain.jar')" /app.jar

FROM sources AS auth-build
COPY auth/src auth/src
COPY --from=api auth.yaml /api/auth.yaml
RUN --mount=type=cache,target=/root/.gradle,sharing=locked ./gradlew --no-daemon :auth:bootJar \
    && cp "$(find auth/build/libs -name '*.jar' ! -name '*-plain.jar')" /app.jar

# Ubuntu 24.04, here and for the site: the 26.04 base the plain 25-jre tag now carries ships pebble, built with a Go
# that has a critical vulnerability, though these images never run it. Back to 25-jre once a newer pebble lands there.
FROM eclipse-temurin:25-jre-noble AS review
# One user id across the review's images (curate's too): auth writes the clients' secrets to a volume curate reads.
RUN useradd --uid 10001 --home /app app && mkdir -p /app /secrets && chown app /app /secrets
WORKDIR /app
ENV JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 \
    SERVER_ADDRESS=0.0.0.0

FROM review AS workbench
# What the runs write: the fetched dataset's files (wb export) and Natural Earth's borders, downloaded once.
RUN mkdir dataset-export natural-earth && chown app dataset-export natural-earth
COPY --from=workbench-build /app.jar app.jar
USER app
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM review AS auth
COPY --from=auth-build /app.jar app.jar
USER app
EXPOSE 9000
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM eclipse-temurin:25-jre-noble AS site
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
