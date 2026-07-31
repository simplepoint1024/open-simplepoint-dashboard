#!/usr/bin/env bash
set -euo pipefail

STACK_NAME="${STACK_NAME:-open-simplepoint}"
MCP_URL="${PHASE2_MCP_URL:-}"
MCP_TOKEN="${PHASE2_MCP_TOKEN:-}"
MCP_SERVER_ID="${PHASE2_MCP_SERVER_ID:-}"
MCP_SCOPE="${PHASE2_MCP_SCOPE:-SYSTEM}"
MCP_TENANT_ID="${PHASE2_MCP_TENANT_ID:-}"
MCP_TOOL_NAME="${PHASE2_MCP_TOOL_NAME:-}"
MCP_TOOL_ARGUMENTS="${PHASE2_MCP_TOOL_ARGUMENTS:-}"
SESSION_COUNT="${PHASE2_SESSION_COUNT:-16}"
DIRECTORY_PREFIX="${SIMPLEPOINT_AI_RUNTIME_MCP_SESSION_DIRECTORY_KEY_PREFIX:-simplepoint:ai:runtime:mcp:sessions:}"
ALLOW_NODE_DRAIN="${PHASE2_ALLOW_NODE_DRAIN:-false}"
REQUIRE_DISTINCT_NODES="${PHASE2_REQUIRE_DISTINCT_NODES:-true}"
REDIS_CONTAINER="${PHASE2_REDIS_CONTAINER:-}"
POSTGRES_CONTAINER="${PHASE2_POSTGRES_CONTAINER:-}"

TEMP_DIR=""
DRAINED_NODE=""

if [[ -z "${MCP_TOOL_ARGUMENTS}" ]]; then
  MCP_TOOL_ARGUMENTS='{}'
fi

cleanup() {
  if [[ -n "${DRAINED_NODE}" ]]; then
    docker node update --availability active "${DRAINED_NODE}" >/dev/null \
      || true
  fi
  if [[ -n "${TEMP_DIR}" && -d "${TEMP_DIR}" ]]; then
    rm -rf -- "${TEMP_DIR}"
  fi
}

fail() {
  printf 'Phase 2 verification failed: %s\n' "$*" >&2
  exit 1
}

require_value() {
  local value="$1"
  local name="$2"
  [[ -n "${value}" ]] || fail "${name} is required"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 is required"
}

service_container() {
  local service_name="$1"
  docker ps \
    --filter "label=com.docker.swarm.service.name=${STACK_NAME}_${service_name}" \
    --format '{{.ID}}' \
    | head -n 1
}

redis_command() {
  docker exec "${REDIS_CONTAINER}" redis-cli --raw "$@"
}

postgres_query() {
  docker exec "${POSTGRES_CONTAINER}" \
    psql -v ON_ERROR_STOP=1 -U postgres -d simplepoint -Atc "$1"
}

frame() {
  local value="$1"
  printf '%s:%s' "${#value}" "${value}"
}

session_directory_key() {
  local session_id="$1"
  local material
  material="$(frame "${MCP_SERVER_ID}")"
  material+="$(frame "${MCP_SCOPE}")"
  material+="$(frame "${MCP_TENANT_ID}")"
  material+="$(frame "${session_id}")"
  printf '%sassignments:%s' \
    "${DIRECTORY_PREFIX}" \
    "$(printf '%s' "${material}" | sha256sum | awk '{print $1}')"
}

decode_base64url() {
  local value="${1//-/+}"
  value="${value//_/\/}"
  case $((${#value} % 4)) in
    2) value+="==" ;;
    3) value+="=" ;;
    0) ;;
    *) fail "invalid base64url assignment" ;;
  esac
  printf '%s' "${value}" | base64 --decode
}

assignment_workload() {
  local assignment="$1"
  local version
  local encoded_workload
  local encoded_lease
  local fencing_token
  IFS='.' read -r version encoded_workload encoded_lease fencing_token \
    <<< "${assignment}"
  [[ "${version}" == "v1" && -n "${encoded_workload}" \
    && -n "${encoded_lease}" && "${fencing_token}" =~ ^[1-9][0-9]*$ ]] \
    || fail "invalid Runtime session assignment"
  decode_base64url "${encoded_workload}"
}

