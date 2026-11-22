# Build from the repository root:  docker build -f ritme-server/Dockerfile -t ritme-server .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew ./
COPY gradle ./gradle
COPY modules/ritme-protocol ./modules/ritme-protocol
COPY ritme-server ./ritme-server
RUN ./gradlew --no-daemon -p ritme-server installDist

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 ritme && mkdir -p /app/web
WORKDIR /app
COPY --from=build /src/ritme-server/build/install/ritme-server /app
USER ritme
ENV PORT=8080 WEB_DIR=/app/web
EXPOSE 8080
ENTRYPOINT ["/app/bin/ritme-server"]
