#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STACK_NAME="${STACK_NAME:-open-simplepoint}"
PUBLIC_HOST="${SIMPLEPOINT_PUBLIC_HOST:-}"

validate_runtime_prerequisites() {
  local pki_dir="${SIMPLEPOINT_RUNTIME_PKI_HOST_DIR:-/etc/simplepoint/runtime-pki}"
  local egress_signing_key="${SIMPLEPOINT_TOOL_EGRESS_SIGNING_KEY:-}"
  local required_file
  local security_options

  if [[ -z "${SIMPLEPOINT_RUNTIME_PKI_STORE_PASSWORD:-}" ]]; then
    echo "SIMPLEPOINT_RUNTIME_PKI_STORE_PASSWORD is required for Runtime mTLS." >&2
    exit 1
  fi
  if [[ -z "${SIMPLEPOINT_TOOL_RUNTIME_DOCKER_HOST:-}" ]]; then
    echo "SIMPLEPOINT_TOOL_RUNTIME_DOCKER_HOST must point to a restricted per-node Docker Socket Proxy." >&2
    exit 1
  fi
  if [[ ${#egress_signing_key} -lt 32 ]]; then
    echo "SIMPLEPOINT_TOOL_EGRESS_SIGNING_KEY must contain at least 32 characters." >&2
    exit 1
  fi
  security_options="$(docker info --format '{{json .SecurityOptions}}' 2>/dev/null || true)"
  if [[ "${security_options}" != *"name=seccomp"* ]]; then
    echo "Docker seccomp support is required on every Runtime worker node." >&2
    exit 1
  fi
  if [[ "${SIMPLEPOINT_TOOL_RUNTIME_APPARMOR_REQUIRED:-true}" == "true" ]] \
      && [[ "${security_options}" != *"name=apparmor"* ]]; then
    echo "Docker AppArmor support and the open-simplepoint-mcp-workload profile are required." >&2
    exit 1
  fi
  for required_file in \
    ai/identity.p12 \
    ai/trust.p12 \
    gateway/identity.p12 \
    gateway/trust.p12 \
    node/tls.crt \
    node/tls.key \
    node/ca.crt \
    verifier/tls.crt \
    verifier/tls.key \
    verifier/ca.crt; do
    if [[ ! -r "${pki_dir}/${required_file}" ]]; then
      echo "Missing readable Runtime mTLS file: ${pki_dir}/${required_file}" >&2
      exit 1
    fi
  done
}

detect_host_ip() {
  if command -v docker >/dev/null 2>&1; then
    local swarm_addr
    swarm_addr="$(docker info --format '{{.Swarm.NodeAddr}}' 2>/dev/null || true)"
    if [[ -n "${swarm_addr}" && "${swarm_addr}" != "<no value>" ]]; then
      printf '%s\n' "${swarm_addr}"
      return 0
    fi
  fi

  if command -v ip >/dev/null 2>&1; then
    local route_ip
    route_ip="$(
      ip route get 1.1.1.1 2>/dev/null \
        | awk '{ for (i = 1; i <= NF; i++) if ($i == "src") { print $(i + 1); exit } }'
    )"
    if [[ -n "${route_ip}" ]]; then
      printf '%s\n' "${route_ip}"
      return 0
    fi
  fi

  if command -v hostname >/dev/null 2>&1; then
    local host_ip
    host_ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
    if [[ -n "${host_ip}" ]]; then
      printf '%s\n' "${host_ip}"
      return 0
    fi
  fi

  printf '127.0.0.1\n'
}

ensure_swarm() {
  local state
  state="$(docker info --format '{{.Swarm.LocalNodeState}}' 2>/dev/null || true)"
  if [[ "${state}" == "active" ]]; then
    if [[ "$(docker info --format '{{.Swarm.ControlAvailable}}' 2>/dev/null || true)" != "true" ]]; then
      echo "Current Docker node is not a Swarm manager. Please run this script on a manager node." >&2
      exit 1
    fi
    return 0
  fi

  local advertise_addr
  advertise_addr="$(detect_host_ip)"
  echo "Initializing Docker Swarm on ${advertise_addr}..."
  docker swarm init --advertise-addr "${advertise_addr}" >/dev/null
}

ensure_runtime_node_label() {
  local labeled_nodes
  labeled_nodes="$(
    docker node ls -q \
      | xargs -r docker node inspect \
          --format '{{if eq (index .Spec.Labels "simplepoint.runtime") "true"}}{{.ID}}{{end}}' \
      | sed '/^$/d'
  )"
  if [[ -n "${labeled_nodes}" ]]; then
    return 0
  fi
  if [[ "${SIMPLEPOINT_SWARM_AUTO_LABEL_LOCAL_RUNTIME:-true}" != "true" ]]; then
    echo "Label at least one trusted node with simplepoint.runtime=true." >&2
    exit 1
  fi
  local local_node_id
  local_node_id="$(docker info --format '{{.Swarm.NodeID}}')"
  docker node update \
    --label-add simplepoint.runtime=true \
    "${local_node_id}" >/dev/null
  echo "Labeled the current node as the local SimplePoint Runtime node."
}

main() {
  command -v docker >/dev/null 2>&1 || {
    echo "docker is required" >&2
    exit 1
  }

  ensure_swarm
  validate_runtime_prerequisites
  ensure_runtime_node_label

  if [[ -z "${PUBLIC_HOST}" ]]; then
    PUBLIC_HOST="localhost"
  fi

  export SIMPLEPOINT_PUBLIC_HOST="${PUBLIC_HOST}"
  export SIMPLEPOINT_POSTGRES_IMAGE="${SIMPLEPOINT_POSTGRES_IMAGE:-somesimpled/open-simplepoint-postgres:swarm}"
  export SIMPLEPOINT_BOOTSTRAP_IMAGE="${SIMPLEPOINT_BOOTSTRAP_IMAGE:-somesimpled/open-simplepoint-bootstrap:swarm}"
  export SIMPLEPOINT_AUTH_IMAGE="${SIMPLEPOINT_AUTH_IMAGE:-somesimpled/open-simplepoint-authorization:swarm}"
  export SIMPLEPOINT_COMMON_IMAGE="${SIMPLEPOINT_COMMON_IMAGE:-somesimpled/open-simplepoint-common:swarm}"
  export SIMPLEPOINT_AUDITING_IMAGE="${SIMPLEPOINT_AUDITING_IMAGE:-somesimpled/open-simplepoint-auditing:swarm}"
  export SIMPLEPOINT_DNA_IMAGE="${SIMPLEPOINT_DNA_IMAGE:-somesimpled/open-simplepoint-dna:swarm}"
  export SIMPLEPOINT_AI_IMAGE="${SIMPLEPOINT_AI_IMAGE:-somesimpled/open-simplepoint-ai:swarm}"
  export SIMPLEPOINT_MCP_GATEWAY_IMAGE="${SIMPLEPOINT_MCP_GATEWAY_IMAGE:-somesimpled/open-simplepoint-mcp-gateway:swarm}"
  export SIMPLEPOINT_TOOL_EGRESS_PROXY_IMAGE="${SIMPLEPOINT_TOOL_EGRESS_PROXY_IMAGE:-somesimpled/open-simplepoint-tool-egress-proxy:swarm}"
  export SIMPLEPOINT_TOOL_IMAGE_VERIFIER_IMAGE="${SIMPLEPOINT_TOOL_IMAGE_VERIFIER_IMAGE:-somesimpled/open-simplepoint-tool-image-verifier:swarm}"
  export SIMPLEPOINT_TOOL_RUNTIME_IMAGE="${SIMPLEPOINT_TOOL_RUNTIME_IMAGE:-somesimpled/open-simplepoint-tool-runtime:swarm}"
  export SIMPLEPOINT_HOST_IMAGE="${SIMPLEPOINT_HOST_IMAGE:-somesimpled/open-simplepoint-host:swarm}"
  export SIMPLEPOINT_IMAGE_VERSION="${SIMPLEPOINT_IMAGE_VERSION:-swarm}"
  export SIMPLEPOINT_SERVICE_ROUTER_INTERNAL_AUTH_TOKEN="${SIMPLEPOINT_SERVICE_ROUTER_INTERNAL_AUTH_TOKEN:-dev-swarm-service-router}"

  local stack_exists=false
  if docker stack services "${STACK_NAME}" >/dev/null 2>&1; then
    stack_exists=true
  fi

  echo "Building platform images with BuildKit..."
  "${ROOT_DIR}/scripts/shell/build_images.sh" --load \
    postgres bootstrap authorization common auditing dna ai mcp-gateway \
    tool-egress-proxy tool-image-verifier tool-runtime host

  echo "Deploying stack ${STACK_NAME} with PUBLIC_HOST=${SIMPLEPOINT_PUBLIC_HOST}..."
  docker stack deploy -c "${ROOT_DIR}/docker/swarm/stack.yml" "${STACK_NAME}"

  if [[ "${stack_exists}" == "true" ]]; then
    echo "Forcing application services to pick up local image tag updates..."
    for service in authorization common auditing dna ai mcp-gateway tool-egress-proxy tool-image-verifier tool-runtime host; do
      docker service update --force "${STACK_NAME}_${service}" >/dev/null
    done
  fi

  cat <<EOF

SimplePoint Swarm deployment submitted.

Expected access URLs after services become healthy:
  Host UI:           http://${SIMPLEPOINT_PUBLIC_HOST}:8080
  Authorization:     http://${SIMPLEPOINT_PUBLIC_HOST}:9000
  Consul UI:         http://${SIMPLEPOINT_PUBLIC_HOST}:8500

Useful commands:
  docker stack services ${STACK_NAME}
  docker stack ps ${STACK_NAME}
  docker service logs ${STACK_NAME}_host -f
EOF
}

main "$@"
