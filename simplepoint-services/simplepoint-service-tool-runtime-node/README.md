# Open SimplePoint Tool Runtime Node

`tool-runtime-node` is an independent Go process that owns the Docker Engine
boundary for OCI-packaged MCP Servers. It does not load tool code into the AI
service or MCP Gateway.

The current runtime slices provide:

- authenticated node status and image preparation APIs;
- outbound control-plane registration, capacity/label heartbeats, and graceful
  offline reporting;
- stable node identities with per-process generation fencing, automatic
  re-registration, and retry when the control plane is temporarily unavailable;
- digest-pinned OCI image references and registry allowlists;
- fail-closed, pre-pull Cosign signature, signed SBOM, and vulnerability
  admission through an independent mTLS image verifier;
- MCP/OCI label compatibility validation;
- fenced start, inspect, stop, and delete lifecycle operations;
- lease replacement that removes an older container generation before starting
  the higher fencing token;
- forced non-root execution, read-only root filesystems, `cap-drop ALL`,
  `no-new-privileges`, a validated custom seccomp policy, an optional named
  AppArmor profile, PID/CPU/memory limits, bounded tmpfs, and no network by
  default;
- a fenced Streamable HTTP session facade that bridges the Gateway to one
  persistent stdio MCP connection inside a managed OCI workload;
- scope-resolved encrypted secret references transported only over mTLS,
  materialized as bounded `0400` files, mounted read-only, and removed with the
  workload;
- optional policy-bound HTTP/HTTPS egress through an independent proxy, with
  short-lived signed DNS allowlist capabilities and no direct Internet route;
- ownership labels so the node cannot mutate containers it did not create.

Workload environment variables remain limited to non-sensitive configuration.
Names that look like credentials, passwords, private keys, secrets, or tokens
are rejected; sensitive values use the file-mounted Secret Broker path.

The Docker socket is a root-equivalent control boundary. The node must not mount
it directly. Deploy a fixed-version, least-privilege Docker Socket Proxy on each
trusted runtime node, expose the proxy only on an internal node network, and
point `DOCKER_HOST` at that proxy. Keep port `2891` on the control-plane network
and never publish the proxy port to an untrusted network.

The Compose-only development PKI generator reuses an identity while its node
ID, DNS SAN, store password, and remaining lifetime are valid. Set
`SIMPLEPOINT_RUNTIME_PKI_FORCE_RENEW=true` only when AI and Runtime containers
will be recreated together. Production deployments must use an external CA or
secret system instead of this generator.

## Control-plane connection

The node initiates its control connection to the AI service; the AI service
does not need inbound access to the node for registration and heartbeats.
Configure:

```text
SIMPLEPOINT_TOOL_RUNTIME_NODE_ID
SIMPLEPOINT_TOOL_RUNTIME_CONTROL_PLANE_URL
SIMPLEPOINT_TOOL_RUNTIME_ADVERTISE_URL
SIMPLEPOINT_TOOL_RUNTIME_NODE_LABELS
SIMPLEPOINT_TOOL_RUNTIME_MAX_WORKLOADS
SIMPLEPOINT_TOOL_RUNTIME_MAX_REPORTED_IMAGE_DIGESTS
SIMPLEPOINT_TOOL_RUNTIME_REGISTRATION_RETRY
SIMPLEPOINT_TOOL_RUNTIME_MTLS_ENABLED
SIMPLEPOINT_TOOL_RUNTIME_TLS_CERT_FILE
SIMPLEPOINT_TOOL_RUNTIME_TLS_KEY_FILE
SIMPLEPOINT_TOOL_RUNTIME_TLS_CA_FILE
SIMPLEPOINT_TOOL_RUNTIME_TLS_SERVER_NAME
SIMPLEPOINT_TOOL_RUNTIME_TLS_CONTROL_SERVER_NAME
SIMPLEPOINT_TOOL_RUNTIME_SECRET_ROOT
SIMPLEPOINT_TOOL_RUNTIME_SECRET_VOLUME
SIMPLEPOINT_TOOL_RUNTIME_SECRET_MOUNT_TARGET
SIMPLEPOINT_TOOL_RUNTIME_EGRESS_ENABLED
SIMPLEPOINT_TOOL_RUNTIME_EGRESS_NETWORK
SIMPLEPOINT_TOOL_RUNTIME_EGRESS_PROXY_URL
SIMPLEPOINT_TOOL_RUNTIME_EGRESS_SIGNING_KEY
SIMPLEPOINT_TOOL_RUNTIME_SUPPLY_CHAIN_ENABLED
SIMPLEPOINT_TOOL_RUNTIME_IMAGE_VERIFIER_URL
SIMPLEPOINT_TOOL_RUNTIME_IMAGE_VERIFIER_SERVER_NAME
SIMPLEPOINT_TOOL_RUNTIME_IMAGE_VERIFIER_IDENTITY
SIMPLEPOINT_TOOL_RUNTIME_IMAGE_VERIFIER_TIMEOUT
SIMPLEPOINT_TOOL_RUNTIME_SECCOMP_PROFILE
SIMPLEPOINT_TOOL_RUNTIME_SECCOMP_REQUIRED
SIMPLEPOINT_TOOL_RUNTIME_APPARMOR_PROFILE
SIMPLEPOINT_TOOL_RUNTIME_APPARMOR_ENABLED
SIMPLEPOINT_TOOL_RUNTIME_APPARMOR_REQUIRED
SIMPLEPOINT_TOOL_RUNTIME_TLS_EXPECTED_GATEWAY_IDENTITY
SIMPLEPOINT_TOOL_RUNTIME_MCP_REQUEST_TIMEOUT
SIMPLEPOINT_TOOL_RUNTIME_MAX_MCP_MESSAGE_BYTES
```

