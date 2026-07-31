#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ALL_TARGETS=(postgres bootstrap authorization common auditing dna ai mcp-gateway agent-runtime workflow-runtime runtime-pki tool-egress-proxy tool-image-verifier tool-runtime host)
TARGETS=("$@")

if ((${#TARGETS[@]} == 0)); then
  TARGETS=("${ALL_TARGETS[@]}")
fi

command -v cosign >/dev/null 2>&1 || {
  echo "cosign is required to sign published OCI images." >&2
  exit 1
}
command -v trivy >/dev/null 2>&1 || {
  echo "trivy is required to generate signed SPDX attestations." >&2
  exit 1
}
command -v docker >/dev/null 2>&1 || {
  echo "docker buildx is required to resolve published OCI digests." >&2
  exit 1
}
docker buildx version >/dev/null

IMAGE_TAG="${SIMPLEPOINT_IMAGE_TAG:-local}"
IMAGE_REGISTRY="${SIMPLEPOINT_IMAGE_REGISTRY:-somesimpled}"
IMAGE_REGISTRY="${IMAGE_REGISTRY%/}"
work_dir="$(mktemp -d)"

cleanup() {
  rm -rf "${work_dir}"
}
trap cleanup EXIT

image_variable() {
  case "$1" in
    postgres) printf 'SIMPLEPOINT_POSTGRES_IMAGE\n' ;;
    bootstrap) printf 'SIMPLEPOINT_BOOTSTRAP_IMAGE\n' ;;
    authorization) printf 'SIMPLEPOINT_AUTH_IMAGE\n' ;;
    common) printf 'SIMPLEPOINT_COMMON_IMAGE\n' ;;
    auditing) printf 'SIMPLEPOINT_AUDITING_IMAGE\n' ;;
    dna) printf 'SIMPLEPOINT_DNA_IMAGE\n' ;;
    ai) printf 'SIMPLEPOINT_AI_IMAGE\n' ;;
    mcp-gateway) printf 'SIMPLEPOINT_MCP_GATEWAY_IMAGE\n' ;;
    agent-runtime) printf 'SIMPLEPOINT_AGENT_RUNTIME_IMAGE\n' ;;
    workflow-runtime) printf 'SIMPLEPOINT_WORKFLOW_RUNTIME_IMAGE\n' ;;
    runtime-pki) printf 'SIMPLEPOINT_RUNTIME_PKI_IMAGE\n' ;;
    tool-egress-proxy) printf 'SIMPLEPOINT_TOOL_EGRESS_PROXY_IMAGE\n' ;;
    tool-image-verifier) printf 'SIMPLEPOINT_TOOL_IMAGE_VERIFIER_IMAGE\n' ;;
    tool-runtime) printf 'SIMPLEPOINT_TOOL_RUNTIME_IMAGE\n' ;;
    host) printf 'SIMPLEPOINT_HOST_IMAGE\n' ;;
    *)
      echo "Unknown image target: $1" >&2
      return 1
      ;;
  esac
}

image_name() {
  local target="$1"
  local variable
  variable="$(image_variable "${target}")"
  if [[ -n "${!variable:-}" ]]; then
    printf '%s\n' "${!variable}"
  else
    printf '%s/open-simplepoint-%s:%s\n' \
      "${IMAGE_REGISTRY}" \
      "${target}" \
      "${IMAGE_TAG}"
  fi
}

cosign_key_arguments=()
if [[ -n "${COSIGN_KEY:-}" ]]; then
  cosign_key_arguments=(--key "${COSIGN_KEY}")
fi

for target in "${TARGETS[@]}"; do
  image="$(image_name "${target}")"
  digest="$(
    docker buildx imagetools inspect \
      "${image}" \
      --format '{{.Manifest.Digest}}'
  )"
  if [[ ! "${digest}" =~ ^sha256:[a-f0-9]{64}$ ]]; then
    echo "Registry returned an invalid digest for ${image}: ${digest}" >&2
    exit 1
  fi
  repository="${image}"
  if [[ "${image}" == */* ]]; then
    repository="${image%/*}/${image##*/}"
  fi
  repository="${repository%:*}"
  immutable_reference="${repository}@${digest}"
  sbom_file="${work_dir}/${target}.spdx.json"

  echo "Signing ${immutable_reference}"
  cosign sign --yes "${cosign_key_arguments[@]}" "${immutable_reference}"
  trivy image \
    --quiet \
    --no-progress \
    --format spdx-json \
    --output "${sbom_file}" \
    "${immutable_reference}"
  cosign attest \
    --yes \
    --type spdx \
    --predicate "${sbom_file}" \
    "${cosign_key_arguments[@]}" \
    "${immutable_reference}"
done

echo "Signed OCI images and SPDX attestations published successfully."
