#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SOURCE_PROFILE="${ROOT_DIR}/docker/runtime-security/open-simplepoint-mcp-workload.apparmor"
TARGET_PROFILE="/etc/apparmor.d/open-simplepoint-mcp-workload"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run this installer as root on each Runtime worker node." >&2
  exit 1
fi
if [[ ! -r /sys/module/apparmor/parameters/enabled ]] \
    || [[ "$(tr -d '\\n' </sys/module/apparmor/parameters/enabled)" != "Y" ]]; then
  echo "AppArmor is not enabled on this Runtime worker node." >&2
  exit 1
fi
if ! command -v apparmor_parser >/dev/null 2>&1; then
  echo "apparmor_parser is required on each Runtime worker node." >&2
  exit 1
fi
if [[ ! -r "${SOURCE_PROFILE}" ]]; then
  echo "Runtime AppArmor source profile is missing: ${SOURCE_PROFILE}" >&2
  exit 1
fi

install -m 0644 "${SOURCE_PROFILE}" "${TARGET_PROFILE}"
apparmor_parser --replace "${TARGET_PROFILE}"
echo "Loaded AppArmor profile open-simplepoint-mcp-workload."
