#!/usr/bin/env bash
set -euo pipefail

export npm_config_fetch_retries="${npm_config_fetch_retries:-5}"
export npm_config_fetch_retry_mintimeout="${npm_config_fetch_retry_mintimeout:-10000}"
export npm_config_fetch_retry_maxtimeout="${npm_config_fetch_retry_maxtimeout:-120000}"
export npm_config_network_concurrency="${npm_config_network_concurrency:-8}"

workspace_dir="/workspace/simplepoint-react"

for attempt in 1 2 3; do
  echo "Installing frontend dependencies (attempt ${attempt}/3)..."
  if pnpm --dir "${workspace_dir}" install --frozen-lockfile --store-dir=/pnpm/store; then
    binding_path="$(
      find "${workspace_dir}/node_modules/.pnpm" \
        -type f \
        -name 'rspack.linux-x64-gnu.node' \
        -print \
        -quit
    )"
    if [[ -n "${binding_path}" ]]; then
      exit 0
    fi
    echo "Rspack native binding is missing after dependency installation." >&2
  fi

  rm -rf "${workspace_dir}/node_modules"
done

echo "Unable to install the Rspack Linux x64 native binding." >&2
exit 1
