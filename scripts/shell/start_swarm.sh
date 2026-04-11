#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STACK_NAME="${STACK_NAME:-simplepoint}"
PUBLIC_HOST="${SIMPLEPOINT_PUBLIC_HOST:-}"

detect_host_ip() {
  if command -v docker >/dev/null 2>&1; then
    local swarm_addr
    swarm_addr="$(docker info --format '{{.Swarm.NodeAddr}}' 2>/dev/null || true)"
    if [[ -n "${swarm_addr}" && "${swarm_addr}" != "<no value>" ]]; then
      printf '%s\n' "${swarm_addr}"
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

build_image() {
  local image="$1"
  shift
  echo "Building ${image}..."
  docker build -t "${image}" "$@"
}

main() {
  command -v docker >/dev/null 2>&1 || {
    echo "docker is required" >&2
    exit 1
  }

  ensure_swarm

  if [[ -z "${PUBLIC_HOST}" ]]; then
    PUBLIC_HOST="$(detect_host_ip)"
  fi

  export SIMPLEPOINT_PUBLIC_HOST="${PUBLIC_HOST}"
  export SIMPLEPOINT_BOOTSTRAP_IMAGE="${SIMPLEPOINT_BOOTSTRAP_IMAGE:-simplepoint/bootstrap:swarm}"
  export SIMPLEPOINT_AUTH_IMAGE="${SIMPLEPOINT_AUTH_IMAGE:-simplepoint/authorization:swarm}"
  export SIMPLEPOINT_COMMON_IMAGE="${SIMPLEPOINT_COMMON_IMAGE:-simplepoint/common:swarm}"
  export SIMPLEPOINT_HOST_IMAGE="${SIMPLEPOINT_HOST_IMAGE:-simplepoint/host:swarm}"

  build_image "${SIMPLEPOINT_BOOTSTRAP_IMAGE}" -f "${ROOT_DIR}/docker/swarm/bootstrap/Dockerfile" "${ROOT_DIR}"
  build_image "${SIMPLEPOINT_AUTH_IMAGE}" \
    --build-arg APP_GRADLE_PATH=":simplepoint-services:simplepoint-service-authorization" \
    --build-arg APP_INSTALL_DIR="simplepoint-services/simplepoint-service-authorization/build/install/simplepoint-service-authorization" \
    --build-arg APP_START_SCRIPT="/opt/simplepoint/bin/simplepoint-service-authorization" \
    -f "${ROOT_DIR}/docker/swarm/app/Dockerfile" "${ROOT_DIR}"
  build_image "${SIMPLEPOINT_COMMON_IMAGE}" \
    --build-arg APP_GRADLE_PATH=":simplepoint-services:simplepoint-service-common" \
    --build-arg APP_INSTALL_DIR="simplepoint-services/simplepoint-service-common/build/install/simplepoint-service-common" \
    --build-arg APP_START_SCRIPT="/opt/simplepoint/bin/simplepoint-service-common" \
    -f "${ROOT_DIR}/docker/swarm/app/Dockerfile" "${ROOT_DIR}"
  build_image "${SIMPLEPOINT_HOST_IMAGE}" \
    --build-arg APP_GRADLE_PATH=":simplepoint-services:simplepoint-service-host" \
    --build-arg APP_INSTALL_DIR="simplepoint-services/simplepoint-service-host/build/install/simplepoint-service-host" \
    --build-arg APP_START_SCRIPT="/opt/simplepoint/bin/simplepoint-service-host" \
    -f "${ROOT_DIR}/docker/swarm/app/Dockerfile" "${ROOT_DIR}"

  echo "Deploying stack ${STACK_NAME} with PUBLIC_HOST=${SIMPLEPOINT_PUBLIC_HOST}..."
  docker stack deploy -c "${ROOT_DIR}/docker/swarm/stack.yml" "${STACK_NAME}"

  cat <<EOF

SimplePoint Swarm deployment submitted.

Expected access URLs after services become healthy:
  Host UI:           http://${SIMPLEPOINT_PUBLIC_HOST}:8080
  Authorization:     http://${SIMPLEPOINT_PUBLIC_HOST}:9000
  Consul UI:         http://${SIMPLEPOINT_PUBLIC_HOST}:8500
  Vault UI:          http://${SIMPLEPOINT_PUBLIC_HOST}:8200/ui
  RabbitMQ UI:       http://${SIMPLEPOINT_PUBLIC_HOST}:15672

Useful commands:
  docker stack services ${STACK_NAME}
  docker stack ps ${STACK_NAME}
  docker service logs ${STACK_NAME}_host -f
EOF
}

main "$@"
