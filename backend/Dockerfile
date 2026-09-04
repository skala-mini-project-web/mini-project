FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew \
    && ./gradlew dependencies --no-daemon

COPY src ./src
RUN ./gradlew bootJar --no-daemon \
    && cp "$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' | head -n 1)" /workspace/app.jar

FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system argus \
    && useradd --system --gid argus --home-dir /app argus

WORKDIR /app
COPY --from=builder --chown=argus:argus /workspace/app.jar ./app.jar

USER argus
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
