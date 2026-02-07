#!/usr/bin/env bash
set -euo pipefail

ENV="${1:-dev}"
INFRA_DIR="infrastructure"

CONSUL_ADDR="http://127.0.0.1:8500"
VAULT_ADDR="http://127.0.0.1:8200"

wait_for_http() {
  local name="$1"
  local url="$2"
  local attempts=20
  local delay=0.5

  if ! command -v curl >/dev/null 2>&1; then
    echo "❌ curl is required to check ${name} at ${url}"
    return 1
  fi

  for ((i = 1; i <= attempts; i++)); do
    if curl -s -o /dev/null "${url}"; then
      return 0
    fi
    sleep "${delay}"
  done

  echo "❌ ${name} is not reachable at ${url}"
  return 1
}

if [[ ! -d "$INFRA_DIR" ]]; then
  echo "❌ Error: infrastructure directory not found at $INFRA_DIR"
  exit 1
fi

# Ensure dependencies are up before apply
wait_for_http "Vault" "${VAULT_ADDR}/v1/sys/health"
wait_for_http "Consul" "${CONSUL_ADDR}/v1/status/leader"

echo "🚀 Applying Terraform for environment: $ENV"
cd "$INFRA_DIR"

if ! make apply ENV="$ENV"; then
  echo "❌ Terraform apply failed for environment: $ENV"
  exit 1
fi

echo "✅ Terraform apply completed for environment: $ENV"
