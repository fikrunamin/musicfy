# Build from the repository root:  docker build -f ritme-server/Dockerfile -t ritme-server .
FROM node:24-bookworm-slim AS web
WORKDIR /src/ritme-web
COPY ritme-web/package.json ritme-web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY ritme-web ./
RUN npm run build

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew ./
COPY gradle ./gradle
COPY modules/ritme-protocol ./modules/ritme-protocol
COPY ritme-server ./ritme-server
RUN ./gradlew --no-daemon -p ritme-server installDist

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 ritme
WORKDIR /app
COPY --from=build /src/ritme-server/build/install/ritme-server /app
COPY --from=web /src/ritme-web/dist /app/web
USER ritme
# ExitOnOutOfMemoryError: a JVM that ran out of heap is not trustworthy (threads die, classes fail
# to load, GC thrashes while the process stays alive); exit so `restart: unless-stopped` restarts it.
ENV PORT=8080 WEB_DIR=/app/web JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["/app/bin/ritme-server"]
