#!/usr/bin/env bash
set -euo pipefail

VAULT_PORT=8200
VAULT_ADDR="http://127.0.0.1:${VAULT_PORT}"

# Default token extracted as a variable
DEFAULT_DEV_TOKEN="root"

# Read dev token from argument or prompt
DEV_TOKEN="${1:-}"

if [[ -z "$DEV_TOKEN" ]]; then
  read -rp "Enter Vault dev root token (default: ${DEFAULT_DEV_TOKEN}): " INPUT_TOKEN
  DEV_TOKEN="${INPUT_TOKEN:-$DEFAULT_DEV_TOKEN}"
fi

# Check if Vault is installed
if ! command -v vault >/dev/null 2>&1; then
  echo "❌ Vault is not installed on this system."
  echo "👉 Please install Vault first: https://developer.hashicorp.com/vault/downloads"
  exit 1
fi

VAULT_PID=""

is_vault_running() {
  if command -v lsof >/dev/null 2>&1; then
    if lsof -i :${VAULT_PORT} >/dev/null 2>&1; then
      return 0
    fi
  fi

  if command -v curl >/dev/null 2>&1; then
    local code
    code="$(curl -s -o /dev/null -w "%{http_code}" "${VAULT_ADDR}/v1/sys/health" || true)"
    if [[ "$code" != "000" ]]; then
      return 0
    fi
  fi

  if vault status -address="${VAULT_ADDR}" >/dev/null 2>&1; then
    return 0
  fi

  return 1
}

wait_for_vault() {
  local attempts=20
  local delay=0.5

  for ((i = 1; i <= attempts; i++)); do
    if is_vault_running; then
      return 0
    fi
    if [[ -n "$VAULT_PID" ]] && ! kill -0 "$VAULT_PID" >/dev/null 2>&1; then
      return 1
    fi
    sleep "${delay}"
  done

  return 1
}

start_vault() {
  if command -v setsid >/dev/null 2>&1; then
    nohup setsid vault server -dev -dev-root-token-id="${DEV_TOKEN}" > "${LOG_FILE}" 2>&1 &
  else
    nohup vault server -dev -dev-root-token-id="${DEV_TOKEN}" > "${LOG_FILE}" 2>&1 &
  fi

  VAULT_PID=$!
  echo "${VAULT_PID}" > "${PID_FILE}"
  disown "${VAULT_PID}" 2>/dev/null || true
}

echo "🔍 Checking if Vault is already running..."

# Check if Vault is already running
if is_vault_running; then
  echo "⚠️ Vault is already running on port ${VAULT_PORT}."
  echo "🌐 UI available at: ${VAULT_ADDR}/ui"
  exit 0
fi

# Prepare log directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="${SCRIPT_DIR}/logs"
mkdir -p "$LOG_DIR"

LOG_FILE="${LOG_DIR}/vault-dev-$(date +%Y%m%d-%H%M%S).log"
PID_FILE="${LOG_DIR}/vault-dev.pid"

echo "🚀 Starting Vault in dev mode..."
echo "🔑 Using dev root token: ${DEV_TOKEN}"
echo "📄 Log file: ${LOG_FILE}"

# Start Vault in dev mode
start_vault

# Verify Vault started successfully
if wait_for_vault; then
  echo "✅ Vault dev mode started successfully."
  echo "🔑 Root Token: ${DEV_TOKEN}"
  echo "🌐 UI: ${VAULT_ADDR}/ui"
  echo ""
  echo "👉 You may want to export VAULT_ADDR:"
  echo "   export VAULT_ADDR=${VAULT_ADDR}"
else
  if [[ -n "$VAULT_PID" ]] && ! kill -0 "$VAULT_PID" >/dev/null 2>&1; then
    echo "❌ Vault process exited early (pid: ${VAULT_PID}). Check logs at: ${LOG_FILE}"
  else
    echo "❌ Failed to start Vault. Check logs at: ${LOG_FILE}"
  fi
  exit 1
fi
