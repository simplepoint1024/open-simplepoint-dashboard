#!/usr/bin/env bash
set -euo pipefail

wait_for_tcp() {
  local host="$1"
  local port="$2"
  local timeout="${3:-180}"
  local start_ts
  start_ts="$(date +%s)"
  while true; do
    if bash -lc "</dev/tcp/${host}/${port}" >/dev/null 2>&1; then
      return 0
    fi
    if (( "$(date +%s)" - start_ts >= timeout )); then
      echo "Timed out waiting for ${host}:${port}" >&2
      return 1
    fi
    sleep 2
  done
}

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

IFS=',' read -r -a tcp_targets <<< "${SIMPLEPOINT_WAIT_FOR:-}"
for target in "${tcp_targets[@]}"; do
  [[ -z "${target}" ]] && continue
  host="${target%%:*}"
  port="${target##*:}"
  echo "Waiting for ${host}:${port}..."
  wait_for_tcp "${host}" "${port}" "${SIMPLEPOINT_WAIT_TIMEOUT:-180}"
done

IFS=',' read -r -a http_targets <<< "${SIMPLEPOINT_WAIT_FOR_URLS:-}"
for url in "${http_targets[@]}"; do
  [[ -z "${url}" ]] && continue
  echo "Waiting for ${url}..."
  wait_for_http "${url}" "${SIMPLEPOINT_WAIT_TIMEOUT:-180}"
done

exec "${SIMPLEPOINT_START_SCRIPT}"

