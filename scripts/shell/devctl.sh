#!/usr/bin/env bash
set -euo pipefail
umask 077

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STATE_DIR="${SIMPLEPOINT_STATE_DIR:-${ROOT_DIR}/.simplepoint}"
DEFAULT_ENV_FILE="${STATE_DIR}/dev.env"
LEGACY_ENV_FILE="${STATE_DIR}/local.env"
ENV_FILE="${SIMPLEPOINT_ENV_FILE:-${DEFAULT_ENV_FILE}}"
RUN_DIR="${STATE_DIR}/run"
LOG_DIR="${STATE_DIR}/logs"
BACKUP_DIR="${STATE_DIR}/backups"
CONFIG_ROOT="${SIMPLEPOINT_CONFIG_ROOT:-${ROOT_DIR}/config/consul}"
DEV_PROFILE="dev"

declare -A SERVICE_PORTS=(
  [authorization]=9000 [common]=7000 [host]=8080 [auditing]=6000 [dna]=2777
  [ai]=2888 [mcp-gateway]=2890 [agent-runtime]=2894 [workflow-runtime]=2895
)

CORE_SERVICES=(authorization common host)
AI_SERVICES=(authorization common ai mcp-gateway agent-runtime workflow-runtime host)
FULL_SERVICES=(authorization common auditing dna ai mcp-gateway agent-runtime workflow-runtime host)

say() { printf '%s\n' "$*"; }
ok() { printf '  [OK] %s\n' "$*"; }
warn() { printf '  [WARN] %s\n' "$*" >&2; }
fail() { printf '  [FAIL] %s\n' "$*" >&2; }
die() { printf '错误: %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
SimplePoint 本地开发工具（不依赖 Docker）

用法:
  ./dev doctor [core|ai|full]
  ./dev init [core|ai|full]
  ./dev config plan|apply|verify
  ./dev database create|verify|extensions
  ./dev up [core|ai|full] [--backend-only]
  ./dev status
  ./dev logs <service> [--follow]
  ./dev restart <service> [--backend-only]
  ./dev down [core|ai|full]

首次使用:
  cp config/dev.env.example .simplepoint/dev.env
  # 编辑 .simplepoint/dev.env 中的 PostgreSQL/Redis/Consul 地址
  ./dev doctor ai
  ./dev init ai
  ./dev up ai
EOF
}

ensure_state_dirs() {
  mkdir -p "${STATE_DIR}" "${RUN_DIR}" "${LOG_DIR}" "${BACKUP_DIR}"
}

ensure_env_file() {
  ensure_state_dirs
  if [[ "${ENV_FILE}" == "${DEFAULT_ENV_FILE}" && ! -f "${ENV_FILE}" && -f "${LEGACY_ENV_FILE}" ]]; then
    mv "${LEGACY_ENV_FILE}" "${ENV_FILE}"
    say "已将旧配置 ${LEGACY_ENV_FILE} 迁移为 ${ENV_FILE}。"
  fi
  if [[ ! -f "${ENV_FILE}" ]]; then
    cp "${ROOT_DIR}/config/dev.env.example" "${ENV_FILE}"
    say "已创建 ${ENV_FILE}。默认值适用于本机标准端口；如有不同请先编辑该文件。"
  fi
  chmod 600 "${ENV_FILE}"
}

