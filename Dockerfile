FROM eclipse-temurin:25-jdk-noble AS builder

WORKDIR /workspace

COPY gradle gradle
COPY gradlew gradlew
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY build-logic build-logic
COPY app app

RUN ./gradlew --no-daemon :app:bootJar

FROM eclipse-temurin:25-jdk-noble

RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        ca-certificates \
        curl \
        docker.io \
        docker-compose-v2 \
        git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/my-agent

COPY --from=builder /workspace/app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/opt/my-agent/app.jar"]
