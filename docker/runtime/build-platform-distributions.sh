#!/usr/bin/env bash
set -euo pipefail

build_workers="${SIMPLEPOINT_BUILD_MAX_WORKERS:-1}"
native_retry_limit="${SIMPLEPOINT_BUILD_NATIVE_RETRY_LIMIT:-3}"

if [[ "$#" -eq 0 ]]; then
  build_targets=(
    :simplepoint-services:simplepoint-service-authorization:installDist
    :simplepoint-services:simplepoint-service-common:installDist
    :simplepoint-services:simplepoint-service-auditing:installDist
    :simplepoint-services:simplepoint-service-dna:installDist
    :simplepoint-services:simplepoint-service-ai:installDist
    :simplepoint-services:simplepoint-service-mcp-gateway:installDist
    :simplepoint-services:simplepoint-service-host:installDist
  )
else
  build_targets=("$@")
fi

if [[ ! "${native_retry_limit}" =~ ^[1-9][0-9]*$ ]]; then
  printf 'SIMPLEPOINT_BUILD_NATIVE_RETRY_LIMIT must be a positive integer\n' >&2
  exit 1
fi

build_log="$(mktemp /tmp/simplepoint-platform-build.XXXXXX)"
trap 'rm -f -- "${build_log}"' EXIT

for attempt in $(seq 1 "${native_retry_limit}"); do
  : > "${build_log}"
  if gradle \
      --no-daemon \
      --parallel \
      --max-workers="${build_workers}" \
      -Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=1g -XX:ReservedCodeCacheSize=512m -XX:TieredStopAtLevel=1" \
      "${build_targets[@]}" 2>&1 | tee "${build_log}"; then
    exit 0
  fi
  if ! grep -Eq \
      "finished with non-zero exit value (133|134|139)" \
      "${build_log}" \
      || [[ "${attempt}" -ge "${native_retry_limit}" ]]; then
    exit 1
  fi
  printf 'Native frontend build exited unexpectedly; retrying (%s/%s)...\n' \
    "$((attempt + 1))" \
    "${native_retry_limit}" >&2
done
