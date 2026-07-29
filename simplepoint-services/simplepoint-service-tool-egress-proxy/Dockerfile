# syntax=docker/dockerfile:1.7

ARG GO_IMAGE=golang:1.24-alpine@sha256:8bee1901f1e530bfb4a7850aa7a479d17ae3a18beb6e09064ed54cfd245b7191
ARG RUNTIME_IMAGE=gcr.io/distroless/static-debian12:nonroot@sha256:f5b485ea962d9bd1186b2f6b3a061191539b905b82ec395de78cbfae51f20e35

FROM ${GO_IMAGE} AS build

ARG OCI_IMAGE_VERSION=dev
WORKDIR /workspace

COPY --link simplepoint-services/simplepoint-service-tool-egress-proxy/go.mod ./
COPY --link simplepoint-services/simplepoint-service-tool-egress-proxy/ ./

RUN go test ./... \
    && CGO_ENABLED=0 go build \
      -trimpath \
      -ldflags="-s -w -X main.version=${OCI_IMAGE_VERSION}" \
      -o /out/tool-egress-proxy \
      ./cmd/tool-egress-proxy

FROM ${RUNTIME_IMAGE}

ARG RUNTIME_IMAGE
ARG OCI_IMAGE_VERSION=dev
ARG OCI_IMAGE_REVISION=unknown
ARG OCI_IMAGE_SOURCE=https://github.com/simplepoint1024/open-simplepoint-dashboard

LABEL org.opencontainers.image.title="open-simplepoint-tool-egress-proxy" \
      org.opencontainers.image.description="Stateless policy proxy for isolated SimplePoint OCI workloads" \
      org.opencontainers.image.vendor="SimplePoint" \
      org.opencontainers.image.version="${OCI_IMAGE_VERSION}" \
      org.opencontainers.image.revision="${OCI_IMAGE_REVISION}" \
      org.opencontainers.image.source="${OCI_IMAGE_SOURCE}" \
      org.opencontainers.image.url="${OCI_IMAGE_SOURCE}" \
      org.opencontainers.image.documentation="${OCI_IMAGE_SOURCE}/blob/main/simplepoint-services/simplepoint-service-tool-egress-proxy/README.md" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.base.name="${RUNTIME_IMAGE}"

COPY --link --from=build /out/tool-egress-proxy /tool-egress-proxy

EXPOSE 2892
USER 65532:65532
ENTRYPOINT ["/tool-egress-proxy"]
