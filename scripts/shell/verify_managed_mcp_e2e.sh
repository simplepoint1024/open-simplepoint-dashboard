#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${MCP_E2E_BASE_URL:-http://127.0.0.1:8080/ai}"
AUTHORIZATION="${MCP_E2E_AUTHORIZATION:-}"
COOKIE="${MCP_E2E_COOKIE:-}"
CONTEXT_ID="${MCP_E2E_CONTEXT_ID:-}"
IMAGE_REFERENCE="${MCP_E2E_IMAGE_REFERENCE:-}"
IMAGE_DIGEST="${MCP_E2E_IMAGE_DIGEST:-}"
TOOL_NAME="${MCP_E2E_TOOL_NAME:-echo}"
TOOL_ARGUMENTS="${MCP_E2E_TOOL_ARGUMENTS:-{\"message\":\"open-simplepoint-managed-mcp-e2e-ok\"}}"
EXPECTED_TEXT="${MCP_E2E_EXPECTED_TEXT:-open-simplepoint-managed-mcp-e2e-ok}"
VERIFY_REDEPLOY="${MCP_E2E_VERIFY_REDEPLOY:-true}"
KEEP_RESOURCES="${MCP_E2E_KEEP_RESOURCES:-false}"
WAIT_ATTEMPTS="${MCP_E2E_WAIT_ATTEMPTS:-90}"

TEMP_DIR=""
SERVER_ID=""
POOL_ID=""

fail() {
  printf 'Managed MCP E2E verification failed: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 is required"
}

api() {
  local method="$1"
  local path="$2"
  local body="${3:-}"
  local response_file="${TEMP_DIR}/response.json"
  local -a args=(
    -sS
    -o "${response_file}"
    -w '%{http_code}'
    -X "${method}"
    -H 'Accept: application/json'
  )
  if [[ -n "${AUTHORIZATION}" ]]; then
    args+=(-H "Authorization: ${AUTHORIZATION}")
  fi
  if [[ -n "${COOKIE}" ]]; then
    args+=(-H "Cookie: ${COOKIE}")
  fi
  if [[ -n "${CONTEXT_ID}" ]]; then
    args+=(-H "X-Authorization-Context-Id: ${CONTEXT_ID}")
  fi
  if [[ -n "${body}" ]]; then
    args+=(-H 'Content-Type: application/json' --data-binary "${body}")
  fi
  local status
  status="$(curl "${args[@]}" "${BASE_URL%/}${path}")"
  if [[ ! "${status}" =~ ^2[0-9][0-9]$ ]]; then
    printf 'HTTP %s from %s %s\n' "${status}" "${method}" "${path}" >&2
    sed -n '1,80p' "${response_file}" >&2
    return 1
  fi
  cat "${response_file}"
}

best_effort_api() {
  api "$@" >/dev/null 2>&1 || true
}

pool_is_reclaimed() {
  local pool
  pool="$(api GET "/workbench/runtime/pools/${POOL_ID}")" || return 1
  [[ "$(jq -r '.currentReplicas' <<< "${pool}")" == "0" ]] \
    && [[ "$(jq -r '.readyReplicas' <<< "${pool}")" == "0" ]] \
    && api GET "/workbench/runtime/workloads/by-pool/${POOL_ID}" \
      | jq -e 'all(.status == "SUCCEEDED"
          or .status == "FAILED"
          or .status == "LOST"
          or .status == "CANCELLED")' >/dev/null
}

cleanup() {
  local exit_status=$?
  if [[ "${KEEP_RESOURCES}" != "true" ]]; then
    if [[ -n "${POOL_ID}" ]]; then
      best_effort_api POST "/workbench/runtime/pools/${POOL_ID}/disable" '{}'
      for _ in $(seq 1 60); do
        pool_is_reclaimed && break
        sleep 1
      done
      best_effort_api DELETE "/workbench/runtime/pools/${POOL_ID}"
    fi
    if [[ -n "${SERVER_ID}" ]]; then
      best_effort_api DELETE \
        "/workbench/mcp/servers?ids=${SERVER_ID}"
    fi
  fi
  if [[ -n "${TEMP_DIR}" && -d "${TEMP_DIR}" ]]; then
    rm -rf -- "${TEMP_DIR}"
  fi
  return "${exit_status}"
}

