#!/usr/bin/env bash
set -euo pipefail

artifact_reference="${OCI_REFERENCE:-}"
manifest_file="${SKILL_MANIFEST_FILE:-skill.json}"
source_url="${OCI_SOURCE:-https://github.com/example/__EXTENSION_NAME__}"
revision="${OCI_REVISION:-unknown}"
version="${OCI_VERSION:-1.0.0}"

fail() {
  printf 'Skill packaging failed: %s\n' "$*" >&2
  exit 1
}

for command_name in jq oras sha256sum; do
  command -v "${command_name}" >/dev/null 2>&1 \
    || fail "${command_name} is required"
done

[[ -n "${artifact_reference}" ]] \
  || fail "OCI_REFERENCE must be repository:tag"
[[ -f "${manifest_file}" ]] \
  || fail "Skill Manifest does not exist: ${manifest_file}"
npm test

package_dir="$(mktemp -d /tmp/open-simplepoint-skill-package.XXXXXX)"
cleanup() {
  rm -rf -- "${package_dir}"
}
trap cleanup EXIT

manifest_digest="sha256:$(sha256sum "${manifest_file}" | awk '{print $1}')"
config_file="${package_dir}/config.json"
jq -cn \
  --arg manifestDigest "${manifest_digest}" \
  '{
    schemaVersion: "1.0",
    manifestMediaType:
      "application/vnd.simplepoint.skill.manifest.v1+json",
    manifestDigest: $manifestDigest
  }' > "${config_file}"

oras push "${artifact_reference}" \
  --artifact-type application/vnd.simplepoint.skill.v1+json \
  --config \
    "${config_file}:application/vnd.simplepoint.skill.config.v1+json" \
  --annotation "org.opencontainers.image.source=${source_url}" \
  --annotation "org.opencontainers.image.revision=${revision}" \
  --annotation "org.opencontainers.image.version=${version}" \
  --annotation "org.opencontainers.image.licenses=Apache-2.0" \
  "${manifest_file}:application/vnd.simplepoint.skill.manifest.v1+json"

oras manifest fetch --descriptor "${artifact_reference}" \
  | jq -r '"Published \(.digest)"'
