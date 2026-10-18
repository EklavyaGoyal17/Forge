# syntax=docker/dockerfile:1
# One image: Spring Boot API + built UI + the shaded runner jar (the only code runner JVMs load).

FROM node:22-alpine AS ui
WORKDIR /ui
COPY forge-ui/package.json forge-ui/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY forge-ui/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY forge-lab/pom.xml forge-lab/
COPY forge-server/pom.xml forge-server/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests dependency:go-offline -pl forge-lab -am || true
COPY forge-lab/src forge-lab/src
COPY forge-server/src forge-server/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S forge && adduser -S -G forge -H -s /sbin/nologin forge
WORKDIR /app
COPY --from=build /src/forge-server/target/forge-server.jar /app/forge-server.jar
COPY --from=build /src/forge-lab/target/forge-lab-*-runner.jar /app/forge-runner.jar
COPY --from=ui /ui/dist /app/ui
USER forge
ENV FORGE_RUNNER_CLASSPATH=/app/forge-runner.jar \
    FORGE_RUNNER_WORK_ROOT=/tmp/forge-runs \
    FORGE_UI_DIR=/app/ui \
    FORGE_BIND_ADDRESS=0.0.0.0
EXPOSE 8090
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s CMD wget -qO- http://127.0.0.1:8090/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=40", "-Djava.io.tmpdir=/tmp", "-jar", "/app/forge-server.jar"]
