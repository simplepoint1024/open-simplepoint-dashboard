# syntax=docker/dockerfile:1.7

ARG GO_IMAGE=golang:1.24-alpine@sha256:8bee1901f1e530bfb4a7850aa7a479d17ae3a18beb6e09064ed54cfd245b7191
ARG COSIGN_IMAGE=gcr.io/projectsigstore/cosign:v3.0.6@sha256:de9c65609e6bde17e6b48de485ee788407c9502fa08b8f4459f595b21f56cd00
ARG TRIVY_IMAGE=aquasec/trivy:0.70.0@sha256:be1190afcb28352bfddc4ddeb71470835d16462af68d310f9f4bca710961a41e
ARG RUNTIME_IMAGE=alpine:3.23@sha256:fd791d74b68913cbb027c6546007b3f0d3bc45125f797758156952bc2d6daf40

FROM ${COSIGN_IMAGE} AS cosign
FROM ${TRIVY_IMAGE} AS trivy

FROM ${GO_IMAGE} AS build

ARG OCI_IMAGE_VERSION=dev
WORKDIR /workspace

COPY --link simplepoint-services/simplepoint-service-tool-image-verifier/go.mod ./
COPY --link simplepoint-services/simplepoint-service-tool-image-verifier/ ./

RUN go test ./... \
    && CGO_ENABLED=0 go build \
      -trimpath \
      -ldflags="-s -w -X main.version=${OCI_IMAGE_VERSION}" \
      -o /out/tool-image-verifier \
      ./cmd/tool-image-verifier

FROM ${RUNTIME_IMAGE}

ARG COSIGN_IMAGE
ARG TRIVY_IMAGE
ARG RUNTIME_IMAGE
ARG OCI_IMAGE_VERSION=dev
ARG OCI_IMAGE_REVISION=unknown
ARG OCI_IMAGE_SOURCE=https://github.com/simplepoint1024/open-simplepoint-dashboard

RUN apk add --no-cache ca-certificates \
    && install -d -m 0750 -o 65532 -g 65532 /var/cache/trivy

COPY --link --from=build /out/tool-image-verifier /usr/local/bin/tool-image-verifier
COPY --link --from=cosign /ko-app/cosign /usr/local/bin/cosign
COPY --link --from=trivy /usr/local/bin/trivy /usr/local/bin/trivy

EXPOSE 2893
USER 65532:65532
ENTRYPOINT ["/usr/local/bin/tool-image-verifier"]

LABEL org.opencontainers.image.title="open-simplepoint-tool-image-verifier" \
      org.opencontainers.image.description="Independent Cosign, SBOM, and vulnerability admission service for SimplePoint OCI workloads" \
      org.opencontainers.image.vendor="SimplePoint" \
      org.opencontainers.image.version="${OCI_IMAGE_VERSION}" \
      org.opencontainers.image.revision="${OCI_IMAGE_REVISION}" \
      org.opencontainers.image.source="${OCI_IMAGE_SOURCE}" \
      org.opencontainers.image.url="${OCI_IMAGE_SOURCE}" \
      org.opencontainers.image.documentation="${OCI_IMAGE_SOURCE}/blob/main/simplepoint-services/simplepoint-service-tool-image-verifier/README.md" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.base.name="${RUNTIME_IMAGE}" \
      io.simplepoint.supply-chain.cosign.image="${COSIGN_IMAGE}" \
      io.simplepoint.supply-chain.trivy.image="${TRIVY_IMAGE}"
