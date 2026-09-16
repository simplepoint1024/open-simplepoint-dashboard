#!/usr/bin/env bash
set -euo pipefail

CONSUL_ADDR="${CONSUL_ADDR:-http://consul:8500}"
PUBLIC_HOST="${PUBLIC_HOST:-}"
CONFIG_ROOT="${CONFIG_ROOT:-/bootstrap/consul-config}"
CONFIG_PROFILE="${CONFIG_PROFILE:-compose}"
CONSUL_HTTP_TOKEN="${CONSUL_HTTP_TOKEN:-}"

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
  local -a curl_args=(-fsS --request PUT --data-binary @-)
  if [[ -n "${CONSUL_HTTP_TOKEN}" ]]; then
    curl_args+=(-H "X-Consul-Token: ${CONSUL_HTTP_TOKEN}")
  fi
  sed "s#__PUBLIC_HOST__#${PUBLIC_HOST}#g" "${file}" | curl "${curl_args[@]}" \
    "${CONSUL_ADDR}/v1/kv/${path}" >/dev/null
}

upload_tree() {
  local root="$1"
  [[ -d "${root}" ]] || return 0
  while IFS= read -r file; do
    local rel="${file#${root}/}"
    put_consul_key "${rel}" "${file}"
  done < <(find "${root}" -type f -name '*.properties' | LC_ALL=C sort)
}

main() {
  echo "Waiting for Consul..."
  wait_for_http "${CONSUL_ADDR}/v1/status/leader"

  echo "Uploading Consul KV configuration..."
  upload_tree "${CONFIG_ROOT}/base"
  upload_tree "${CONFIG_ROOT}/profiles/${CONFIG_PROFILE}"

  local marker
  marker="{\"status\":\"ready\",\"profile\":\"${CONFIG_PROFILE}\",\"updatedAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}"
  printf '%s' "${marker}" | put_consul_key \
    "simplepoint/bootstrap/status" /dev/stdin

  echo "SimplePoint bootstrap completed."
}

main "$@"
