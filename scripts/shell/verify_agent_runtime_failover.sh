#!/usr/bin/env bash
set -euo pipefail

AGENT_API_URL="${AGENT_API_URL:-http://127.0.0.1:2888}"
AGENT_TOKEN="${AGENT_TOKEN:-}"
AGENT_ID="${AGENT_ID:-}"
AGENT_RUNTIME_SERVICE="${AGENT_RUNTIME_SERVICE:-agent-runtime}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-open-simplepoint-postgres-1}"
FAILOVER_TIMEOUT_SECONDS="${FAILOVER_TIMEOUT_SECONDS:-180}"
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-0.2}"
AGENT_REQUEST="${AGENT_REQUEST:-请调用已绑定技能一次，参数使用 message=agent-failover-ok、name=SimplePoint、uri=document://42；成功后用一句话总结。}"

STOPPED_CONTAINER=""

cleanup() {
  if [[ -n "${STOPPED_CONTAINER}" ]]; then
    docker start "${STOPPED_CONTAINER}" >/dev/null 2>&1 || true
  fi
}

fail() {
  printf 'Agent Runtime failover verification failed: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 is required"
}

postgres_query() {
  docker exec "${POSTGRES_CONTAINER}" \
    psql -v ON_ERROR_STOP=1 -U postgres -d simplepoint -Atc "$1"
}

execution_state() {
  local execution_id="$1"
  postgres_query \
    "SELECT status || '|' || COALESCE(lease_owner, '') || '|' || lease_token
              || '|' || COALESCE(current_trace_id, '')
       FROM simpoint_ai_agent_executions
      WHERE id = '${execution_id}'"
}

capture_active_lease() {
  local execution_id="$1"
  local deadline=$((SECONDS + 15))
  local row=""
  while ((SECONDS < deadline)); do
    row="$(execution_state "${execution_id}")"
    IFS='|' read -r STATUS LEASE_OWNER LEASE_TOKEN CURRENT_TRACE_ID \
      <<< "${row}"
    if [[ "${STATUS}" == "RUNNING"
        && -n "${LEASE_OWNER}"
        && -n "${CURRENT_TRACE_ID}" ]]; then
      return 0
    fi
    if [[ "${STATUS}" =~ ^(SUCCEEDED|FAILED|CANCELLED)$ ]]; then
      fail "execution ${execution_id} reached ${STATUS} before fault injection"
    fi
    sleep 0.02
  done
  fail "execution ${execution_id} did not expose an active lease"
}

wait_for_takeover() {
  local execution_id="$1"
  local old_owner="$2"
  local old_token="$3"
  local deadline=$((SECONDS + FAILOVER_TIMEOUT_SECONDS))
  local row=""
  TAKEOVER_SEEN=false
  while ((SECONDS < deadline)); do
    row="$(execution_state "${execution_id}")"
    IFS='|' read -r STATUS CURRENT_OWNER CURRENT_TOKEN CURRENT_TRACE_ID \
      <<< "${row}"
    if [[ "${CURRENT_TOKEN}" =~ ^[0-9]+$
        && "${CURRENT_TOKEN}" -gt "${old_token}"
        && "${CURRENT_OWNER}" != "${old_owner}" ]]; then
      TAKEOVER_SEEN=true
    fi
    if [[ "${STATUS}" =~ ^(SUCCEEDED|FAILED|CANCELLED)$ ]]; then
      return 0
    fi
    sleep "${POLL_INTERVAL_SECONDS}"
  done
  fail "execution ${execution_id} did not reach a terminal state"
}

