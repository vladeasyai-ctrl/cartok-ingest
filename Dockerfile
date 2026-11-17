# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /workspace

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

RUN addgroup -S cartok \
    && adduser -S cartok -G cartok

COPY --from=build --chown=cartok:cartok /workspace/target/cartok-ingest.jar app.jar

USER cartok

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
