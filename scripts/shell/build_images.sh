#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUTPUT_MODE="load"
OCI_OUTPUT_DIR=""
SIGN_AFTER_PUSH=false
ALL_TARGETS=(postgres bootstrap authorization common auditing dna ai mcp-gateway agent-runtime workflow-runtime runtime-pki tool-egress-proxy tool-image-verifier tool-runtime host)
TARGETS=()

usage() {
  cat <<'EOF'
Usage:
  scripts/shell/build_images.sh [--load] [target ...]
  scripts/shell/build_images.sh --push [--sign] [target ...]
  scripts/shell/build_images.sh --oci <output-dir> [target ...]

Modes:
  --load          Build in parallel and load images into the local Docker engine.
  --push          Push OCI images with provenance and SBOM attestations.
  --sign          After --push, publish Cosign signatures and signed SPDX attestations.
  --oci DIR       Export OCI image-layout archives with provenance and SBOM.

Environment:
  SIMPLEPOINT_IMAGE_TAG         Image tag. Defaults to "local".
  SIMPLEPOINT_IMAGE_REGISTRY    Registry/repository prefix. Defaults to "somesimpled".
  SIMPLEPOINT_IMAGE_VERSION     OCI image version label.
  SIMPLEPOINT_IMAGE_REVISION    OCI source revision label.
  SIMPLEPOINT_IMAGE_SOURCE      OCI source repository URL.
EOF
}

is_target() {
  local requested="$1"
  local candidate
  for candidate in "${ALL_TARGETS[@]}"; do
    [[ "${requested}" == "${candidate}" ]] && return 0
  done
  return 1
}

while (($# > 0)); do
  case "$1" in
    --load)
      OUTPUT_MODE="load"
      shift
      ;;
    --push)
      OUTPUT_MODE="push"
      shift
      ;;
    --sign)
      SIGN_AFTER_PUSH=true
      shift
      ;;
    --oci)
      [[ $# -ge 2 ]] || {
        echo "--oci requires an output directory" >&2
        exit 2
      }
      OUTPUT_MODE="oci"
      OCI_OUTPUT_DIR="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    -*)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
    *)
      is_target "$1" || {
        echo "Unknown image target: $1" >&2
        exit 2
      }
      TARGETS+=("$1")
      shift
      ;;
  esac
done

