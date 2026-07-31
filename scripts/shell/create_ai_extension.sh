#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
  cat <<'EOF'
Usage:
  create_ai_extension.sh tool <name> [--output <directory>]
  create_ai_extension.sh skill <name> \
    --server-id <id> --snapshot-id <id> \
    [--tool-name <name>] [--tool-alias <alias>] \
    [--output <directory>]
EOF
}

fail() {
  printf 'AI extension creation failed: %s\n' "$*" >&2
  exit 1
}

[[ $# -ge 2 ]] || {
  usage
  exit 1
}

extension_type="$1"
extension_name="$2"
shift 2

[[ "${extension_type}" == "tool" || "${extension_type}" == "skill" ]] \
  || fail "type must be tool or skill"
[[ "${extension_name}" =~ ^[a-z0-9][a-z0-9-]{0,63}$ ]] \
  || fail "name must match ^[a-z0-9][a-z0-9-]{0,63}$"

output_directory="${PWD}/${extension_name}"
server_id=""
snapshot_id=""
tool_name="echo"
tool_alias="echo"

while (($# > 0)); do
  case "$1" in
    --output)
      [[ $# -ge 2 ]] || fail "--output requires a directory"
      output_directory="$2"
      shift 2
      ;;
    --server-id)
      [[ $# -ge 2 ]] || fail "--server-id requires a value"
      server_id="$2"
      shift 2
      ;;
    --snapshot-id)
      [[ $# -ge 2 ]] || fail "--snapshot-id requires a value"
      snapshot_id="$2"
      shift 2
      ;;
    --tool-name)
      [[ $# -ge 2 ]] || fail "--tool-name requires a value"
      tool_name="$2"
      shift 2
      ;;
    --tool-alias)
      [[ $# -ge 2 ]] || fail "--tool-alias requires a value"
      tool_alias="$2"
      shift 2
      ;;
    *)
      fail "unknown argument: $1"
      ;;
  esac
done

if [[ "${extension_type}" == "skill" ]]; then
  [[ "${server_id}" =~ ^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$ ]] \
    || fail "--server-id is required and contains unsupported characters"
  [[ "${snapshot_id}" =~ ^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$ ]] \
    || fail "--snapshot-id is required and contains unsupported characters"
  [[ "${tool_name}" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$ ]] \
    || fail "--tool-name contains unsupported characters"
  [[ "${tool_alias}" =~ ^[a-z0-9][a-z0-9_.-]{0,63}$ ]] \
    || fail "--tool-alias contains unsupported characters"
fi

case "${extension_type}" in
  tool)
    template_directory="${ROOT_DIR}/templates/ai-extensions/mcp-tool-typescript"
    ;;
  skill)
    template_directory="${ROOT_DIR}/templates/ai-extensions/skill"
    ;;
esac

destination="$(realpath -m "${output_directory}")"
template_root="$(realpath "${ROOT_DIR}/templates/ai-extensions")"
case "${destination}" in
  "${template_root}"|"${template_root}"/*)
    fail "output must not overwrite the source templates"
    ;;
esac
if [[ -e "${destination}" ]] \
    && [[ -n "$(find "${destination}" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
  fail "output directory is not empty: ${destination}"
fi

mkdir -p "${destination}"
cp -R "${template_directory}/." "${destination}/"

while IFS= read -r -d '' file; do
  sed -i \
    -e "s|__EXTENSION_NAME__|${extension_name}|g" \
    -e "s|__SERVER_ID__|${server_id}|g" \
    -e "s|__SNAPSHOT_ID__|${snapshot_id}|g" \
    -e "s|__TOOL_NAME__|${tool_name}|g" \
    -e "s|__TOOL_ALIAS__|${tool_alias}|g" \
    "${file}"
done < <(find "${destination}" -type f -print0)

find "${destination}" -type f -name '*.sh' -exec chmod 0755 {} +
printf 'Created independent %s project at %s\n' \
  "${extension_type}" \
  "${destination}"