The control plane responds with the accepted generation, heartbeat interval,
and lease expiry. A restarted process receives a newer generation, so a stale
process cannot renew or take a node identity back. In mTLS mode the node
certificate must contain the exact URI SAN
`spiffe://open-simplepoint/runtime-node/{nodeId}`. The AI peer certificate must
contain `spiffe://open-simplepoint/ai-control-plane`; both outbound registration
and inbound dispatch use TLS 1.3.

## Private API

With mTLS enabled, all `/internal/v1/**` requests require the verified AI
control-plane URI SAN. Legacy shared-token headers are ignored. Token
authentication remains available only as an explicit compatibility mode when
mTLS is disabled. Every workload lifecycle request also requires
`X-SimplePoint-Runtime-Lease-Id` and
`X-SimplePoint-Runtime-Fencing-Token`. The start body must carry the same
values. Requests for an older or mismatched generation return HTTP `409`.

```text
GET    /health
GET    /internal/v1/node/status
POST   /internal/v1/images/prepare
POST   /internal/v1/workloads
GET    /internal/v1/workloads/{workloadId}
POST   /internal/v1/workloads/{workloadId}/stop
DELETE /internal/v1/workloads/{workloadId}
POST   /mcp/v1/workloads/{workloadId}
GET    /mcp/v1/workloads/{workloadId}
DELETE /mcp/v1/workloads/{workloadId}
```

The lifecycle routes accept only the AI control-plane identity. The MCP routes
accept only the exact Gateway URI SAN
`spiffe://open-simplepoint/mcp-gateway`; they also require the active workload
lease and fencing headers. The MCP facade implements standard Streamable HTTP
session semantics while the managed container speaks newline-delimited MCP
JSON-RPC over stdio.

An MCP OCI image uses normal OCI annotations plus SimplePoint package
extensions. These labels describe packaging, not new MCP protocol methods:

```text
org.opencontainers.image.title
io.simplepoint.mcp.transport=streamable-http|stdio
io.simplepoint.mcp.protocol-version=2025-11-25
```

The AI control plane performs capacity-aware assignment, lease renewal,
observation, retry, deadline stop, image prewarming, elastic replicas,
scale-to-zero, and final container reclamation. A Redis-backed hashed session
directory and Rendezvous Hash now distribute managed MCP sessions across READY
replicas. Stale node heartbeats, changed lease fences, and connection failures
remove the old assignment before a later request can select another Runtime
node; a failed Tool request is never replayed automatically.

## Managed MCP end-to-end verification

Build the repository smoke MCP image, then run the control-plane verifier with
an authenticated platform or tenant session:

```bash
docker build \
  -t somesimpled/open-simplepoint-mcp-echo:e2e-local \
  simplepoint-services/simplepoint-service-tool-runtime-node/testdata/stdio-mcp-server

MCP_E2E_IMAGE_REFERENCE=somesimpled/open-simplepoint-mcp-echo:e2e-local \
MCP_E2E_AUTHORIZATION="Bearer ${CI_ACCESS_TOKEN}" \
./scripts/shell/verify_managed_mcp_e2e.sh
```

`MCP_E2E_COOKIE` may be used instead of `MCP_E2E_AUTHORIZATION` when testing a
browser/BFF session. Set `MCP_E2E_CONTEXT_ID` for a specific tenant
authorization context. The verifier creates a unique managed Server and Pool,
waits for a READY Workload, discovers and calls `echo`, replaces the Runtime
replica through the redeploy API, calls the Tool again, and safely deletes all
temporary resources. Set `MCP_E2E_KEEP_RESOURCES=true` only for manual
inspection.
