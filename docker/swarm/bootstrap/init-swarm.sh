#!/usr/bin/env bash
set -euo pipefail

CONSUL_ADDR="${CONSUL_ADDR:-http://consul:8500}"
VAULT_ADDR="${VAULT_ADDR:-http://vault:8200}"
VAULT_TOKEN="${VAULT_TOKEN:-root}"
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

ensure_vault_mount() {
  local status
  status="$(curl -s -o /tmp/vault-mount.out -w "%{http_code}" \
    -H "X-Vault-Token: ${VAULT_TOKEN}" \
    "${VAULT_ADDR}/v1/sys/mounts/transit" || true)"
  if [[ "${status}" == "200" ]]; then
    return 0
  fi

  status="$(curl -s -o /tmp/vault-mount-create.out -w "%{http_code}" \
    -H "X-Vault-Token: ${VAULT_TOKEN}" \
    -H "Content-Type: application/json" \
    --request POST \
    --data '{"type":"transit"}' \
    "${VAULT_ADDR}/v1/sys/mounts/transit" || true)"
  [[ "${status}" == "200" || "${status}" == "204" ]] || {
    echo "Failed to create Vault transit mount: HTTP ${status}" >&2
    cat /tmp/vault-mount-create.out >&2 || true
    exit 1
  }
}

ensure_vault_key() {
  local status
  status="$(curl -s -o /tmp/vault-key.out -w "%{http_code}" \
    -H "X-Vault-Token: ${VAULT_TOKEN}" \
    "${VAULT_ADDR}/v1/transit/keys/sas-jwt" || true)"
  if [[ "${status}" == "200" ]]; then
    return 0
  fi

  status="$(curl -s -o /tmp/vault-key-create.out -w "%{http_code}" \
    -H "X-Vault-Token: ${VAULT_TOKEN}" \
    -H "Content-Type: application/json" \
    --request POST \
    --data '{"type":"rsa-2048","exportable":true,"allow_plaintext_backup":true,"deletion_allowed":true}' \
    "${VAULT_ADDR}/v1/transit/keys/sas-jwt" || true)"
  [[ "${status}" == "200" || "${status}" == "204" ]] || {
    echo "Failed to create Vault transit key: HTTP ${status}" >&2
    cat /tmp/vault-key-create.out >&2 || true
    exit 1
  }
}

main() {
  echo "Waiting for Consul and Vault..."
  wait_for_http "${CONSUL_ADDR}/v1/status/leader"
  wait_for_http "${VAULT_ADDR}/v1/sys/health"

  echo "Uploading Consul KV configuration..."
  while IFS= read -r file; do
    rel="${file#${CONFIG_ROOT}/}"
    put_consul_key "${rel}" "${file}"
  done < <(find "${CONFIG_ROOT}" -type f -name '*.properties' | LC_ALL=C sort)

  echo "Ensuring Vault transit backend and key..."
  ensure_vault_mount
  ensure_vault_key

  curl -fsS \
    --request PUT \
    --data-binary "ready" \
    "${CONSUL_ADDR}/v1/kv/simplepoint/bootstrap/status" >/dev/null

  echo "SimplePoint bootstrap completed."
}

main "$@"
