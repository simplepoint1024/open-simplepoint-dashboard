# syntax=docker/dockerfile:1.7

FROM node:22.22.0-alpine AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY tsconfig.json ./
COPY src ./src
RUN npm run build \
    && npm prune --omit=dev

FROM node:22.22.0-alpine
ARG OCI_SOURCE="https://github.com/example/__EXTENSION_NAME__"
ARG OCI_REVISION="unknown"
ARG OCI_VERSION="1.0.0"
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build --chown=65532:65532 /workspace/dist ./dist
COPY --from=build --chown=65532:65532 /workspace/node_modules ./node_modules
COPY --from=build --chown=65532:65532 /workspace/package.json ./
USER 65532:65532
ENTRYPOINT ["node", "/app/dist/src/index.js"]

LABEL org.opencontainers.image.title="__EXTENSION_NAME__" \
      org.opencontainers.image.description="Independent MCP Tool server for Open SimplePoint" \
      org.opencontainers.image.source="${OCI_SOURCE}" \
      org.opencontainers.image.revision="${OCI_REVISION}" \
      org.opencontainers.image.version="${OCI_VERSION}" \
      org.opencontainers.image.licenses="Apache-2.0" \
      io.simplepoint.mcp.transport="stdio" \
      io.simplepoint.mcp.protocol-version="2025-11-25"
