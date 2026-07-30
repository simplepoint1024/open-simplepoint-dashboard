#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${SKILL_E2E_BASE_URL:-http://127.0.0.1:8080/ai}"
AUTHORIZATION="${SKILL_E2E_AUTHORIZATION:-}"
COOKIE="${SKILL_E2E_COOKIE:-}"
CONTEXT_ID="${SKILL_E2E_CONTEXT_ID:-}"
ARTIFACT_REFERENCE="${SKILL_E2E_ARTIFACT_REFERENCE:-}"
ARTIFACT_DIGEST="${SKILL_E2E_ARTIFACT_DIGEST:-}"
MANIFEST_FILE="${SKILL_E2E_MANIFEST_FILE:-}"
INPUT="${SKILL_E2E_INPUT:-}"
EXPECTED_TEXT="${SKILL_E2E_EXPECTED_TEXT:-open-simplepoint-skill-e2e-ok}"
IDEMPOTENCY_KEY="${SKILL_E2E_IDEMPOTENCY_KEY:-}"
WAIT_ATTEMPTS="${SKILL_E2E_WAIT_ATTEMPTS:-60}"
AUTO_APPROVE="${SKILL_E2E_AUTO_APPROVE:-false}"
VERIFY_PAUSE_RESUME="${SKILL_E2E_VERIFY_PAUSE_RESUME:-false}"
EXPECTED_SUCCEEDED_STEPS="${SKILL_E2E_EXPECT_SUCCEEDED_STEPS:-}"
EXPECTED_SKIPPED_STEPS="${SKILL_E2E_EXPECT_SKIPPED_STEPS:-}"

TEMP_DIR=""

if [[ -z "${INPUT}" ]]; then
  INPUT='{"message":"open-simplepoint-skill-e2e-ok"}'
fi

