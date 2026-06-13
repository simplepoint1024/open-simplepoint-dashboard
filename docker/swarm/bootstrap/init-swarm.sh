#!/usr/bin/env bash
set -euo pipefail

CONSUL_ADDR="${CONSUL_ADDR:-http://consul:8500}"
PUBLIC_HOST="${PUBLIC_HOST:-}"
CONFIG_ROOT="${CONFIG_ROOT:-/bootstrap/consul-config}"

if [[ -z "${PUBLIC_HOST}" ]]; then
  echo "PUBLIC_HOST is required" >&2
  exit 1
fi

wait_for_http() {
  local url="$1"
  local timeout="${2:-180}"
  local start_ts
  start_ts="$(date +%s)"
  while true; do
    if curl -fsS "${url}" >/dev/null 2>&1; then
      return 0
    fi
    if (( "$(date +%s)" - start_ts >= timeout )); then
      echo "Timed out waiting for ${url}" >&2
      return 1
    fi
    sleep 2
  done
}

put_consul_key() {
  local path="$1"
  local file="$2"
  sed "s#__PUBLIC_HOST__#${PUBLIC_HOST}#g" "${file}" | curl -fsS \
    --request PUT \
    --data-binary @- \
    "${CONSUL_ADDR}/v1/kv/${path}" >/dev/null
}

main() {
  echo "Waiting for Consul..."
  wait_for_http "${CONSUL_ADDR}/v1/status/leader"

  echo "Uploading Consul KV configuration..."
  while IFS= read -r file; do
    rel="${file#${CONFIG_ROOT}/}"
    put_consul_key "${rel}" "${file}"
  done < <(find "${CONFIG_ROOT}" -type f -name '*.properties' | LC_ALL=C sort)

  curl -fsS \
    --request PUT \
    --data-binary "ready" \
    "${CONSUL_ADDR}/v1/kv/simplepoint/bootstrap/status" >/dev/null

  echo "SimplePoint bootstrap completed."
}

main "$@"
