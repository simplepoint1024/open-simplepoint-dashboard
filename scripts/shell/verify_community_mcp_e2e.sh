#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPOSITORY_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"

command -v node >/dev/null 2>&1 || {
  printf 'Community MCP E2E requires Node.js 20 or newer.\n' >&2
  exit 1
}

exec node "${REPOSITORY_ROOT}/scripts/node/verify-community-mcp-e2e.mjs"
