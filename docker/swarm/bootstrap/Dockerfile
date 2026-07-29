# syntax=docker/dockerfile:1.7

ARG RUNTIME_IMAGE=alpine:3.22

FROM ${RUNTIME_IMAGE}

ARG RUNTIME_IMAGE
ARG OCI_IMAGE_VERSION=dev
ARG OCI_IMAGE_REVISION=unknown
ARG OCI_IMAGE_SOURCE=https://github.com/simplepoint1024/open-simplepoint-dashboard

RUN apk add --no-cache bash curl ca-certificates \
    && addgroup -S -g 10001 simplepoint \
    && adduser -S -D -H -u 10001 -G simplepoint simplepoint

WORKDIR /bootstrap

COPY --link --chown=10001:10001 docker/swarm/bootstrap/init-swarm.sh /bootstrap/init-swarm.sh
COPY --link --chown=10001:10001 docker/swarm/bootstrap/consul-config /bootstrap/consul-config

RUN chmod 0555 /bootstrap/init-swarm.sh

LABEL org.opencontainers.image.title="open-simplepoint-bootstrap" \
      org.opencontainers.image.description="SimplePoint Consul configuration bootstrap" \
      org.opencontainers.image.version="${OCI_IMAGE_VERSION}" \
      org.opencontainers.image.revision="${OCI_IMAGE_REVISION}" \
      org.opencontainers.image.source="${OCI_IMAGE_SOURCE}" \
      org.opencontainers.image.url="${OCI_IMAGE_SOURCE}" \
      org.opencontainers.image.documentation="${OCI_IMAGE_SOURCE}/blob/main/doc/deployment/docker_swarm_deployment.md" \
      org.opencontainers.image.vendor="SimplePoint" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.base.name="${RUNTIME_IMAGE}"

USER 10001:10001
ENTRYPOINT ["/bootstrap/init-swarm.sh"]