mcp_request() {
  local session_id="$1"
  local body="$2"
  local output_file="$3"
  local -a headers=(
    -H "Authorization: Bearer ${MCP_TOKEN}"
    -H 'Accept: application/json, text/event-stream'
    -H 'Content-Type: application/json'
  )
  if [[ -n "${session_id}" ]]; then
    headers+=(-H "Mcp-Session-Id: ${session_id}")
  fi
  curl -sS \
    "${headers[@]}" \
    -o "${output_file}" \
    -w '%{http_code}' \
    --data-binary "${body}" \
    "${MCP_URL}"
}

initialize_session() {
  local sequence="$1"
  local headers_file="${TEMP_DIR}/initialize-${sequence}.headers"
  local body_file="${TEMP_DIR}/initialize-${sequence}.body"
  local status
  status="$(
    curl -sS \
      -D "${headers_file}" \
      -H "Authorization: Bearer ${MCP_TOKEN}" \
      -H 'Accept: application/json, text/event-stream' \
      -H 'Content-Type: application/json' \
      -o "${body_file}" \
      -w '%{http_code}' \
      --data-binary \
      "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"open-simplepoint-phase2-verifier\",\"version\":\"1.0.${sequence}\"}}}" \
      "${MCP_URL}"
  )"
  [[ "${status}" == "200" ]] \
    || fail "MCP initialize returned HTTP ${status}"
  local session_id
  session_id="$(
    awk -F': *' '
      tolower($1) == "mcp-session-id" {
        sub(/\r$/, "", $2)
        print $2
        exit
      }
    ' "${headers_file}"
  )"
  [[ "${session_id}" =~ ^[A-Za-z0-9._~-]{1,512}$ ]] \
    || fail "MCP initialize did not return a safe session ID"
  local initialized_status
  initialized_status="$(
    mcp_request \
      "${session_id}" \
      '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}' \
      "${TEMP_DIR}/initialized-${sequence}.body"
  )"
  [[ "${initialized_status}" == "200" \
    || "${initialized_status}" == "202" ]] \
    || fail "MCP initialized notification returned HTTP ${initialized_status}"
  printf '%s' "${session_id}"
}

call_test_tool() {
  local session_id="$1"
  local sequence="$2"
  mcp_request \
    "${session_id}" \
    "{\"jsonrpc\":\"2.0\",\"id\":${sequence},\"method\":\"tools/call\",\"params\":{\"name\":\"${MCP_TOOL_NAME}\",\"arguments\":${MCP_TOOL_ARGUMENTS}}}" \
    "${TEMP_DIR}/tool-call-${sequence}.body"
}

wait_for_assignment() {
  local session_id="$1"
  local key
  key="$(session_directory_key "${session_id}")"
  local assignment=""
  for _ in $(seq 1 30); do
    assignment="$(redis_command GET "${key}")"
    if [[ -n "${assignment}" ]]; then
      printf '%s' "${assignment}"
      return 0
    fi
    sleep 1
  done
  fail "managed session assignment was not created"
}

wait_for_reassignment() {
  local session_id="$1"
  local previous="$2"
  local key
  key="$(session_directory_key "${session_id}")"
  for attempt in $(seq 1 60); do
    call_test_tool "${session_id}" "$((1000 + attempt))" >/dev/null || true
    local assignment
    assignment="$(redis_command GET "${key}")"
    if [[ -n "${assignment}" && "${assignment}" != "${previous}" ]]; then
      printf '%s' "${assignment}"
      return 0
    fi
    sleep 1
  done
  fail "session did not move away from the failed Runtime replica"
}

validate_runtime_nodes() {
  local required=2
  if [[ "${REQUIRE_DISTINCT_NODES}" == "false" ]]; then
    required=1
  fi
  local ready_nodes
  ready_nodes="$(
    postgres_query \
      "SELECT COUNT(*) FROM simpoint_ai_runtime_nodes WHERE deleted_at IS NULL AND status = 'READY' AND heartbeat_expires_at > CURRENT_TIMESTAMP"
  )"
  [[ "${ready_nodes}" =~ ^[0-9]+$ && "${ready_nodes}" -ge "${required}" ]] \
    || fail "at least ${required} READY Runtime node(s) are required"
}

