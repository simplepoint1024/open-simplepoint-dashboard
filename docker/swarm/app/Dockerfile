ARG GRADLE_IMAGE=gradle:8.12-jdk17
ARG RUNTIME_IMAGE=eclipse-temurin:17-jre-jammy

FROM ${GRADLE_IMAGE} AS build
WORKDIR /workspace
COPY . .

ARG APP_GRADLE_PATH
ARG APP_INSTALL_DIR
RUN test -n "${APP_GRADLE_PATH}" && test -n "${APP_INSTALL_DIR}"
RUN ./gradlew --no-daemon "${APP_GRADLE_PATH}:installDist"

FROM ${RUNTIME_IMAGE} AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends bash curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/simplepoint

ARG APP_INSTALL_DIR
ARG APP_START_SCRIPT
ENV SIMPLEPOINT_START_SCRIPT="${APP_START_SCRIPT}"
ENV SIMPLEPOINT_WAIT_FOR="postgres:5432,redis:6379,rabbitmq:5672,consul:8500,vault:8200"
ENV SIMPLEPOINT_WAIT_FOR_URLS="http://consul:8500/v1/kv/simplepoint/bootstrap/status?raw"
ENV SIMPLEPOINT_WAIT_TIMEOUT=180

COPY docker/swarm/app/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh
COPY --from=build /workspace/${APP_INSTALL_DIR}/ /opt/simplepoint/

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