fail() {
  printf 'Skill Workflow E2E verification failed: %s\n' "$*" >&2
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

main() {
  require_command curl
  require_command jq
  require_command sed

  [[ -n "${AUTHORIZATION}" || -n "${COOKIE}" ]] \
    || fail "SKILL_E2E_AUTHORIZATION or SKILL_E2E_COOKIE is required"
  [[ -n "${ARTIFACT_REFERENCE}" ]] \
    || fail "SKILL_E2E_ARTIFACT_REFERENCE is required"
  [[ "${ARTIFACT_DIGEST}" =~ ^sha256:[a-f0-9]{64}$ ]] \
    || fail "SKILL_E2E_ARTIFACT_DIGEST must be a sha256 digest"
  [[ -f "${MANIFEST_FILE}" ]] \
    || fail "SKILL_E2E_MANIFEST_FILE must point to a readable JSON file"
  jq -e 'type == "object"' "${MANIFEST_FILE}" >/dev/null \
    || fail "Skill Manifest must be one JSON object"
  jq -e 'type == "object"' <<< "${INPUT}" >/dev/null \
    || fail "SKILL_E2E_INPUT must be one JSON object"
  [[ "${WAIT_ATTEMPTS}" =~ ^[1-9][0-9]*$ ]] \
    || fail "SKILL_E2E_WAIT_ATTEMPTS must be a positive integer"
  [[ -z "${EXPECTED_SUCCEEDED_STEPS}"
      || "${EXPECTED_SUCCEEDED_STEPS}" =~ ^[0-9]+$ ]] \
    || fail "SKILL_E2E_EXPECT_SUCCEEDED_STEPS must be a non-negative integer"
  [[ -z "${EXPECTED_SKIPPED_STEPS}"
      || "${EXPECTED_SKIPPED_STEPS}" =~ ^[0-9]+$ ]] \
    || fail "SKILL_E2E_EXPECT_SKIPPED_STEPS must be a non-negative integer"

  TEMP_DIR="$(mktemp -d /tmp/open-simplepoint-skill-e2e.XXXXXX)"
  trap 'rm -rf -- "${TEMP_DIR}"' EXIT

  local code version title
  code="$(jq -er '.metadata.name | select(type == "string" and length > 0)' \
    "${MANIFEST_FILE}")"
  version="$(jq -er '.metadata.version | select(type == "string" and length > 0)' \
    "${MANIFEST_FILE}")"
  title="$(jq -r '.metadata.title // .metadata.name' "${MANIFEST_FILE}")"
  if [[ -z "${IDEMPOTENCY_KEY}" ]]; then
    IDEMPOTENCY_KEY="skill-e2e-$(date -u +%Y%m%d%H%M%S)-$$"
  fi

  local skills skill_id create_skill
  skills="$(api GET "/workbench/skills?page=0&size=200")"
  skill_id="$(jq -r --arg code "${code}" \
    '.content[]? | select(.code == $code) | .id' <<< "${skills}" | head -n 1)"
  if [[ -z "${skill_id}" ]]; then
    create_skill="$(jq -cn \
      --arg code "${code}" \
      --arg name "${title}" \
      '{
        code: $code,
        name: $name,
        description: "Automated signed OCI Skill Workflow verification",
        enabled: true
      }')"
    skill_id="$(api POST "/workbench/skills" "${create_skill}" | jq -r '.id')"
    [[ -n "${skill_id}" && "${skill_id}" != "null" ]] \
      || fail "Skill creation returned no ID"
    printf 'Created Skill %s\n' "${skill_id}"
  else
    printf 'Reusing Skill %s\n' "${skill_id}"
  fi

  local versions version_id existing_digest create_version
  versions="$(api GET "/workbench/skills/${skill_id}/versions?page=0&size=200")"
  version_id="$(jq -r --arg version "${version}" \
    '.content[]? | select(.version == $version) | .id' <<< "${versions}" \
    | head -n 1)"
  if [[ -z "${version_id}" ]]; then
    create_version="$(jq -cn \
      --arg version "${version}" \
      --arg artifactReference "${ARTIFACT_REFERENCE}" \
      --arg artifactDigest "${ARTIFACT_DIGEST}" \
      --slurpfile manifest "${MANIFEST_FILE}" \
      '{
        version: $version,
        artifactReference: $artifactReference,
        artifactDigest: $artifactDigest,
        manifest: $manifest[0]
      }')"
    local created_version
    created_version="$(api POST \
      "/workbench/skills/${skill_id}/versions" \
      "${create_version}")"
    version_id="$(jq -r '.id' <<< "${created_version}")"
    jq -e \
      '.artifactSignatureRequired == false
        or .artifactSignatureVerified == true' \
      <<< "${created_version}" >/dev/null \
      || fail "Skill Artifact did not pass signature verification"
    printf 'Created immutable Skill version %s\n' "${version_id}"
  else
    existing_digest="$(jq -r --arg version "${version}" \
      '.content[]? | select(.version == $version) | .artifactDigest' \
      <<< "${versions}" | head -n 1)"
    [[ "${existing_digest}" == "${ARTIFACT_DIGEST}" ]] \
      || fail "Existing Skill version uses a different Artifact digest"
    printf 'Reusing immutable Skill version %s\n' "${version_id}"
  fi

  local published
  published="$(api POST \
    "/workbench/skills/${skill_id}/versions/${version_id}/publish" \
    '{}')"
  jq -e '.status == "PUBLISHED"' <<< "${published}" >/dev/null \
    || fail "Skill version did not enter PUBLISHED"
  printf 'Published Skill version %s\n' "${version_id}"

  local execution_body execution execution_id duplicate_id
  execution_body="$(jq -cn \
    --arg idempotencyKey "${IDEMPOTENCY_KEY}" \
    --argjson input "${INPUT}" \
    '{idempotencyKey: $idempotencyKey, input: $input}')"
  execution="$(api POST \
    "/workbench/skills/${skill_id}/executions" \
    "${execution_body}")"
  execution_id="$(jq -r '.id' <<< "${execution}")"
  [[ -n "${execution_id}" && "${execution_id}" != "null" ]] \
    || fail "Skill execution returned no ID"

  duplicate_id="$(api POST \
    "/workbench/skills/${skill_id}/executions" \
    "${execution_body}" | jq -r '.id')"
  [[ "${duplicate_id}" == "${execution_id}" ]] \
    || fail "Idempotent submission returned a different execution"

  local submitted_status controlled
  submitted_status="$(jq -r '.status' <<< "${execution}")"
  if [[ "${submitted_status}" == "WAITING_APPROVAL" ]] \
      && [[ "${VERIFY_PAUSE_RESUME}" == "true" ]]; then
    controlled="$(api POST \
      "/workbench/skills/${skill_id}/executions/${execution_id}/pause" \
      '{"reason":"Automated approval pause checkpoint verification"}')"
    [[ "$(jq -r '.status' <<< "${controlled}")" == "PAUSED" ]] \
      || fail "Approval-waiting execution did not pause"
    controlled="$(api POST \
      "/workbench/skills/${skill_id}/executions/${execution_id}/resume" \
      '{}')"
    [[ "$(jq -r '.status' <<< "${controlled}")" == "WAITING_APPROVAL" ]] \
      || fail "Unapproved execution did not resume to WAITING_APPROVAL"
    printf 'Verified approval-waiting pause and resume\n'
  fi
  if [[ "${submitted_status}" == "WAITING_APPROVAL" ]] \
      && [[ "${AUTO_APPROVE}" == "true" ]]; then
    controlled="$(api POST \
      "/workbench/skills/${skill_id}/executions/${execution_id}/approve" \
      '{"comment":"Automated Skill Workflow E2E approval"}')"
    case "$(jq -r '.status' <<< "${controlled}")" in
      PENDING|RUNNING|SUCCEEDED)
        printf 'Approved Skill execution %s\n' "${execution_id}"
        ;;
      *)
        fail "Approved execution did not enter an executable state"
        ;;
    esac
  fi

  local current status
  for attempt in $(seq 1 "${WAIT_ATTEMPTS}"); do
    current="$(api GET \
      "/workbench/skills/${skill_id}/executions/${execution_id}")"
    status="$(jq -r '.status' <<< "${current}")"
    if [[ "${status}" == "SUCCEEDED" ]]; then
      jq -e '
        (.steps | length) > 0
        and any(.steps[]; .status == "SUCCEEDED")
        and all(.steps[];
          .status == "SUCCEEDED" or .status == "SKIPPED"
        )
      ' <<< "${current}" >/dev/null \
        || fail "Skill execution has an incomplete workflow step"
      if [[ -n "${EXPECTED_SUCCEEDED_STEPS}" ]]; then
        [[ "$(jq '[.steps[] | select(.status == "SUCCEEDED")] | length' \
          <<< "${current}")" == "${EXPECTED_SUCCEEDED_STEPS}" ]] \
          || fail "Skill execution has an unexpected succeeded step count"
      fi
      if [[ -n "${EXPECTED_SKIPPED_STEPS}" ]]; then
        [[ "$(jq '[.steps[] | select(.status == "SKIPPED")] | length' \
          <<< "${current}")" == "${EXPECTED_SKIPPED_STEPS}" ]] \
          || fail "Skill execution has an unexpected skipped step count"
      fi
      if [[ -n "${EXPECTED_TEXT}" ]] \
          && ! jq -e --arg expected "${EXPECTED_TEXT}" \
            '.. | strings | select(. == $expected)' \
            <<< "${current}" >/dev/null; then
        fail "Skill execution does not contain expected text: ${EXPECTED_TEXT}"
      fi
      printf 'Skill Workflow E2E verification passed\n'
      printf 'Resources retained: skill=%s version=%s execution=%s\n' \
        "${skill_id}" "${version_id}" "${execution_id}"
      return 0
    fi
    if [[ "${status}" == "FAILED"
        || "${status}" == "REJECTED"
        || "${status}" == "CANCELLED" ]]; then
      jq . <<< "${current}" >&2
      fail "Skill execution entered ${status}"
    fi
    printf 'Waiting for Skill execution: attempt=%s status=%s\n' \
      "${attempt}" "${status}" >&2
    sleep 1
  done
  fail "Skill execution did not finish"
}

main "$@"
