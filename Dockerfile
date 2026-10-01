# syntax=docker/dockerfile:1
# Images for the two runnable modules, built from one Dockerfile:
#
#   docker compose --profile game up -d --build      # cluster + server
#   docker compose run --rm tui game-1 alice         # one player
#
# The build stage packages each module with its runtime dependencies next to
# it, so the run stages are a plain JRE and a classpath: no fat jar, no Maven.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
# The cache mount keeps the local Maven repository between builds, so a
# rebuild after a source change does not download the dependencies again.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -DskipTests package dependency:copy-dependencies -DincludeScope=runtime \
      -pl server,client-tui -am

FROM eclipse-temurin:21-jre AS server
WORKDIR /app
COPY --from=build /src/server/target/server-*.jar app.jar
COPY --from=build /src/server/target/dependency/ lib/
ENTRYPOINT ["java", "-cp", "app.jar:lib/*", "com.goosegame.server.GooseServer"]

FROM eclipse-temurin:21-jre AS tui
WORKDIR /app
COPY --from=build /src/client-tui/target/client-tui-*.jar app.jar
COPY --from=build /src/client-tui/target/dependency/ lib/
# The bootstrap address is fixed to the compose network; the arguments of
# `docker compose run` follow it: [gameId [player]].
ENTRYPOINT ["java", "-cp", "app.jar:lib/*", "com.goosegame.tui.Main", "kafka-1:29092,kafka-2:29092,kafka-3:29092"]
