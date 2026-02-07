#!/usr/bin/env bash
set -euo pipefail

SHELL_DIR="scripts/shell"

VAULT_SCRIPT="${SHELL_DIR}/start_dev_vault.sh"
CONSUL_SCRIPT="${SHELL_DIR}/start_dev_consul.sh"
PROFILE_SCRIPT="${SHELL_DIR}/init_profile.sh"

# Helper function
run_script() {
  local script_path="$1"

  if [[ ! -f "$script_path" ]]; then
    echo "❌ Script not found: $script_path"
    exit 1
  fi

  if [[ ! -x "$script_path" ]]; then
    echo "⚠️ Script is not executable, fixing permissions: $script_path"
    chmod +x "$script_path"
  fi

  echo "▶️ Running: $script_path"
  "$script_path"
  echo "✔️ Completed: $script_path"
  echo ""
}

echo "🚀 Starting development environment..."

run_script "$VAULT_SCRIPT"
run_script "$CONSUL_SCRIPT"
run_script "$PROFILE_SCRIPT"

echo "🎉 All development services started successfully."