load_env() {
  ensure_env_file
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a

  : "${CONSUL_HTTP_ADDR:=http://127.0.0.1:8500}"
  : "${CONSUL_HTTP_TOKEN:=}"
  : "${SIMPLEPOINT_GRADLE_EXECUTABLE:=gradle}"
  : "${SIMPLEPOINT_POSTGRES_HOST:=127.0.0.1}"
  : "${SIMPLEPOINT_POSTGRES_PORT:=5432}"
  : "${SIMPLEPOINT_POSTGRES_DATABASE:=simplepoint}"
  : "${SIMPLEPOINT_POSTGRES_USERNAME:=postgres}"
  : "${SIMPLEPOINT_POSTGRES_PASSWORD:=postgres}"
  : "${SIMPLEPOINT_REDIS_HOST:=127.0.0.1}"
  : "${SIMPLEPOINT_REDIS_PORT:=6379}"
  : "${SIMPLEPOINT_REDIS_PASSWORD:=}"

  CONSUL_HTTP_ADDR="${CONSUL_HTTP_ADDR%/}"
  local consul_authority="${CONSUL_HTTP_ADDR#*://}"
  consul_authority="${consul_authority%%/*}"
  export SIMPLEPOINT_CONSUL_HOST="${consul_authority%%:*}"
  if [[ "${consul_authority}" == *:* ]]; then
    export SIMPLEPOINT_CONSUL_PORT="${consul_authority##*:}"
  else
    export SIMPLEPOINT_CONSUL_PORT=8500
  fi
  if [[ -n "${CONSUL_HTTP_TOKEN}" ]]; then
    export SPRING_CLOUD_CONSUL_CONFIG_ACL_TOKEN="${CONSUL_HTTP_TOKEN}"
    export SPRING_CLOUD_CONSUL_DISCOVERY_ACL_TOKEN="${CONSUL_HTTP_TOKEN}"
  fi
}

consul_curl() {
  local -a args=("$@")
  if [[ -n "${CONSUL_HTTP_TOKEN:-}" ]]; then
    curl --noproxy '*' -H "X-Consul-Token: ${CONSUL_HTTP_TOKEN}" "${args[@]}"
  else
    curl --noproxy '*' "${args[@]}"
  fi
}

config_files() {
  find "${CONFIG_ROOT}/base" "${CONFIG_ROOT}/profiles/${DEV_PROFILE}" \
    -type f -name '*.properties' -print | LC_ALL=C sort
}

config_key_for_file() {
  local file="$1"
  if [[ "${file}" == "${CONFIG_ROOT}/base/"* ]]; then
    printf '%s' "${file#${CONFIG_ROOT}/base/}"
  else
    printf '%s' "${file#${CONFIG_ROOT}/profiles/${DEV_PROFILE}/}"
  fi
}

legacy_config_keys() {
  local file key
  while IFS= read -r file; do
    key="$(config_key_for_file "${file}")"
    printf '%s\n' "${key/-dev\//-local\/}"
  done < <(find "${CONFIG_ROOT}/profiles/${DEV_PROFILE}" -type f -name '*.properties' -print | LC_ALL=C sort)
  printf '%s\n' 'simplepoint/bootstrap/local/status'
}

read_consul_key() {
  local key="$1" output="$2"
  local status
  status="$(consul_curl -sS -o "${output}" -w '%{http_code}' \
    "${CONSUL_HTTP_ADDR}/v1/kv/${key}?raw" || true)"
  [[ "${status}" == "200" ]]
}

put_consul_key() {
  local key="$1" file="$2"
  consul_curl -fsS --request PUT --data-binary "@${file}" \
    "${CONSUL_HTTP_ADDR}/v1/kv/${key}" >/dev/null
}

delete_consul_key() {
  local key="$1"
  consul_curl -fsS --request DELETE "${CONSUL_HTTP_ADDR}/v1/kv/${key}" >/dev/null
}

config_digest() {
  local hash_command
  if command -v sha256sum >/dev/null 2>&1; then
    hash_command=sha256sum
  elif command -v shasum >/dev/null 2>&1; then
    hash_command='shasum -a 256'
  else
    die "缺少 sha256sum 或 shasum"
  fi
  while IFS= read -r file; do
    # shellcheck disable=SC2086
    ${hash_command} "${file}"
  done < <(config_files) | LC_ALL=C sort | {
    # shellcheck disable=SC2086
    ${hash_command} | awk '{print $1}'
  }
}

