#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERIFY_DOCKER="${AI_EXTENSION_VERIFY_DOCKER:-false}"
TEMP_DIR="$(mktemp -d /tmp/open-simplepoint-ai-extensions.XXXXXX)"
IMAGE_NAME="open-simplepoint/mcp-tool-template-verify:local"

cleanup() {
  if [[ "${VERIFY_DOCKER}" == "true" ]]; then
    docker image rm "${IMAGE_NAME}" >/dev/null 2>&1 || true
  fi
  rm -rf -- "${TEMP_DIR}"
}
trap cleanup EXIT

command -v npm >/dev/null 2>&1 || {
  echo "npm is required" >&2
  exit 1
}

"${ROOT_DIR}/scripts/shell/create_ai_extension.sh" \
  tool template-mcp-tool \
  --output "${TEMP_DIR}/tool"
if grep -R -n '__[A-Z_]*__' "${TEMP_DIR}"; then
  echo "Generated extension still contains template placeholders" >&2
  exit 1
fi

(
  cd "${TEMP_DIR}/tool"
  npm ci
  npm run check
)
if [[ "${VERIFY_DOCKER}" == "true" ]]; then
  docker build -t "${IMAGE_NAME}" "${TEMP_DIR}/tool"
  [[ "$(docker image inspect "${IMAGE_NAME}" \
    --format '{{index .Config.Labels "io.simplepoint.mcp.transport"}}')" \
      == "stdio" ]]
  [[ "$(docker image inspect "${IMAGE_NAME}" \
    --format '{{index .Config.Labels "io.simplepoint.mcp.protocol-version"}}')" \
      == "2025-11-25" ]]
fi

echo "AI extension templates verified successfully."
