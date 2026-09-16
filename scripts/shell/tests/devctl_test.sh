#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
TEST_DIR="$(mktemp -d)"
SERVER_PID=""

cleanup() {
  [[ -z "${SERVER_PID}" ]] || kill "${SERVER_PID}" 2>/dev/null || true
  rm -rf "${TEST_DIR}"
}
trap cleanup EXIT

mkdir -p "${TEST_DIR}/state" "${TEST_DIR}/config"
cp -R "${ROOT_DIR}/config/consul/base" "${TEST_DIR}/config/base"
cp -R "${ROOT_DIR}/config/consul/profiles" "${TEST_DIR}/config/profiles"

python3 - "${TEST_DIR}/port" <<'PY' &
import http.server
import json
import sys
import urllib.parse

store = {}
port_file = sys.argv[1]

class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/v1/status/leader":
            body = b'"127.0.0.1:8300"'
            self.send_response(200)
        elif parsed.path.startswith("/v1/kv/"):
            key = urllib.parse.unquote(parsed.path[len("/v1/kv/"):])
            if key not in store:
                self.send_response(404)
                self.end_headers()
                return
            body = store[key]
            self.send_response(200)
        else:
            self.send_response(404)
            self.end_headers()
            return
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_PUT(self):
        parsed = urllib.parse.urlparse(self.path)
        key = urllib.parse.unquote(parsed.path[len("/v1/kv/"):])
        length = int(self.headers.get("Content-Length", "0"))
        store[key] = self.rfile.read(length)
        body = json.dumps(True).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_DELETE(self):
        parsed = urllib.parse.urlparse(self.path)
        key = urllib.parse.unquote(parsed.path[len("/v1/kv/"):])
        store.pop(key, None)
        body = json.dumps(True).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
with open(port_file, "w", encoding="utf-8") as handle:
    handle.write(str(server.server_port))
server.serve_forever()
PY
SERVER_PID=$!

for _ in {1..50}; do [[ -s "${TEST_DIR}/port" ]] && break; sleep 0.1; done
[[ -s "${TEST_DIR}/port" ]] || { echo "mock Consul failed to start" >&2; exit 1; }
MOCK_PORT="$(<"${TEST_DIR}/port")"

sed "s#http://127.0.0.1:8500#http://127.0.0.1:${MOCK_PORT}#" \
  "${ROOT_DIR}/config/dev.env.example" >"${TEST_DIR}/state/local.env"

curl --noproxy '*' -fsS --request PUT --data 'legacy-local-value' \
  "http://127.0.0.1:${MOCK_PORT}/v1/kv/simplepoint/config/host-local/application.properties" >/dev/null
curl --noproxy '*' -fsS --request PUT --data '{"profile":"local"}' \
  "http://127.0.0.1:${MOCK_PORT}/v1/kv/simplepoint/bootstrap/local/status" >/dev/null

run_dev() {
  SIMPLEPOINT_STATE_DIR="${TEST_DIR}/state" \
  SIMPLEPOINT_CONFIG_ROOT="${TEST_DIR}/config" \
    "${ROOT_DIR}/dev" "$@"
}

first_apply="$(run_dev config apply)"
grep -q '更新 0' <<<"${first_apply}"
grep -q '删除旧 local 键 2' <<<"${first_apply}"
[[ -f "${TEST_DIR}/state/dev.env" && ! -e "${TEST_DIR}/state/local.env" ]]
find "${TEST_DIR}/state/backups" -type f \
  -path '*/simplepoint/config/host-local/application.properties' | grep -q .
[[ "$(curl --noproxy '*' -sS -o /dev/null -w '%{http_code}' \
  "http://127.0.0.1:${MOCK_PORT}/v1/kv/simplepoint/config/host-local/application.properties?raw")" == 404 ]]
second_apply="$(run_dev config apply)"
grep -q '新增 0，更新 0' <<<"${second_apply}"
run_dev config verify >/dev/null

target="${TEST_DIR}/config/profiles/dev/simplepoint/config/host-dev/application.properties"
printf '\nsimplepoint.devctl.test=true\n' >>"${target}"
plan="$(run_dev config plan)"
grep -q '~ simplepoint/config/host-dev/application.properties' <<<"${plan}"
run_dev config apply >/dev/null
find "${TEST_DIR}/state/backups" -type f \
  -path '*/simplepoint/config/host-dev/application.properties' | grep -q .
run_dev config verify >/dev/null

required=(authorization common host ai mcp-gateway agent-runtime workflow-runtime)
for service in "${required[@]}"; do
  [[ -f "${TEST_DIR}/config/profiles/dev/simplepoint/config/${service}-dev/application.properties" ]] \
    || { echo "missing dev Consul overlay for ${service}" >&2; exit 1; }
done

echo "devctl tests passed"