config_action() {
  local action="${1:-plan}"
  [[ "${action}" =~ ^(plan|apply|verify)$ ]] || die "config 仅支持 plan、apply、verify"
  load_env
  consul_curl -fsS "${CONSUL_HTTP_ADDR}/v1/status/leader" >/dev/null \
    || die "无法连接 Consul: ${CONSUL_HTTP_ADDR}"

  local changed=0 unchanged=0 missing=0 deleted=0 file key current backup_stamp
  backup_stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  while IFS= read -r file; do
    key="$(config_key_for_file "${file}")"
    current="$(mktemp)"
    if read_consul_key "${key}" "${current}"; then
      if cmp -s "${file}" "${current}"; then
        unchanged=$((unchanged + 1))
        printf '  = %s\n' "${key}"
      else
        changed=$((changed + 1))
        printf '  ~ %s\n' "${key}"
        if [[ "${action}" == "apply" ]]; then
          mkdir -p "${BACKUP_DIR}/${backup_stamp}/$(dirname "${key}")"
          cp "${current}" "${BACKUP_DIR}/${backup_stamp}/${key}"
          put_consul_key "${key}" "${file}"
        fi
      fi
    else
      missing=$((missing + 1))
      printf '  + %s\n' "${key}"
      [[ "${action}" != "apply" ]] || put_consul_key "${key}" "${file}"
    fi
    rm -f "${current}"
  done < <(config_files)

  while IFS= read -r key; do
    current="$(mktemp)"
    if read_consul_key "${key}" "${current}"; then
      deleted=$((deleted + 1))
      printf '  - %s\n' "${key}"
      if [[ "${action}" == "apply" ]]; then
        mkdir -p "${BACKUP_DIR}/${backup_stamp}/$(dirname "${key}")"
        cp "${current}" "${BACKUP_DIR}/${backup_stamp}/${key}"
        delete_consul_key "${key}"
      fi
    fi
    rm -f "${current}"
  done < <(legacy_config_keys)

  if [[ "${action}" == "apply" ]]; then
    local marker_file git_revision
    marker_file="$(mktemp)"
    git_revision="$(git -C "${ROOT_DIR}" rev-parse --short HEAD 2>/dev/null || printf unknown)"
    printf '{"status":"ready","profile":"dev","configDigest":"%s","gitRevision":"%s","updatedAt":"%s"}\n' \
      "$(config_digest)" "${git_revision}" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >"${marker_file}"
    put_consul_key "simplepoint/bootstrap/dev/status" "${marker_file}"
    rm -f "${marker_file}"
    say "Consul 配置已同步：新增 ${missing}，更新 ${changed}，删除旧 local 键 ${deleted}，未变 ${unchanged}。"
    (( changed + deleted == 0 )) || say "旧值已备份到 ${BACKUP_DIR}/${backup_stamp}。"
  elif [[ "${action}" == "verify" ]]; then
    if (( changed + missing + deleted > 0 )); then
      die "Consul 配置不一致：缺少 ${missing}，变化 ${changed}，遗留 local 键 ${deleted}。请执行 ./dev config apply"
    fi
    say "Consul 配置校验通过，共 ${unchanged} 项。"
  else
    say "计划结果：新增 ${missing}，更新 ${changed}，删除旧 local 键 ${deleted}，未变 ${unchanged}。"
  fi
}