main() {
  trap cleanup EXIT
  require_command docker
  require_command curl
  require_command jq
  require_command sha256sum
  require_command base64
  require_value "${MCP_URL}" PHASE2_MCP_URL
  require_value "${MCP_TOKEN}" PHASE2_MCP_TOKEN
  require_value "${MCP_SERVER_ID}" PHASE2_MCP_SERVER_ID
  require_value "${MCP_TOOL_NAME}" PHASE2_MCP_TOOL_NAME
  [[ "${MCP_SERVER_ID}" =~ ^[A-Za-z0-9_.-]{1,128}$ ]] \
    || fail "PHASE2_MCP_SERVER_ID contains unsafe characters"
  [[ "${MCP_SCOPE}" == "SYSTEM" || "${MCP_SCOPE}" == "TENANT" ]] \
    || fail "PHASE2_MCP_SCOPE must be SYSTEM or TENANT"
  [[ "${MCP_TOOL_NAME}" =~ ^[A-Za-z0-9_.:-]{1,128}$ ]] \
    || fail "PHASE2_MCP_TOOL_NAME contains unsafe characters"
  [[ "${SESSION_COUNT}" =~ ^[0-9]+$ && "${SESSION_COUNT}" -ge 8 \
    && "${SESSION_COUNT}" -le 128 ]] \
    || fail "PHASE2_SESSION_COUNT must be between 8 and 128"
  [[ "${REQUIRE_DISTINCT_NODES}" == "true"
      || "${REQUIRE_DISTINCT_NODES}" == "false" ]] \
    || fail "PHASE2_REQUIRE_DISTINCT_NODES must be true or false"
  if [[ "${ALLOW_NODE_DRAIN}" == "true"
      && "${REQUIRE_DISTINCT_NODES}" != "true" ]]; then
    fail "node drain requires PHASE2_REQUIRE_DISTINCT_NODES=true"
  fi
  jq -e 'type == "object"' <<< "${MCP_TOOL_ARGUMENTS}" >/dev/null \
    || fail "PHASE2_MCP_TOOL_ARGUMENTS must be one JSON object"
  if [[ "${REQUIRE_DISTINCT_NODES}" == "true" ]]; then
    docker node ls >/dev/null 2>&1 \
      || fail "run this verifier on a Swarm manager"
  fi

  TEMP_DIR="$(mktemp -d /tmp/open-simplepoint-phase2.XXXXXX)"
  if [[ -z "${REDIS_CONTAINER}" ]]; then
    REDIS_CONTAINER="$(service_container redis)"
  fi
  if [[ -z "${POSTGRES_CONTAINER}" ]]; then
    POSTGRES_CONTAINER="$(service_container postgres)"
  fi
  require_value "${REDIS_CONTAINER}" PHASE2_REDIS_CONTAINER
  require_value "${POSTGRES_CONTAINER}" PHASE2_POSTGRES_CONTAINER
  validate_runtime_nodes

  local assignments_file="${TEMP_DIR}/assignments"
  local sessions_file="${TEMP_DIR}/sessions"
  local workloads_file="${TEMP_DIR}/workloads"
  : > "${assignments_file}"
  : > "${sessions_file}"
  : > "${workloads_file}"
  for sequence in $(seq 1 "${SESSION_COUNT}"); do
    local session_id
    session_id="$(initialize_session "${sequence}")"
    local status
    status="$(call_test_tool "${session_id}" "$((sequence + 1))")"
    [[ "${status}" == "200" ]] \
      || fail "test Tool returned HTTP ${status}"
    if grep -Eq '"error"[[:space:]]*:' \
        "${TEMP_DIR}/tool-call-$((sequence + 1)).body"; then
      fail "test Tool returned an MCP protocol error"
    fi
    local assignment
    assignment="$(wait_for_assignment "${session_id}")"
    printf '%s\n' "${session_id}" >> "${sessions_file}"
    printf '%s\n' "${assignment}" >> "${assignments_file}"
    local repeated
    call_test_tool "${session_id}" "$((sequence + 100))" >/dev/null
    repeated="$(wait_for_assignment "${session_id}")"
    [[ "${repeated}" == "${assignment}" ]] \
      || fail "one MCP session changed replica while its assignment was healthy"
  done

  while read -r assignment; do
    local workload_id
    workload_id="$(assignment_workload "${assignment}")"
    [[ "${workload_id}" =~ ^[A-Za-z0-9_.-]{1,128}$ ]] \
      || fail "Runtime workload ID is unsafe"
    printf '%s\n' "${workload_id}" >> "${workloads_file}"
  done < "${assignments_file}"
  local unique_workloads
  unique_workloads="$(sort -u "${workloads_file}" | wc -l)"
  [[ "${unique_workloads}" -ge 2 ]] \
    || fail "new MCP sessions were not distributed across READY replicas"
  local nodes_file="${TEMP_DIR}/nodes"
  : > "${nodes_file}"
  while read -r workload_id; do
    postgres_query \
      "SELECT assigned_node_id FROM simpoint_ai_runtime_workloads WHERE deleted_at IS NULL AND runtime_workload_id = '${workload_id}'" \
      >> "${nodes_file}"
  done < "${workloads_file}"
  local unique_nodes
  unique_nodes="$(sed '/^$/d' "${nodes_file}" | sort -u | wc -l)"
  if [[ "${REQUIRE_DISTINCT_NODES}" == "true" ]]; then
    [[ "${unique_nodes}" -ge 2 ]] \
      || fail "managed MCP replicas were not spread across Runtime nodes"
  else
    [[ "${unique_nodes}" -ge 1 ]] \
      || fail "managed MCP replicas have no READY Runtime node"
  fi
  printf 'Validated %s sessions across %s workloads on %s Runtime nodes.\n' \
    "${SESSION_COUNT}" "${unique_workloads}" "${unique_nodes}"

  if [[ "${ALLOW_NODE_DRAIN}" != "true" ]]; then
    printf '%s\n' \
      'Load distribution passed. Set PHASE2_ALLOW_NODE_DRAIN=true to run fault injection.'
    return 0
  fi

  local selected_session
  local selected_assignment
  local selected_workload
  selected_session="$(head -n 1 "${sessions_file}")"
  selected_assignment="$(head -n 1 "${assignments_file}")"
  selected_workload="$(assignment_workload "${selected_assignment}")"
  [[ "${selected_workload}" =~ ^[A-Za-z0-9_.-]{1,128}$ ]] \
    || fail "selected Runtime workload ID is unsafe"
  local selected_node
  selected_node="$(
    postgres_query \
      "SELECT assigned_node_id FROM simpoint_ai_runtime_workloads WHERE deleted_at IS NULL AND runtime_workload_id = '${selected_workload}'"
  )"
  [[ "${selected_node}" =~ ^[A-Za-z0-9_.-]{1,64}$ ]] \
    || fail "selected Runtime node ID is unsafe"
  local node_role
  local runtime_label
  node_role="$(docker node inspect "${selected_node}" --format '{{.Spec.Role}}')"
  runtime_label="$(
    docker node inspect "${selected_node}" \
      --format '{{index .Spec.Labels "simplepoint.runtime"}}'
  )"
  [[ "${node_role}" == "worker" ]] \
    || fail "fault injection refuses to drain a Swarm manager"
  [[ "${runtime_label}" == "true" ]] \
    || fail "fault injection requires a dedicated simplepoint.runtime worker"

  DRAINED_NODE="${selected_node}"
  docker node update --availability drain "${DRAINED_NODE}" >/dev/null
  printf 'Drained Runtime worker %s; waiting for fenced reassignment.\n' \
    "${DRAINED_NODE}"
  local replacement_assignment
  replacement_assignment="$(
    wait_for_reassignment "${selected_session}" "${selected_assignment}"
  )"
  local replacement_workload
  replacement_workload="$(assignment_workload "${replacement_assignment}")"
  local replacement_node
  replacement_node="$(
    postgres_query \
      "SELECT assigned_node_id FROM simpoint_ai_runtime_workloads WHERE deleted_at IS NULL AND runtime_workload_id = '${replacement_workload}'"
  )"
  [[ -n "${replacement_node}" && "${replacement_node}" != "${DRAINED_NODE}" ]] \
    || fail "replacement workload did not move to another Runtime node"

  docker node update --availability active "${DRAINED_NODE}" >/dev/null
  DRAINED_NODE=""
  printf 'Phase 2 Runtime failover passed: %s -> %s.\n' \
    "${selected_node}" "${replacement_node}"
}

main "$@"