if ((${#TARGETS[@]} == 0)); then
  TARGETS=("${ALL_TARGETS[@]}")
fi

if [[ "${SIGN_AFTER_PUSH}" == "true" && "${OUTPUT_MODE}" != "push" ]]; then
  echo "--sign can only be used together with --push" >&2
  exit 2
fi

command -v docker >/dev/null 2>&1 || {
  echo "docker is required" >&2
  exit 1
}
docker buildx version >/dev/null

IMAGE_TAG="${SIMPLEPOINT_IMAGE_TAG:-local}"
IMAGE_REGISTRY="${SIMPLEPOINT_IMAGE_REGISTRY:-somesimpled}"
IMAGE_REGISTRY="${IMAGE_REGISTRY%/}"

image_name() {
  local service="$1"
  printf '%s/open-simplepoint-%s:%s\n' "${IMAGE_REGISTRY}" "${service}" "${IMAGE_TAG}"
}

export OCI_IMAGE_VERSION="${SIMPLEPOINT_IMAGE_VERSION:-${IMAGE_TAG}}"
export OCI_IMAGE_REVISION="${SIMPLEPOINT_IMAGE_REVISION:-$(git -C "${ROOT_DIR}" rev-parse HEAD 2>/dev/null || printf 'unknown')}"
export OCI_IMAGE_SOURCE="${SIMPLEPOINT_IMAGE_SOURCE:-https://github.com/simplepoint1024/open-simplepoint-dashboard}"
export SIMPLEPOINT_POSTGRES_IMAGE="${SIMPLEPOINT_POSTGRES_IMAGE:-$(image_name postgres)}"
export SIMPLEPOINT_BOOTSTRAP_IMAGE="${SIMPLEPOINT_BOOTSTRAP_IMAGE:-$(image_name bootstrap)}"
export SIMPLEPOINT_AUTH_IMAGE="${SIMPLEPOINT_AUTH_IMAGE:-$(image_name authorization)}"
export SIMPLEPOINT_COMMON_IMAGE="${SIMPLEPOINT_COMMON_IMAGE:-$(image_name common)}"
export SIMPLEPOINT_AUDITING_IMAGE="${SIMPLEPOINT_AUDITING_IMAGE:-$(image_name auditing)}"
export SIMPLEPOINT_DNA_IMAGE="${SIMPLEPOINT_DNA_IMAGE:-$(image_name dna)}"
export SIMPLEPOINT_AI_IMAGE="${SIMPLEPOINT_AI_IMAGE:-$(image_name ai)}"
export SIMPLEPOINT_MCP_GATEWAY_IMAGE="${SIMPLEPOINT_MCP_GATEWAY_IMAGE:-$(image_name mcp-gateway)}"
export SIMPLEPOINT_AGENT_RUNTIME_IMAGE="${SIMPLEPOINT_AGENT_RUNTIME_IMAGE:-$(image_name agent-runtime)}"
export SIMPLEPOINT_WORKFLOW_RUNTIME_IMAGE="${SIMPLEPOINT_WORKFLOW_RUNTIME_IMAGE:-$(image_name workflow-runtime)}"
export SIMPLEPOINT_RUNTIME_PKI_IMAGE="${SIMPLEPOINT_RUNTIME_PKI_IMAGE:-$(image_name runtime-pki)}"
export SIMPLEPOINT_TOOL_EGRESS_PROXY_IMAGE="${SIMPLEPOINT_TOOL_EGRESS_PROXY_IMAGE:-$(image_name tool-egress-proxy)}"
export SIMPLEPOINT_TOOL_IMAGE_VERIFIER_IMAGE="${SIMPLEPOINT_TOOL_IMAGE_VERIFIER_IMAGE:-$(image_name tool-image-verifier)}"
export SIMPLEPOINT_TOOL_RUNTIME_IMAGE="${SIMPLEPOINT_TOOL_RUNTIME_IMAGE:-$(image_name tool-runtime)}"
export SIMPLEPOINT_HOST_IMAGE="${SIMPLEPOINT_HOST_IMAGE:-$(image_name host)}"

cd "${ROOT_DIR}"

case "${OUTPUT_MODE}" in
  load)
    docker buildx bake --file docker-bake.hcl --load "${TARGETS[@]}"
    ;;
  push)
    output_options=()
    for target in "${TARGETS[@]}"; do
      output_options+=(--set "${target}.output=type=image,push=true,oci-mediatypes=true")
    done
    docker buildx bake \
      --file docker-bake.hcl \
      --provenance=mode=max \
      --sbom=true \
      "${output_options[@]}" \
      "${TARGETS[@]}"
    if [[ "${SIGN_AFTER_PUSH}" == "true" ]]; then
      "${ROOT_DIR}/scripts/shell/sign_images.sh" "${TARGETS[@]}"
    fi
    ;;
  oci)
    mkdir -p "${OCI_OUTPUT_DIR}"
    OCI_OUTPUT_DIR="$(cd "${OCI_OUTPUT_DIR}" && pwd)"
    output_options=()
    for target in "${TARGETS[@]}"; do
      output_options+=(--set "${target}.output=type=oci,dest=${OCI_OUTPUT_DIR}/open-simplepoint-${target}-${IMAGE_TAG}.tar")
    done
    docker buildx bake \
      --file docker-bake.hcl \
      --allow "fs.write=${OCI_OUTPUT_DIR}" \
      --provenance=mode=max \
      --sbom=true \
      "${output_options[@]}" \
      "${TARGETS[@]}"
    ;;
esac
