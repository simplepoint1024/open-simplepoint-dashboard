#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
  cat <<'EOF'
Usage:
  create_ai_extension.sh tool <name> [--output <directory>]
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

[[ "${extension_type}" == "tool" ]] \
  || fail "type must be tool"
[[ "${extension_name}" =~ ^[a-z0-9][a-z0-9-]{0,63}$ ]] \
  || fail "name must match ^[a-z0-9][a-z0-9-]{0,63}$"

output_directory="${PWD}/${extension_name}"
while (($# > 0)); do
  case "$1" in
    --output)
      [[ $# -ge 2 ]] || fail "--output requires a directory"
      output_directory="$2"
      shift 2
      ;;
    *)
      fail "unknown argument: $1"
      ;;
  esac
done

template_directory="${ROOT_DIR}/templates/ai-extensions/mcp-tool-typescript"

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
    "${file}"
done < <(find "${destination}" -type f -print0)

find "${destination}" -type f -name '*.sh' -exec chmod 0755 {} +
printf 'Created independent %s project at %s\n' \
  "${extension_type}" \
  "${destination}"