wait_for_ready_pool() {
  local pool=""
  for attempt in $(seq 1 "${WAIT_ATTEMPTS}"); do
    pool="$(api GET "/workbench/runtime/pools/${POOL_ID}")"
    local status
    status="$(jq -r '.status' <<< "${pool}")"
    if [[ "${status}" == "READY" ]] \
        && [[ "$(jq -r '.readyReplicas' <<< "${pool}")" -ge 1 ]]; then
      printf '%s' "${pool}"
      return 0
    fi
    if [[ "${status}" == "ERROR" ]]; then
      fail "Runtime Pool entered ERROR: $(jq -r '.lastError // "unknown"' <<< "${pool}")"
    fi
    printf 'Waiting for Runtime Pool: attempt=%s status=%s replicas=%s/%s\n' \
      "${attempt}" \
      "${status}" \
      "$(jq -r '.readyReplicas' <<< "${pool}")" \
      "$(jq -r '.desiredReplicas' <<< "${pool}")" >&2
    sleep 2
  done
  fail "Runtime Pool did not become READY"
}

active_workload_ids() {
  api GET "/workbench/runtime/workloads/by-pool/${POOL_ID}" \
    | jq -r '.[] | select(
        .status != "SUCCEEDED"
        and .status != "FAILED"
        and .status != "LOST"
        and .status != "CANCELLED"
      ) | .id' \
    | sort
}

discover_and_call() {
  local discovery
  discovery="$(api POST "/workbench/mcp/servers/${SERVER_ID}/discover" '{}')"
  jq -e --arg tool "${TOOL_NAME}" \
    'any(.tools[]?; .name == $tool)' <<< "${discovery}" >/dev/null \
    || fail "Tool ${TOOL_NAME} was not discovered"

  local tools
  tools="$(api GET "/workbench/mcp/servers/${SERVER_ID}/tools")"
  jq -e --arg tool "${TOOL_NAME}" \
    'any(.[]?; .name == $tool)' <<< "${tools}" >/dev/null \
    || fail "Tool ${TOOL_NAME} is missing from the active snapshot"

  local call_body
  call_body="$(jq -cn \
    --arg tool "${TOOL_NAME}" \
    --argjson arguments "${TOOL_ARGUMENTS}" \
    '{toolName: $tool, arguments: $arguments}')"
  local result
  result="$(api POST \
    "/workbench/mcp/servers/${SERVER_ID}/tools/call" \
    "${call_body}")"
  jq -e '.error == false' <<< "${result}" >/dev/null \
    || fail "Tool ${TOOL_NAME} returned an MCP error"
  if [[ -n "${EXPECTED_TEXT}" ]] \
      && ! jq -c . <<< "${result}" | grep -Fq -- "${EXPECTED_TEXT}"; then
    fail "Tool result does not contain expected text: ${EXPECTED_TEXT}"
  fi
}

