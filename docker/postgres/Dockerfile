FROM postgres:16-alpine AS pgvector-builder

ARG PGVECTOR_VERSION=0.8.1

RUN apk add --no-cache build-base ca-certificates curl
RUN curl -fsSL "https://github.com/pgvector/pgvector/archive/refs/tags/v${PGVECTOR_VERSION}.tar.gz" \
      | tar -xz -C /tmp \
    && make -C "/tmp/pgvector-${PGVECTOR_VERSION}" OPTFLAGS="" with_llvm=no \
    && make -C "/tmp/pgvector-${PGVECTOR_VERSION}" with_llvm=no \
      DESTDIR=/tmp/pgvector-install install

FROM postgres:16-alpine

COPY --from=pgvector-builder \
  /tmp/pgvector-install/usr/local/lib/postgresql/vector.so \
  /usr/local/lib/postgresql/vector.so
COPY --from=pgvector-builder \
  /tmp/pgvector-install/usr/local/share/postgresql/extension/ \
  /usr/local/share/postgresql/extension/