main() {
  trap cleanup EXIT
  require_command curl
  require_command docker
  require_command jq
  [[ -n "${AGENT_TOKEN}" ]] || fail "AGENT_TOKEN is required"
  [[ "${AGENT_ID}" =~ ^[0-9a-f-]{36}$ ]] \
    || fail "AGENT_ID must be a UUID"
  [[ "${FAILOVER_TIMEOUT_SECONDS}" =~ ^[1-9][0-9]*$ ]] \
    || fail "FAILOVER_TIMEOUT_SECONDS must be a positive integer"
  docker inspect "${POSTGRES_CONTAINER}" >/dev/null 2>&1 \
    || fail "PostgreSQL container ${POSTGRES_CONTAINER} is unavailable"

  mapfile -t runtime_containers < <(
    docker compose ps -q "${AGENT_RUNTIME_SERVICE}"
  )
  ((${#runtime_containers[@]} >= 2)) \
    || fail "at least two Agent Runtime containers are required"

  local idempotency_key="agent-failover-$(date +%s%N)"
  local request_body
  request_body="$(
    jq -cn \
      --arg key "${idempotency_key}" \
      --arg request "${AGENT_REQUEST}" \
      '{idempotencyKey: $key, input: {request: $request}}'
  )"
  local response
  response="$(
    curl -fsS \
      -H "Authorization: Bearer ${AGENT_TOKEN}" \
      -H "Content-Type: application/json" \
      --data-binary "${request_body}" \
      "${AGENT_API_URL%/}/workbench/agents/${AGENT_ID}/executions"
  )"
  local execution_id
  execution_id="$(jq -er '.id' <<< "${response}")"
  [[ "${execution_id}" =~ ^[0-9a-f-]{36}$ ]] \
    || fail "Agent API returned an invalid execution ID"

  capture_active_lease "${execution_id}"
  local old_owner="${LEASE_OWNER}"
  local old_token="${LEASE_TOKEN}"
  local owner_host="${old_owner%%-*}"
  STOPPED_CONTAINER="$(
    docker ps --format '{{.ID}} {{.Names}}' \
      | awk -v host="${owner_host}" \
          'index($1, host) == 1 {print $2; exit}'
  )"
  [[ -n "${STOPPED_CONTAINER}" ]] \
    || fail "unable to resolve lease owner ${old_owner}"

  docker stop --timeout 0 "${STOPPED_CONTAINER}" >/dev/null
  wait_for_takeover "${execution_id}" "${old_owner}" "${old_token}"
  [[ "${TAKEOVER_SEEN}" == "true" ]] \
    || fail "no higher fencing token was observed after stopping the owner"
  [[ "${STATUS}" == "SUCCEEDED" ]] || {
    local error_code
    error_code="$(
      postgres_query \
        "SELECT COALESCE(error_code, '')
           FROM simpoint_ai_agent_executions
          WHERE id = '${execution_id}'"
    )"
    fail "execution ${execution_id} ended as ${STATUS}: ${error_code}"
  }

  local trace_count
  local event_count
  local running_trace_count
  local expired_trace_count
  trace_count="$(
    postgres_query \
      "SELECT COUNT(*)
         FROM simpoint_ai_agent_execution_traces
        WHERE execution_id = '${execution_id}'"
  )"
  event_count="$(
    postgres_query \
      "SELECT COUNT(*)
         FROM simpoint_ai_agent_execution_events
        WHERE execution_id = '${execution_id}'"
  )"
  running_trace_count="$(
    postgres_query \
      "SELECT COUNT(*)
         FROM simpoint_ai_agent_execution_traces
        WHERE execution_id = '${execution_id}'
          AND deleted_at IS NULL
          AND status = 'RUNNING'"
  )"
  [[ "${running_trace_count}" == "0" ]] \
    || fail "execution ${execution_id} retained an orphan RUNNING trace"
  expired_trace_count="$(
    postgres_query \
      "SELECT COUNT(*)
         FROM simpoint_ai_agent_execution_traces
        WHERE execution_id = '${execution_id}'
          AND deleted_at IS NULL
          AND error_code = 'AGENT_RUNTIME_LEASE_EXPIRED'"
  )"
  [[ "${expired_trace_count}" -ge 1 ]] \
    || fail "execution ${execution_id} did not reconcile the interrupted trace"
  printf \
    'Agent Runtime failover passed: execution=%s oldToken=%s newToken=%s traces=%s events=%s expiredTraces=%s\n' \
    "${execution_id}" \
    "${old_token}" \
    "${CURRENT_TOKEN}" \
    "${trace_count}" \
    "${event_count}" \
    "${expired_trace_count}"
}

main "$@"