main() {
  require_command curl
  require_command jq
  require_command sed
  require_command sort
  require_command grep
  [[ -n "${AUTHORIZATION}" || -n "${COOKIE}" ]] \
    || fail "MCP_E2E_AUTHORIZATION or MCP_E2E_COOKIE is required"
  [[ -n "${IMAGE_REFERENCE}" ]] \
    || fail "MCP_E2E_IMAGE_REFERENCE is required"
  jq -e 'type == "object"' <<< "${TOOL_ARGUMENTS}" >/dev/null \
    || fail "MCP_E2E_TOOL_ARGUMENTS must be one JSON object"
  [[ "${WAIT_ATTEMPTS}" =~ ^[1-9][0-9]*$ ]] \
    || fail "MCP_E2E_WAIT_ATTEMPTS must be a positive integer"
  [[ "${VERIFY_REDEPLOY}" == "true" || "${VERIFY_REDEPLOY}" == "false" ]] \
    || fail "MCP_E2E_VERIFY_REDEPLOY must be true or false"
  [[ "${KEEP_RESOURCES}" == "true" || "${KEEP_RESOURCES}" == "false" ]] \
    || fail "MCP_E2E_KEEP_RESOURCES must be true or false"

  if [[ -z "${IMAGE_DIGEST}" ]]; then
    require_command docker
    IMAGE_DIGEST="$(docker image inspect \
      --format '{{.Id}}' "${IMAGE_REFERENCE}")"
  fi
  [[ "${IMAGE_DIGEST}" =~ ^sha256:[a-f0-9]{64}$ ]] \
    || fail "MCP_E2E_IMAGE_DIGEST must be a sha256 digest"

  TEMP_DIR="$(mktemp -d /tmp/open-simplepoint-managed-mcp-e2e.XXXXXX)"
  trap cleanup EXIT

  local suffix
  suffix="$(date -u +%Y%m%d%H%M%S)-$$"
  local code="managed-mcp-e2e-${suffix}"
  local server_body
  server_body="$(jq -cn \
    --arg name "Managed MCP E2E ${suffix}" \
    --arg code "${code}" \
    '{
      name: $name,
      code: $code,
      deploymentType: "MANAGED_OCI",
      transportType: "STDIO",
      authenticationType: "NONE",
      enabled: true,
      description: "Automated managed OCI MCP lifecycle verification"
    }')"
  local server
  server="$(api POST "/workbench/mcp/servers" "${server_body}")"
  SERVER_ID="$(jq -r '.id // empty' <<< "${server}")"
  [[ -n "${SERVER_ID}" ]] || fail "MCP Server creation returned no ID"
  printf 'Created MCP Server %s\n' "${SERVER_ID}"

  local pool_body
  pool_body="$(jq -cn \
    --arg code "${code}" \
    --arg name "Managed MCP E2E Pool ${suffix}" \
    --arg serverId "${SERVER_ID}" \
    --arg imageReference "${IMAGE_REFERENCE}" \
    --arg imageDigest "${IMAGE_DIGEST}" \
    '{
      code: $code,
      name: $name,
      serverId: $serverId,
      imageReference: $imageReference,
      imageDigest: $imageDigest,
      memoryBytes: 67108864,
      nanoCpus: 250000000,
      pidsLimit: 32,
      networkMode: "none",
      egressAllowlist: [],
      secretIds: [],
      minReplicas: 1,
      maxReplicas: 2,
      desiredReplicas: 1,
      activationReplicas: 1,
      prewarmNodes: 1,
      idleTimeoutSeconds: 300,
      replicaLifetimeSeconds: 3600
    }')"
  local pool
  pool="$(api POST "/workbench/runtime/pools" "${pool_body}")"
  POOL_ID="$(jq -r '.id // empty' <<< "${pool}")"
  [[ -n "${POOL_ID}" ]] || fail "Runtime Pool creation returned no ID"
  printf 'Created Runtime Pool %s\n' "${POOL_ID}"

  pool="$(wait_for_ready_pool)"
  api GET "/workbench/runtime/pools/by-server/${SERVER_ID}" \
    | jq -e --arg id "${POOL_ID}" '.id == $id' >/dev/null \
    || fail "Pool by-server query returned an unexpected resource"
  discover_and_call
  printf 'Initial discovery and Tool call passed\n'

  if [[ "${VERIFY_REDEPLOY}" == "true" ]]; then
    local previous_ids
    previous_ids="$(active_workload_ids)"
    [[ -n "${previous_ids}" ]] \
      || fail "No active Workload exists before redeploy"
    api POST "/workbench/runtime/pools/${POOL_ID}/redeploy" '{}' >/dev/null
    wait_for_ready_pool >/dev/null
    local replacement_ids
    replacement_ids="$(active_workload_ids)"
    [[ -n "${replacement_ids}" ]] \
      || fail "No active Workload exists after redeploy"
    [[ "${replacement_ids}" != "${previous_ids}" ]] \
      || fail "Redeploy did not replace the active Workload"
    discover_and_call
    printf 'Redeploy and post-redeploy Tool call passed\n'
  fi

  printf 'Managed MCP E2E verification passed\n'
  if [[ "${KEEP_RESOURCES}" == "true" ]]; then
    printf 'Resources retained: server=%s pool=%s\n' \
      "${SERVER_ID}" "${POOL_ID}"
  fi
}

main "$@"
