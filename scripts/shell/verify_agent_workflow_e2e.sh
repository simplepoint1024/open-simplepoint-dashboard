#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${WORKFLOW_E2E_BASE_URL:-http://127.0.0.1:2888}"
AUTHORIZATION="${WORKFLOW_E2E_AUTHORIZATION:-}"
COOKIE="${WORKFLOW_E2E_COOKIE:-}"
CONTEXT_ID="${WORKFLOW_E2E_CONTEXT_ID:-}"
DURABLE_WORKFLOW_ID="${WORKFLOW_E2E_DURABLE_WORKFLOW_ID:-}"
COMPENSATION_WORKFLOW_ID="${WORKFLOW_E2E_COMPENSATION_WORKFLOW_ID:-}"
RESTART_RUNTIMES="${WORKFLOW_E2E_RESTART_RUNTIMES:-true}"
RUNTIME_SERVICE="${WORKFLOW_E2E_RUNTIME_SERVICE:-workflow-runtime}"
RUNTIME_REPLICAS="${WORKFLOW_E2E_RUNTIME_REPLICAS:-2}"
WAIT_ATTEMPTS="${WORKFLOW_E2E_WAIT_ATTEMPTS:-240}"

TEMP_DIR=""

fail() {
  printf 'Agent Workflow E2E verification failed: %s\n' "$*" >&2
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
    sed -n '1,100p' "${response_file}" >&2
    return 1
  fi
  cat "${response_file}"
}

execution() {
  local workflow_id="$1"
  local execution_id="$2"
  api GET \
    "/workbench/workflows/${workflow_id}/executions/${execution_id}"
}

wait_for_status() {
  local workflow_id="$1"
  local execution_id="$2"
  local expected="$3"
  local current=""
  local status=""
  for _ in $(seq 1 "${WAIT_ATTEMPTS}"); do
    current="$(execution "${workflow_id}" "${execution_id}")"
    status="$(jq -r '.status' <<< "${current}")"
    if [[ ",${expected}," == *",${status},"* ]]; then
      printf '%s' "${current}"
      return 0
    fi
    case "${status}" in
      FAILED|CANCELLED)
        jq . <<< "${current}" >&2
        fail "execution ${execution_id} entered ${status}"
        ;;
    esac
    sleep 0.25
  done
  fail "execution ${execution_id} did not enter ${expected}"
}

wait_for_runtime_health() {
  local healthy=0
  for _ in $(seq 1 120); do
    healthy="$(
      docker compose ps --format json "${RUNTIME_SERVICE}" \
        | jq -s '[.[] | select(.Health == "healthy")] | length'
    )"
    if [[ "${healthy}" -eq "${RUNTIME_REPLICAS}" ]]; then
      return 0
    fi
    sleep 1
  done
  docker compose ps "${RUNTIME_SERVICE}" >&2
  fail "Workflow Runtime replicas did not become healthy"
}

run_durable_verification() {
  local key="workflow-durable-$(date +%s%N)"
  local body
  body="$(
    jq -cn --arg key "${key}" \
      '{idempotencyKey: $key, input: {approved: true}}'
  )"
  local started execution_id duplicate_id
  started="$(api POST \
    "/workbench/workflows/${DURABLE_WORKFLOW_ID}/executions" \
    "${body}")"
  execution_id="$(jq -er '.id' <<< "${started}")"
  duplicate_id="$(
    api POST \
      "/workbench/workflows/${DURABLE_WORKFLOW_ID}/executions" \
      "${body}" \
      | jq -er '.id'
  )"
  [[ "${duplicate_id}" == "${execution_id}" ]] \
    || fail "idempotent Workflow submission returned a different execution"

  wait_for_status \
    "${DURABLE_WORKFLOW_ID}" \
    "${execution_id}" \
    "WAITING_TIMER,WAITING_HUMAN" >/dev/null
  local paused resumed
  paused="$(api POST \
    "/workbench/workflows/${DURABLE_WORKFLOW_ID}/executions/${execution_id}/pause" \
    '{"reason":"Automated durable pause checkpoint"}')"
  [[ "$(jq -r '.status' <<< "${paused}")" == "PAUSED" ]] \
    || fail "Workflow execution did not pause"
  resumed="$(api POST \
    "/workbench/workflows/${DURABLE_WORKFLOW_ID}/executions/${execution_id}/resume" \
    '{}')"
  case "$(jq -r '.status' <<< "${resumed}")" in
    PENDING|RUNNING|WAITING_TIMER) ;;
    *) fail "Workflow execution did not resume" ;;
  esac

  local waiting task_id
  waiting="$(wait_for_status \
    "${DURABLE_WORKFLOW_ID}" \
    "${execution_id}" \
    "WAITING_HUMAN")"
  task_id="$(
    jq -er '.humanTasks[] | select(.status == "OPEN") | .id' \
      <<< "${waiting}" \
      | head -n 1
  )"

  if [[ "${RESTART_RUNTIMES}" == "true" ]]; then
    docker compose stop "${RUNTIME_SERVICE}" >/dev/null
    docker compose up \
      -d \
      --no-deps \
      --scale "${RUNTIME_SERVICE}=${RUNTIME_REPLICAS}" \
      "${RUNTIME_SERVICE}" >/dev/null
    wait_for_runtime_health
    waiting="$(execution "${DURABLE_WORKFLOW_ID}" "${execution_id}")"
    [[ "$(jq -r '.status' <<< "${waiting}")" == "WAITING_HUMAN" ]] \
      || fail "durable human task was lost after Runtime restart"
  fi

  local human_response
  human_response="$(api POST \
    "/workbench/workflows/${DURABLE_WORKFLOW_ID}/executions/${execution_id}/human-tasks/${task_id}/respond" \
    '{"output":{"accepted":true}}')"
  case "$(jq -r '.status' <<< "${human_response}")" in
    PENDING|RUNNING|SUCCEEDED) ;;
    *) fail "Workflow human task did not resume the execution" ;;
  esac

  local completed events
  completed="$(wait_for_status \
    "${DURABLE_WORKFLOW_ID}" \
    "${execution_id}" \
    "SUCCEEDED")"
  jq -e '
    all(.nodes[]; .status == "SUCCEEDED" or .status == "SKIPPED")
    and any(.humanTasks[]; .status == "COMPLETED")
  ' <<< "${completed}" >/dev/null \
    || fail "durable Workflow checkpoints are incomplete"
  events="$(api GET \
    "/workbench/workflows/${DURABLE_WORKFLOW_ID}/executions/${execution_id}/events?after=0&limit=200")"
  jq -e '
    any(.events[]; .type == "HUMAN_TASK_COMPLETED")
    and any(.events[]; .type == "EXECUTION_SUCCEEDED")
  ' <<< "${events}" >/dev/null \
    || fail "durable Workflow event feed is incomplete"
  printf \
    'Durable Workflow passed: execution=%s nodes=%s events=%s\n' \
    "${execution_id}" \
    "$(jq '.nodes | length' <<< "${completed}")" \
    "$(jq '.events | length' <<< "${events}")"
}

