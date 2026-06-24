# syntax=docker/dockerfile:1.7
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY packages/spring-starter/pom.xml packages/spring-starter/pom.xml
COPY services/backend/applicant-api/pom.xml services/backend/applicant-api/pom.xml
COPY packages/spring-starter/src packages/spring-starter/src
RUN --mount=type=secret,id=github_token \
    token="$(cat /run/secrets/github_token)" \
    && mkdir -p /root/.m2 \
    && printf '<settings><servers><server><id>github</id><username>x-access-token</username><password>%s</password></server></servers></settings>' "$token" > /root/.m2/settings.xml \
    && mvn -B -f packages/spring-starter/pom.xml -DskipTests install \
    && rm -f /root/.m2/settings.xml
COPY services/backend/applicant-api/src services/backend/applicant-api/src
RUN --mount=type=secret,id=github_token \
    token="$(cat /run/secrets/github_token)" \
    && mkdir -p /root/.m2 \
    && printf '<settings><servers><server><id>github</id><username>x-access-token</username><password>%s</password></server></servers></settings>' "$token" > /root/.m2/settings.xml \
    && mvn -B -f services/backend/applicant-api/pom.xml -DskipTests package spring-boot:repackage \
    && rm -f /root/.m2/settings.xml

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN addgroup --system spark && adduser --system --ingroup spark spark
COPY --from=build /workspace/services/backend/applicant-api/target/applicant-api-*.jar /app/applicant-api.jar
USER spark
EXPOSE 8080 9090
ENTRYPOINT ["java", "-jar", "/app/applicant-api.jar"]
