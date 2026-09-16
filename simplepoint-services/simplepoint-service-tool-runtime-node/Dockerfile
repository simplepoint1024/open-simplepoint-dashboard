# syntax=docker/dockerfile:1.7

ARG GO_IMAGE=golang:1.24-alpine
ARG RUNTIME_IMAGE=gcr.io/distroless/static-debian12:nonroot

FROM ${GO_IMAGE} AS build

ARG OCI_IMAGE_VERSION=dev
ARG GOPROXY=https://goproxy.cn,direct
ENV GOPROXY=${GOPROXY}
WORKDIR /workspace

COPY --link simplepoint-services/simplepoint-service-tool-runtime-node/go.mod \
  simplepoint-services/simplepoint-service-tool-runtime-node/go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod \
    go mod download

COPY --link simplepoint-services/simplepoint-service-tool-runtime-node/ ./
RUN go test ./... \
    && CGO_ENABLED=0 go build \
      -trimpath \
      -ldflags="-s -w -X main.version=${OCI_IMAGE_VERSION}" \
      -o /out/tool-runtime-node \
      ./cmd/tool-runtime-node \
    && CGO_ENABLED=0 go build \
      -trimpath \
      -ldflags="-s -w" \
      -o /out/runtime-launcher \
      ./cmd/runtime-launcher

FROM ${RUNTIME_IMAGE}

ARG RUNTIME_IMAGE
ARG OCI_IMAGE_VERSION=dev
ARG OCI_IMAGE_REVISION=unknown
ARG OCI_IMAGE_SOURCE=https://github.com/simplepoint1024/open-simplepoint-dashboard

COPY --link --from=build /out/tool-runtime-node /tool-runtime-node
COPY --link --from=build /out/runtime-launcher /simplepoint-runtime-launcher
COPY --link --chown=65532:65532 docker/runtime-security/seccomp.json \
  /etc/simplepoint/runtime-security/seccomp.json

EXPOSE 2891
USER 65532:65532
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=10 \
  CMD ["/tool-runtime-node", "healthcheck"]
ENTRYPOINT ["/tool-runtime-node"]

LABEL org.opencontainers.image.title="open-simplepoint-tool-runtime" \
      org.opencontainers.image.description="SimplePoint independent OCI MCP Server runtime node" \
      org.opencontainers.image.version="${OCI_IMAGE_VERSION}" \
      org.opencontainers.image.revision="${OCI_IMAGE_REVISION}" \
      org.opencontainers.image.source="${OCI_IMAGE_SOURCE}" \
      org.opencontainers.image.url="${OCI_IMAGE_SOURCE}" \
      org.opencontainers.image.documentation="${OCI_IMAGE_SOURCE}/blob/main/simplepoint-services/simplepoint-service-tool-runtime-node/README.md" \
      org.opencontainers.image.vendor="SimplePoint" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.base.name="${RUNTIME_IMAGE}"
