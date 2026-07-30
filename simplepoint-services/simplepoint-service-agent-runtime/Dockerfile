# syntax=docker/dockerfile:1.7

ARG GRADLE_IMAGE=gradle:8.12-jdk21
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre-jammy

FROM ${GRADLE_IMAGE} AS build
USER gradle
WORKDIR /workspace
COPY --chown=1000:1000 . .

RUN --mount=type=cache,id=simplepoint-gradle-cache,target=/home/gradle/.gradle,uid=1000,gid=1000,sharing=locked \
    gradle \
      --no-daemon \
      --max-workers=4 \
      -Dorg.gradle.jvmargs="-Xmx2g -XX:MaxMetaspaceSize=768m -XX:ReservedCodeCacheSize=512m" \
      :simplepoint-services:simplepoint-service-agent-runtime:installDist

FROM ${RUNTIME_IMAGE} AS runtime
ARG RUNTIME_IMAGE
ARG OCI_IMAGE_VERSION=dev
ARG OCI_IMAGE_REVISION=unknown
ARG OCI_IMAGE_SOURCE=https://github.com/simplepoint1024/open-simplepoint-dashboard

RUN command -v bash >/dev/null \
    && command -v curl >/dev/null \
    && test -r /etc/ssl/certs/ca-certificates.crt \
    && groupadd --system --gid 10001 simplepoint \
    && useradd --system --uid 10001 --gid simplepoint --home-dir /opt/simplepoint --shell /usr/sbin/nologin simplepoint \
    && install -d -o simplepoint -g simplepoint /opt/simplepoint

WORKDIR /opt/simplepoint
COPY --link docker/runtime/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod 0555 /usr/local/bin/docker-entrypoint.sh
COPY --link --from=build --chown=10001:10001 \
  /workspace/simplepoint-services/simplepoint-service-agent-runtime/build/install/simplepoint-service-agent-runtime/ \
  /opt/simplepoint/

LABEL org.opencontainers.image.title="open-simplepoint-agent-runtime" \
      org.opencontainers.image.description="SimplePoint independent Agent execution runtime" \
      org.opencontainers.image.version="${OCI_IMAGE_VERSION}" \
      org.opencontainers.image.revision="${OCI_IMAGE_REVISION}" \
      org.opencontainers.image.source="${OCI_IMAGE_SOURCE}" \
      org.opencontainers.image.url="${OCI_IMAGE_SOURCE}" \
      org.opencontainers.image.documentation="${OCI_IMAGE_SOURCE}/blob/main/simplepoint-services/simplepoint-service-agent-runtime/README.md" \
      org.opencontainers.image.vendor="SimplePoint" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.base.name="${RUNTIME_IMAGE}"

ENV SIMPLEPOINT_START_SCRIPT="/opt/simplepoint/bin/simplepoint-service-agent-runtime" \
    SIMPLEPOINT_WAIT_FOR="postgres:5432,consul:8500,ai:2888" \
    SIMPLEPOINT_WAIT_FOR_URLS="http://consul:8500/v1/kv/simplepoint/bootstrap/status?raw" \
    SIMPLEPOINT_WAIT_TIMEOUT="180" \
    PATH="/opt/simplepoint/bin:${PATH}"

EXPOSE 2894
USER 10001:10001
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=20 \
  CMD curl -fsS http://127.0.0.1:2894/actuator/health >/dev/null || exit 1
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
