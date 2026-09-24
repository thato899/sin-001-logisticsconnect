FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY . .
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre
ARG SERVICE
ENV SERVICE=${SERVICE}
WORKDIR /app
COPY --from=build /workspace/${SERVICE}/target/${SERVICE}.jar /app/service.jar
RUN mkdir -p /data && chown 10001:10001 /data
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/service.jar"]