validate_database_name() {
  [[ "${SIMPLEPOINT_POSTGRES_DATABASE}" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] \
    || die "数据库名只能包含字母、数字、下划线，且不能以数字开头"
}

psql_run() {
  local database="$1"
  shift
  PGPASSWORD="${SIMPLEPOINT_POSTGRES_PASSWORD}" psql \
    -h "${SIMPLEPOINT_POSTGRES_HOST}" -p "${SIMPLEPOINT_POSTGRES_PORT}" \
    -U "${SIMPLEPOINT_POSTGRES_USERNAME}" -d "${database}" "$@"
}

database_action() {
  local action="${1:-verify}"
  [[ "${action}" =~ ^(create|verify|extensions)$ ]] || die "database 仅支持 create、verify、extensions"
  load_env
  command -v psql >/dev/null 2>&1 || die "缺少 psql 客户端"
  validate_database_name

  if [[ "${action}" == "create" ]]; then
    if psql_run "${SIMPLEPOINT_POSTGRES_DATABASE}" -Atqc 'select 1' >/dev/null 2>&1; then
      ok "数据库 ${SIMPLEPOINT_POSTGRES_DATABASE} 已存在"
    else
      say "正在创建数据库 ${SIMPLEPOINT_POSTGRES_DATABASE}..."
      psql_run postgres -v ON_ERROR_STOP=1 \
        -c "CREATE DATABASE \"${SIMPLEPOINT_POSTGRES_DATABASE}\"" >/dev/null \
        || die "创建数据库失败；请确认账号有 CREATEDB 权限，或手工创建后重试"
      ok "数据库已创建"
    fi
  elif [[ "${action}" == "extensions" ]]; then
    psql_run "${SIMPLEPOINT_POSTGRES_DATABASE}" -v ON_ERROR_STOP=1 \
      -c 'CREATE EXTENSION IF NOT EXISTS vector' \
      -c 'CREATE EXTENSION IF NOT EXISTS pg_trgm' >/dev/null \
      || die "扩展初始化失败；AI 模块需要 PostgreSQL 安装 pgvector，并允许当前账号 CREATE EXTENSION"
    ok "vector、pg_trgm 扩展可用"
  else
    psql_run "${SIMPLEPOINT_POSTGRES_DATABASE}" -Atqc 'select 1' >/dev/null \
      || die "无法连接数据库 ${SIMPLEPOINT_POSTGRES_DATABASE}"
    ok "PostgreSQL 数据库连接正常"
  fi
}

redis_ping() {
  if command -v redis-cli >/dev/null 2>&1; then
    local -a args=(-h "${SIMPLEPOINT_REDIS_HOST}" -p "${SIMPLEPOINT_REDIS_PORT}" --no-auth-warning)
    [[ "$(REDISCLI_AUTH="${SIMPLEPOINT_REDIS_PASSWORD}" redis-cli "${args[@]}" ping 2>/dev/null)" == "PONG" ]]
  elif [[ -z "${SIMPLEPOINT_REDIS_PASSWORD}" ]] && command -v timeout >/dev/null 2>&1; then
    REDIS_CHECK_HOST="${SIMPLEPOINT_REDIS_HOST}" REDIS_CHECK_PORT="${SIMPLEPOINT_REDIS_PORT}" \
      timeout 3 bash -c 'exec 3<>/dev/tcp/$REDIS_CHECK_HOST/$REDIS_CHECK_PORT; printf "*1\r\n$4\r\nPING\r\n" >&3; IFS= read -r reply <&3; [[ "$reply" == +PONG* ]]'
  else
    return 1
  fi
}

port_in_use() {
  local port="$1"
  if command -v ss >/dev/null 2>&1; then
    ss -ltnH "sport = :${port}" 2>/dev/null | grep -q .
  else
    timeout 1 bash -c "</dev/tcp/127.0.0.1/${port}" >/dev/null 2>&1
  fi
}

service_pid() {
  local file="${RUN_DIR}/$1.pid"
  if [[ -f "${file}" ]]; then
    tr -dc '0-9' <"${file}"
  fi
  return 0
}

service_running() {
  local pid
  pid="$(service_pid "$1")"
  [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null
}

service_healthy() {
  local port="${SERVICE_PORTS[$1]:-}"
  [[ -n "${port}" ]] && curl --noproxy '*' -fsS --max-time 2 \
    "http://127.0.0.1:${port}/actuator/health" >/dev/null 2>&1
}

service_discovered() {
  local service="$1" port="${SERVICE_PORTS[$1]:-}" response
  [[ -n "${port}" ]] || return 1
  response="$(consul_curl -fsS --max-time 3 \
    "${CONSUL_HTTP_ADDR}/v1/health/service/${service}?passing=true" 2>/dev/null)" \
    || return 1
  grep -Eq \
    "\"ServiceID\"[[:space:]]*:[[:space:]]*\"${service}-local-${port}\"" \
    <<<"${response}"
}

service_registered() {
  local service="$1" port="${SERVICE_PORTS[$1]:-}" response
  [[ -n "${port}" && -n "${CONSUL_HTTP_ADDR:-}" ]] || return 1
  response="$(consul_curl -fsS --max-time 3 \
    "${CONSUL_HTTP_ADDR}/v1/catalog/service/${service}" 2>/dev/null)" \
    || return 1
  grep -Eq \
    "\"ServiceID\"[[:space:]]*:[[:space:]]*\"${service}-local-${port}\"" \
    <<<"${response}"
}

services_for_profile() {
  case "${1:-core}" in
    core) printf '%s\n' "${CORE_SERVICES[@]}" ;;
    ai) printf '%s\n' "${AI_SERVICES[@]}" ;;
    full) printf '%s\n' "${FULL_SERVICES[@]}" ;;
    *) die "未知启动组合 '$1'，可选 core、ai、full" ;;
  esac
}

