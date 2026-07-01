ARG GRADLE_IMAGE=gradle:8.12-jdk21
ARG NODE_IMAGE=node:22-bookworm-slim
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre-jammy

FROM ${NODE_IMAGE} AS node

FROM ${GRADLE_IMAGE} AS build
USER root
COPY --from=node /usr/local/ /usr/local/
RUN npm install -g pnpm@9.0.0
USER gradle

WORKDIR /workspace
COPY --chown=gradle:gradle . .

ARG APP_GRADLE_PATH
ARG APP_INSTALL_DIR
ARG APP_USE_PREBUILT=true
RUN test -n "${APP_GRADLE_PATH}" && test -n "${APP_INSTALL_DIR}"
RUN if [ "${APP_USE_PREBUILT}" = "true" ] \
        && [ -d "${APP_INSTALL_DIR}/bin" ] \
        && find "${APP_INSTALL_DIR}/bin" -maxdepth 1 -type f -perm -111 | grep -q .; then \
        echo "Using prebuilt distribution at ${APP_INSTALL_DIR}"; \
    else \
        gradle --no-daemon "${APP_GRADLE_PATH}:installDist"; \
    fi

FROM ${RUNTIME_IMAGE} AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends bash curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/simplepoint

ARG APP_INSTALL_DIR
ARG APP_START_SCRIPT
ENV SIMPLEPOINT_START_SCRIPT="${APP_START_SCRIPT}"
ENV SIMPLEPOINT_WAIT_FOR="postgres:5432,redis:6379,consul:8500"
ENV SIMPLEPOINT_WAIT_FOR_URLS="http://consul:8500/v1/kv/simplepoint/bootstrap/status?raw"
ENV SIMPLEPOINT_WAIT_TIMEOUT=180

COPY docker/swarm/app/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh
COPY --from=build /workspace/${APP_INSTALL_DIR}/ /opt/simplepoint/

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