run_compensation_verification() {
  local key="workflow-compensation-$(date +%s%N)"
  local body started execution_id completed events
  body="$(
    jq -cn --arg key "${key}" \
      '{idempotencyKey: $key, input: {}}'
  )"
  started="$(api POST \
    "/workbench/workflows/${COMPENSATION_WORKFLOW_ID}/executions" \
    "${body}")"
  execution_id="$(jq -er '.id' <<< "${started}")"
  completed="$(wait_for_status \
    "${COMPENSATION_WORKFLOW_ID}" \
    "${execution_id}" \
    "FAILED")"
  jq -e '
    .errorCode == "WORKFLOW_NODE_EXECUTION_FAILED"
    and any(
      .nodes[];
      .status == "COMPENSATED"
      and (.compensationExecutionId | length) > 0
    )
    and any(.nodes[]; .status == "FAILED")
  ' <<< "${completed}" >/dev/null \
    || fail "Workflow compensation checkpoints are incomplete"
  events="$(api GET \
    "/workbench/workflows/${COMPENSATION_WORKFLOW_ID}/executions/${execution_id}/events?after=0&limit=200")"
  jq -e '
    any(.events[]; .type == "COMPENSATION_STARTED")
    and any(.events[]; .type == "COMPENSATION_SUCCEEDED")
    and any(.events[]; .type == "EXECUTION_FAILED")
  ' <<< "${events}" >/dev/null \
    || fail "Workflow compensation event feed is incomplete"
  printf \
    'Workflow compensation passed: execution=%s events=%s\n' \
    "${execution_id}" \
    "$(jq '.events | length' <<< "${events}")"
}

main() {
  require_command curl
  require_command jq
  require_command sed
  require_command seq
  [[ -n "${AUTHORIZATION}" || -n "${COOKIE}" ]] \
    || fail "WORKFLOW_E2E_AUTHORIZATION or WORKFLOW_E2E_COOKIE is required"
  [[ "${DURABLE_WORKFLOW_ID}" =~ ^[0-9a-f-]{36}$ ]] \
    || fail "WORKFLOW_E2E_DURABLE_WORKFLOW_ID must be a UUID"
  [[ "${COMPENSATION_WORKFLOW_ID}" =~ ^[0-9a-f-]{36}$ ]] \
    || fail "WORKFLOW_E2E_COMPENSATION_WORKFLOW_ID must be a UUID"
  [[ "${WAIT_ATTEMPTS}" =~ ^[1-9][0-9]*$ ]] \
    || fail "WORKFLOW_E2E_WAIT_ATTEMPTS must be a positive integer"
  [[ "${RUNTIME_REPLICAS}" =~ ^[1-9][0-9]*$ ]] \
    || fail "WORKFLOW_E2E_RUNTIME_REPLICAS must be a positive integer"
  [[ "${RESTART_RUNTIMES}" == "true"
      || "${RESTART_RUNTIMES}" == "false" ]] \
    || fail "WORKFLOW_E2E_RESTART_RUNTIMES must be true or false"
  if [[ "${RESTART_RUNTIMES}" == "true" ]]; then
    require_command docker
  fi

  TEMP_DIR="$(mktemp -d /tmp/open-simplepoint-workflow-e2e.XXXXXX)"
  trap 'rm -rf -- "${TEMP_DIR}"' EXIT
  run_durable_verification
  run_compensation_verification
  printf 'Agent Workflow E2E verification passed\n'
}

main "$@"
