#!/bin/sh
set -eu

umask 077

output_dir="${SIMPLEPOINT_RUNTIME_PKI_OUTPUT_DIR:-/pki}"
node_id="${SIMPLEPOINT_TOOL_RUNTIME_NODE_ID:-local-runtime-node}"
node_server_name="${SIMPLEPOINT_TOOL_RUNTIME_TLS_SERVER_NAME:-tool-runtime}"
verifier_server_name="${SIMPLEPOINT_TOOL_IMAGE_VERIFIER_TLS_SERVER_NAME:-tool-image-verifier}"
store_password="${SIMPLEPOINT_RUNTIME_PKI_STORE_PASSWORD:?Runtime PKI store password is required}"
valid_days="${SIMPLEPOINT_RUNTIME_PKI_VALID_DAYS:-30}"
renew_before_seconds="${SIMPLEPOINT_RUNTIME_PKI_RENEW_BEFORE_SECONDS:-86400}"
force_renew="${SIMPLEPOINT_RUNTIME_PKI_FORCE_RENEW:-false}"
work_dir="$(mktemp -d)"

cleanup() {
  rm -rf "${work_dir}"
}
trap cleanup EXIT

case "${node_id}" in
  *[!A-Za-z0-9_.-]*|"")
    echo "Runtime node ID is invalid" >&2
    exit 1
    ;;
esac

case "${node_server_name}" in
  *[!A-Za-z0-9.-]*|""|.*|*.)
    echo "Runtime node TLS server name is invalid" >&2
    exit 1
    ;;
esac

case "${verifier_server_name}" in
  *[!A-Za-z0-9.-]*|""|.*|*.)
    echo "Tool image verifier TLS server name is invalid" >&2
    exit 1
    ;;
esac

case "${output_dir}" in
  ""|"/")
    echo "Runtime PKI output directory is unsafe" >&2
    exit 1
    ;;
esac

case "${renew_before_seconds}" in
  *[!0-9]*|"")
    echo "Runtime PKI renewal window is invalid" >&2
    exit 1
    ;;
esac

case "${force_renew}" in
  true|false)
    ;;
  *)
    echo "Runtime PKI force-renew flag must be true or false" >&2
    exit 1
    ;;
esac

if [ "${#store_password}" -lt 12 ]; then
  echo "Runtime PKI store password must contain at least 12 characters" >&2
  exit 1
fi

existing_pki_is_usable() {
  [ "${force_renew}" = "false" ] || return 1
  for required_file in \
    "${output_dir}/ai/identity.p12" \
    "${output_dir}/ai/trust.p12" \
    "${output_dir}/gateway/identity.p12" \
    "${output_dir}/gateway/trust.p12" \
    "${output_dir}/node/tls.crt" \
    "${output_dir}/node/tls.key" \
    "${output_dir}/node/ca.crt" \
    "${output_dir}/verifier/tls.crt" \
    "${output_dir}/verifier/tls.key" \
    "${output_dir}/verifier/ca.crt"; do
    [ -r "${required_file}" ] || return 1
  done

  node_san="$(
    openssl x509 \
      -in "${output_dir}/node/tls.crt" \
      -noout \
      -ext subjectAltName 2>/dev/null
  )" || return 1
  case "${node_san}" in
    *"DNS:${node_server_name}"*"URI:spiffe://open-simplepoint/runtime-node/${node_id}"*)
      ;;
    *)
      return 1
      ;;
  esac
  openssl x509 \
    -checkend "${renew_before_seconds}" \
    -noout \
    -in "${output_dir}/node/tls.crt" >/dev/null 2>&1 || return 1

  verifier_san="$(
    openssl x509 \
      -in "${output_dir}/verifier/tls.crt" \
      -noout \
      -ext subjectAltName 2>/dev/null
  )" || return 1
  case "${verifier_san}" in
    *"DNS:${verifier_server_name}"*"URI:spiffe://open-simplepoint/tool-image-verifier"*)
      ;;
    *)
      return 1
      ;;
  esac
  openssl x509 \
    -checkend "${renew_before_seconds}" \
    -noout \
    -in "${output_dir}/verifier/tls.crt" >/dev/null 2>&1 || return 1

  ai_san="$(
    openssl pkcs12 \
      -in "${output_dir}/ai/identity.p12" \
      -clcerts \
      -nokeys \
      -passin "pass:${store_password}" 2>/dev/null \
      | openssl x509 -noout -ext subjectAltName 2>/dev/null
  )" || return 1
  case "${ai_san}" in
    *"DNS:ai"*"URI:spiffe://open-simplepoint/ai-control-plane"*)
      ;;
    *)
      return 1
      ;;
  esac
  openssl pkcs12 \
    -in "${output_dir}/ai/identity.p12" \
    -clcerts \
    -nokeys \
    -passin "pass:${store_password}" 2>/dev/null \
    | openssl x509 \
        -checkend "${renew_before_seconds}" \
        -noout >/dev/null 2>&1 || return 1
  keytool \
    -list \
    -storetype PKCS12 \
    -keystore "${output_dir}/ai/trust.p12" \
    -storepass "${store_password}" >/dev/null 2>&1 || return 1

  gateway_san="$(
    openssl pkcs12 \
      -in "${output_dir}/gateway/identity.p12" \
      -clcerts \
      -nokeys \
      -passin "pass:${store_password}" 2>/dev/null \
      | openssl x509 -noout -ext subjectAltName 2>/dev/null
  )" || return 1
  case "${gateway_san}" in
    *"DNS:mcp-gateway"*"URI:spiffe://open-simplepoint/mcp-gateway"*)
      ;;
    *)
      return 1
      ;;
  esac
  openssl pkcs12 \
    -in "${output_dir}/gateway/identity.p12" \
    -clcerts \
    -nokeys \
    -passin "pass:${store_password}" 2>/dev/null \
    | openssl x509 \
        -checkend "${renew_before_seconds}" \
        -noout >/dev/null 2>&1 || return 1
  keytool \
    -list \
    -storetype PKCS12 \
    -keystore "${output_dir}/gateway/trust.p12" \
    -storepass "${store_password}" >/dev/null 2>&1 || return 1
}