doctor() {
  local profile="${1:-core}" failures=0 warnings=0 java_version major service port
  load_env
  say "检查 SimplePoint ${profile} 本地开发环境..."

  if command -v java >/dev/null 2>&1; then
    java_version="$(java -version 2>&1 | head -1)"
    major="$(printf '%s' "${java_version}" | sed -E 's/.*version "([0-9]+).*/\1/')"
    if [[ "${major}" =~ ^[0-9]+$ ]] && (( major >= 21 )); then ok "JDK ${major}"; else fail "需要 JDK 21+ (${java_version})"; failures=$((failures + 1)); fi
  else fail "未找到 java"; failures=$((failures + 1)); fi

  if [[ "${SIMPLEPOINT_GRADLE_EXECUTABLE}" == "./gradlew" && -f "${ROOT_DIR}/gradlew" ]]; then
    ok "Gradle Wrapper"
  elif command -v "${SIMPLEPOINT_GRADLE_EXECUTABLE}" >/dev/null 2>&1; then
    ok "Gradle: ${SIMPLEPOINT_GRADLE_EXECUTABLE}"
  else
    fail "Gradle 不可用: ${SIMPLEPOINT_GRADLE_EXECUTABLE}"
    failures=$((failures + 1))
  fi
  command -v curl >/dev/null 2>&1 && ok "curl" || { fail "缺少 curl"; failures=$((failures + 1)); }
  command -v psql >/dev/null 2>&1 && ok "psql" || { fail "缺少 psql 客户端"; failures=$((failures + 1)); }

  if consul_curl -fsS --max-time 3 "${CONSUL_HTTP_ADDR}/v1/status/leader" 2>/dev/null | grep -q ':'; then
    ok "Consul ${CONSUL_HTTP_ADDR}"
  else fail "Consul 不可用: ${CONSUL_HTTP_ADDR}"; failures=$((failures + 1)); fi

  if command -v psql >/dev/null 2>&1 && psql_run "${SIMPLEPOINT_POSTGRES_DATABASE}" -Atqc 'select 1' >/dev/null 2>&1; then
    ok "PostgreSQL ${SIMPLEPOINT_POSTGRES_HOST}:${SIMPLEPOINT_POSTGRES_PORT}/${SIMPLEPOINT_POSTGRES_DATABASE}"
  else warn "目标数据库尚不可用；./dev init 会尝试创建"; warnings=$((warnings + 1)); fi

  if redis_ping; then ok "Redis ${SIMPLEPOINT_REDIS_HOST}:${SIMPLEPOINT_REDIS_PORT}"; else fail "Redis 不可用（有密码时需安装 redis-cli）"; failures=$((failures + 1)); fi

  while IFS= read -r service; do
    port="${SERVICE_PORTS[$service]}"
    if port_in_use "${port}"; then
      if service_running "${service}"; then ok "端口 ${port} 由 ${service} 使用"; else fail "端口 ${port} 已被其他进程占用 (${service})"; failures=$((failures + 1)); fi
    fi
  done < <(services_for_profile "${profile}")

  if [[ "${profile}" == "ai" || "${profile}" == "full" ]]; then
    if command -v psql >/dev/null 2>&1 && psql_run "${SIMPLEPOINT_POSTGRES_DATABASE}" -Atqc \
      "select count(*) from pg_available_extensions where name in ('vector','pg_trgm')" 2>/dev/null | grep -q '^2$'; then
      ok "AI 所需 PostgreSQL 扩展可安装"
    else fail "AI 需要 PostgreSQL pgvector（vector）和 pg_trgm 扩展"; failures=$((failures + 1)); fi
  fi

  if (( failures > 0 )); then
    die "环境检查失败 ${failures} 项，警告 ${warnings} 项"
  fi
  say "环境检查通过（警告 ${warnings} 项）。"
}

