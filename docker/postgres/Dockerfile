# syntax=docker/dockerfile:1.7

ARG POSTGRES_IMAGE=postgres:16-alpine

FROM ${POSTGRES_IMAGE} AS pgvector-builder

ARG PGVECTOR_VERSION=0.8.1

RUN apk add --no-cache build-base ca-certificates curl
RUN curl -fsSL "https://github.com/pgvector/pgvector/archive/refs/tags/v${PGVECTOR_VERSION}.tar.gz" \
      | tar -xz -C /tmp \
    && make -C "/tmp/pgvector-${PGVECTOR_VERSION}" OPTFLAGS="" with_llvm=no \
    && make -C "/tmp/pgvector-${PGVECTOR_VERSION}" with_llvm=no \
      DESTDIR=/tmp/pgvector-install install

FROM ${POSTGRES_IMAGE}

ARG POSTGRES_IMAGE
ARG PGVECTOR_VERSION=0.8.1
ARG OCI_IMAGE_VERSION=dev
ARG OCI_IMAGE_REVISION=unknown
ARG OCI_IMAGE_SOURCE=https://github.com/simplepoint1024/open-simplepoint-dashboard

COPY --from=pgvector-builder \
  /tmp/pgvector-install/usr/local/lib/postgresql/vector.so \
  /usr/local/lib/postgresql/vector.so
COPY --from=pgvector-builder \
  /tmp/pgvector-install/usr/local/share/postgresql/extension/ \
  /usr/local/share/postgresql/extension/

LABEL org.opencontainers.image.title="open-simplepoint-postgres" \
      org.opencontainers.image.description="PostgreSQL 16 with the pgvector extension for SimplePoint" \
      org.opencontainers.image.version="${OCI_IMAGE_VERSION}" \
      org.opencontainers.image.revision="${OCI_IMAGE_REVISION}" \
      org.opencontainers.image.source="${OCI_IMAGE_SOURCE}" \
      org.opencontainers.image.url="${OCI_IMAGE_SOURCE}" \
      org.opencontainers.image.documentation="${OCI_IMAGE_SOURCE}/blob/main/README.md" \
      org.opencontainers.image.vendor="SimplePoint" \
      org.opencontainers.image.licenses="PostgreSQL" \
      org.opencontainers.image.base.name="${POSTGRES_IMAGE}" \
      io.simplepoint.pgvector.version="${PGVECTOR_VERSION}"
