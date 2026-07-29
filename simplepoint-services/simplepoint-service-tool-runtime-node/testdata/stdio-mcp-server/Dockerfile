# syntax=docker/dockerfile:1.7

FROM golang:1.24-alpine AS build
WORKDIR /src
COPY main.go .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /stdio-mcp-server main.go

FROM scratch
COPY --from=build /stdio-mcp-server /stdio-mcp-server
USER 65532:65532
ENTRYPOINT ["/stdio-mcp-server"]

LABEL org.opencontainers.image.title="open-simplepoint-stdio-mcp-smoke" \
      org.opencontainers.image.description="Protocol smoke fixture for the managed OCI stdio bridge" \
      org.opencontainers.image.vendor="SimplePoint" \
      org.opencontainers.image.licenses="Apache-2.0" \
      io.simplepoint.mcp.transport="stdio" \
      io.simplepoint.mcp.protocol-version="2025-11-25"