init_environment() {
  local profile="${1:-core}"
  load_env
  database_action create
  if [[ "${profile}" == "ai" || "${profile}" == "full" ]]; then database_action extensions; fi
  config_action apply
  config_action verify
  say "本地环境初始化完成。下一步: ./dev up ${profile}"
}

start_service() {
  local service="$1" backend_only="${2:-false}" port pid log_file
  port="${SERVICE_PORTS[$service]}"
  log_file="${LOG_DIR}/${service}.log"
  if service_running "${service}"; then
    ok "${service} 已运行 (PID $(service_pid "${service}"))"
    return 0
  fi
  if port_in_use "${port}"; then die "${service} 无法启动：端口 ${port} 已被占用"; fi

  local -a gradle_args=("--no-daemon" ":simplepoint-services:simplepoint-service-${service}:run")
  [[ "${backend_only}" != "true" ]] || gradle_args+=("-Psimplepoint.frontend.skip=true")
  say "启动 ${service}（日志: ${log_file}）..."
  (
    cd "${ROOT_DIR}"
    export SPRING_PROFILES_ACTIVE=dev
    if command -v setsid >/dev/null 2>&1; then
      exec setsid "${SIMPLEPOINT_GRADLE_EXECUTABLE}" "${gradle_args[@]}" >>"${log_file}" 2>&1
    else
      exec "${SIMPLEPOINT_GRADLE_EXECUTABLE}" "${gradle_args[@]}" >>"${log_file}" 2>&1
    fi
  ) &
  pid=$!
  printf '%s\n' "${pid}" >"${RUN_DIR}/${service}.pid"

  local deadline=$((SECONDS + 300))
  while (( SECONDS < deadline )); do
    if service_healthy "${service}" && service_discovered "${service}"; then
      ok "${service} 已就绪并完成服务发现: http://127.0.0.1:${port}"
      return 0
    fi
    if ! kill -0 "${pid}" 2>/dev/null; then
      tail -n 60 "${log_file}" >&2 || true
      die "${service} 启动失败，完整日志见 ${log_file}"
    fi
    sleep 2
  done
  tail -n 60 "${log_file}" >&2 || true
  die "等待 ${service} 健康检查超时，日志见 ${log_file}"
}

up() {
  local profile="${1:-core}" backend_only="${2:-false}" service
  load_env
  config_action verify
  database_action verify
  redis_ping || die "Redis 不可用"
  while IFS= read -r service; do start_service "${service}" "${backend_only}"; done < <(services_for_profile "${profile}")
  say "SimplePoint ${profile} 已启动。入口: http://127.0.0.1:8080"
  say "查看状态: ./dev status；查看日志: ./dev logs host --follow；停止: ./dev down ${profile}"
}

