# syntax=docker/dockerfile:1

FROM public.ecr.aws/docker/library/maven:3.9.16-eclipse-temurin-21-noble AS builder

WORKDIR /workspace
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B package -DskipTests

FROM public.ecr.aws/docker/library/eclipse-temurin:21.0.12_8-jre-noble

WORKDIR /app
RUN groupadd --system app \
    && useradd --system --gid app --home-dir /app app \
    && mkdir -p /app/logs/nacos \
    && chown -R app:app /app

COPY --from=builder --chown=app:app /workspace/target/healthy-agent-*.jar /app/app.jar

USER app
EXPOSE 8091
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
