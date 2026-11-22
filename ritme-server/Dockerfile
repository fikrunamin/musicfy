# Build from the repository root:  docker build -f ritme-server/Dockerfile -t ritme-server .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew ./
COPY gradle ./gradle
COPY modules/ritme-protocol ./modules/ritme-protocol
COPY ritme-server ./ritme-server
RUN ./gradlew --no-daemon -p ritme-server installDist

FROM eclipse-temurin:21-jre
# ExitOnOutOfMemoryError: a JVM that ran out of heap is not trustworthy (threads die, classes fail
# to load, GC thrashes while the process stays alive); exit so `restart: unless-stopped` restarts it.
RUN useradd --system --uid 10001 ritme && mkdir -p /app/web
WORKDIR /app
COPY --from=build /src/ritme-server/build/install/ritme-server /app
USER ritme
ENV PORT=8080 WEB_DIR=/app/web JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["/app/bin/ritme-server"]