stop_service() {
  local service="$1" pid port
  pid="$(service_pid "${service}")"
  if [[ -z "${pid}" ]]; then return 0; fi
  port="${SERVICE_PORTS[$service]}"
  if kill -0 "${pid}" 2>/dev/null; then
    say "停止 ${service} (PID ${pid})..."
    kill -- "-${pid}" 2>/dev/null || kill "${pid}" 2>/dev/null || true
  fi

  # Gradle's `run` task starts the application in a separate daemon session. The
  # launcher PID can therefore disappear before Spring has closed its port and
  # deregistered from Consul. Starting the replacement during that window lets
  # the old instance deregister the new instance because both use the same ID.
  local deadline=$((SECONDS + 45))
  while (( SECONDS < deadline )); do
    if ! port_in_use "${port}" && ! service_registered "${service}"; then
      break
    fi
    sleep 1
  done

  if port_in_use "${port}"; then
    die "${service} 未在 45 秒内释放端口 ${port}，已停止重启以避免覆盖仍运行的进程"
  fi
  if service_registered "${service}"; then
    warn "${service} 已退出但 Consul 注册仍残留，清理实例 ${service}-local-${port}"
    consul_curl -fsS --request PUT \
      "${CONSUL_HTTP_ADDR}/v1/agent/service/deregister/${service}-local-${port}" >/dev/null \
      || die "无法清理 ${service} 的 Consul 注册"
  fi
  rm -f "${RUN_DIR}/${service}.pid"
}

down() {
  local profile="${1:-full}" service
  mapfile -t selected < <(services_for_profile "${profile}")
  for ((i=${#selected[@]}-1; i>=0; i--)); do stop_service "${selected[$i]}"; done
  say "SimplePoint ${profile} 服务已停止。"
}

status() {
  ensure_state_dirs
  local service pid health
  printf '%-20s %-9s %-8s %s\n' SERVICE PID HEALTH URL
  for service in "${FULL_SERVICES[@]}"; do
    pid="$(service_pid "${service}")"
    if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
      if service_healthy "${service}"; then health=UP; else health=STARTING; fi
      printf '%-20s %-9s %-8s http://127.0.0.1:%s\n' "${service}" "${pid}" "${health}" "${SERVICE_PORTS[$service]}"
    else
      printf '%-20s %-9s %-8s %s\n' "${service}" '-' DOWN '-'
    fi
  done
}

logs() {
  local service="${1:-}" follow="${2:-}"
  [[ -n "${SERVICE_PORTS[$service]:-}" ]] || die "请指定有效服务名"
  local file="${LOG_DIR}/${service}.log"
  [[ -f "${file}" ]] || die "日志尚不存在: ${file}"
  if [[ "${follow}" == "--follow" || "${follow}" == "-f" ]]; then tail -n 200 -f "${file}"; else tail -n 200 "${file}"; fi
}

restart() {
  local service="${1:-}" backend_only="${2:-false}"
  [[ -n "${SERVICE_PORTS[$service]:-}" ]] || die "请指定有效服务名"
  load_env
  stop_service "${service}"
  start_service "${service}" "${backend_only}"
}

main() {
  local command="${1:-help}"
  shift || true
  case "${command}" in
    help|-h|--help) usage ;;
    doctor) doctor "${1:-core}" ;;
    init) init_environment "${1:-core}" ;;
    config) config_action "${1:-plan}" ;;
    database) database_action "${1:-verify}" ;;
    up)
      local profile="${1:-core}" backend_only=false
      [[ $# -eq 0 ]] || shift
      [[ "${1:-}" != "--backend-only" ]] || backend_only=true
      up "${profile}" "${backend_only}"
      ;;
    status) status ;;
    logs) logs "${1:-}" "${2:-}" ;;
    restart)
      local restart_backend_only=false
      [[ "${2:-}" != "--backend-only" ]] || restart_backend_only=true
      restart "${1:-}" "${restart_backend_only}"
      ;;
    down) down "${1:-full}" ;;
    *) usage; die "未知命令: ${command}" ;;
  esac
}

main "$@"