if existing_pki_is_usable; then
  echo "Reusing valid Runtime development PKI for node ${node_id}"
  exit 0
fi

openssl req \
  -x509 \
  -newkey rsa:3072 \
  -sha256 \
  -days "${valid_days}" \
  -nodes \
  -subj "/CN=Open SimplePoint Runtime Development CA" \
  -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" \
  -keyout "${work_dir}/ca.key" \
  -out "${work_dir}/ca.crt"

issue_certificate() {
  name="$1"
  common_name="$2"
  san="$3"

  openssl req \
    -new \
    -newkey rsa:3072 \
    -nodes \
    -sha256 \
    -subj "/CN=${common_name}" \
    -addext "subjectAltName=${san}" \
    -addext "extendedKeyUsage=serverAuth,clientAuth" \
    -addext "keyUsage=critical,digitalSignature,keyEncipherment" \
    -keyout "${work_dir}/${name}.key" \
    -out "${work_dir}/${name}.csr"
  openssl x509 \
    -req \
    -sha256 \
    -days "${valid_days}" \
    -copy_extensions copy \
    -in "${work_dir}/${name}.csr" \
    -CA "${work_dir}/ca.crt" \
    -CAkey "${work_dir}/ca.key" \
    -CAcreateserial \
    -out "${work_dir}/${name}.crt"
}

issue_certificate \
  "ai" \
  "ai" \
  "DNS:ai,URI:spiffe://open-simplepoint/ai-control-plane"
issue_certificate \
  "node" \
  "${node_id}" \
  "DNS:${node_server_name},URI:spiffe://open-simplepoint/runtime-node/${node_id}"
issue_certificate \
  "gateway" \
  "mcp-gateway" \
  "DNS:mcp-gateway,URI:spiffe://open-simplepoint/mcp-gateway"
issue_certificate \
  "verifier" \
  "${verifier_server_name}" \
  "DNS:${verifier_server_name},URI:spiffe://open-simplepoint/tool-image-verifier"

openssl pkcs12 \
  -export \
  -name "ai-runtime" \
  -inkey "${work_dir}/ai.key" \
  -in "${work_dir}/ai.crt" \
  -certfile "${work_dir}/ca.crt" \
  -passout "pass:${store_password}" \
  -out "${work_dir}/ai.p12"

openssl pkcs12 \
  -export \
  -name "gateway-runtime" \
  -inkey "${work_dir}/gateway.key" \
  -in "${work_dir}/gateway.crt" \
  -certfile "${work_dir}/ca.crt" \
  -passout "pass:${store_password}" \
  -out "${work_dir}/gateway.p12"

keytool \
  -importcert \
  -noprompt \
  -storetype PKCS12 \
  -alias "runtime-ca" \
  -file "${work_dir}/ca.crt" \
  -keystore "${work_dir}/trust.p12" \
  -storepass "${store_password}"

rm -rf \
  "${output_dir}/ai" \
  "${output_dir}/gateway" \
  "${output_dir}/node" \
  "${output_dir}/verifier"
install -d -m 0500 -o 10001 -g 10001 "${output_dir}/ai"
install -d -m 0500 -o 10001 -g 10001 "${output_dir}/gateway"
install -d -m 0500 -o 65532 -g 65532 "${output_dir}/node"
install -d -m 0500 -o 65532 -g 65532 "${output_dir}/verifier"
install -m 0400 -o 10001 -g 10001 \
  "${work_dir}/ai.p12" "${output_dir}/ai/identity.p12"
install -m 0400 -o 10001 -g 10001 \
  "${work_dir}/trust.p12" "${output_dir}/ai/trust.p12"
install -m 0400 -o 10001 -g 10001 \
  "${work_dir}/gateway.p12" "${output_dir}/gateway/identity.p12"
install -m 0400 -o 10001 -g 10001 \
  "${work_dir}/trust.p12" "${output_dir}/gateway/trust.p12"
install -m 0400 -o 65532 -g 65532 \
  "${work_dir}/node.crt" "${output_dir}/node/tls.crt"
install -m 0400 -o 65532 -g 65532 \
  "${work_dir}/node.key" "${output_dir}/node/tls.key"
install -m 0400 -o 65532 -g 65532 \
  "${work_dir}/ca.crt" "${output_dir}/node/ca.crt"
install -m 0400 -o 65532 -g 65532 \
  "${work_dir}/verifier.crt" "${output_dir}/verifier/tls.crt"
install -m 0400 -o 65532 -g 65532 \
  "${work_dir}/verifier.key" "${output_dir}/verifier/tls.key"
install -m 0400 -o 65532 -g 65532 \
  "${work_dir}/ca.crt" "${output_dir}/verifier/ca.crt"

echo "Ephemeral Runtime development PKI generated for node ${node_id}"
