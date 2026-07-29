# syntax=docker/dockerfile:1.7

ARG RUNTIME_IMAGE=eclipse-temurin:21-jre-alpine
FROM ${RUNTIME_IMAGE}

ARG RUNTIME_IMAGE
ARG OCI_IMAGE_VERSION=dev
ARG OCI_IMAGE_REVISION=unknown
ARG OCI_IMAGE_SOURCE=https://github.com/simplepoint1024/open-simplepoint-dashboard

RUN apk add --no-cache openssl
COPY --link docker/runtime-pki/generate.sh /usr/local/bin/generate-runtime-pki
RUN chmod 0555 /usr/local/bin/generate-runtime-pki

ENTRYPOINT ["/usr/local/bin/generate-runtime-pki"]

LABEL org.opencontainers.image.title="open-simplepoint-runtime-pki" \
      org.opencontainers.image.description="Development-only ephemeral Runtime mutual TLS identity generator" \
      org.opencontainers.image.version="${OCI_IMAGE_VERSION}" \
      org.opencontainers.image.revision="${OCI_IMAGE_REVISION}" \
      org.opencontainers.image.source="${OCI_IMAGE_SOURCE}" \
      org.opencontainers.image.url="${OCI_IMAGE_SOURCE}" \
      org.opencontainers.image.vendor="SimplePoint" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.base.name="${RUNTIME_IMAGE}"
