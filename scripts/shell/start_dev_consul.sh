#!/usr/bin/env bash
set -euo pipefail

CONSUL_PORT=8500

# Check if Consul is installed
if ! command -v consul >/dev/null 2>&1; then
  echo "❌ Consul is not installed on this system."
  echo "👉 Please install Consul first: https://developer.hashicorp.com/consul/downloads"
  exit 1
fi

echo "🔍 Checking if Consul is already running on port ${CONSUL_PORT}..."

# Check if Consul is already running
if lsof -i :${CONSUL_PORT} >/dev/null 2>&1; then
  echo "⚠️ Consul is already running on port ${CONSUL_PORT}."
  echo "🌐 UI: http://localhost:${CONSUL_PORT}"
  exit 0
fi

# Determine script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Log directory
LOG_DIR="${SCRIPT_DIR}/logs"
mkdir -p "$LOG_DIR"

LOG_FILE="${LOG_DIR}/consul-dev-$(date +%Y%m%d-%H%M%S).log"

echo "🚀 Starting Consul in dev mode..."
echo "📄 Log file: ${LOG_FILE}"

# Start Consul in dev mode
nohup consul agent -dev > "${LOG_FILE}" 2>&1 &

sleep 1

# Verify Consul started successfully
if lsof -i :${CONSUL_PORT} >/dev/null 2>&1; then
  echo "✅ Consul dev mode started successfully."
  echo "🌐 UI: http://localhost:${CONSUL_PORT}"
else
  echo "❌ Failed to start Consul. Check logs at: ${LOG_FILE}"
  exit 1
fi
