# Open SimplePoint Tool Runtime Node

The implementation plan for turning this node into a descriptor-driven,
general-purpose MCP runtime is maintained in
[UNIVERSAL_MCP_RUNTIME_ROADMAP.md](./UNIVERSAL_MCP_RUNTIME_ROADMAP.md). It also
defines the upstream-image and end-to-end acceptance requirements for GitHub,
Filesystem, Git, PostgreSQL, Docker, and Playwright MCP servers.

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
- Runtime Profile-owned transport, entrypoint, command, arguments, working
  directory, ordinary environment, and session policy dispatch;
- optional MCP/OCI label compatibility hints for legacy unbound Pools;
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
- a trusted static Generic Launcher, mounted outside third-party images, that
  reads `ENV_AT_EXEC` values from `0400` files and `exec`s the original image
  process without exposing secret values in Docker inspect or process args;
- optional policy-bound HTTP/HTTPS egress through an independent proxy, with
  short-lived signed DNS allowlist capabilities and no direct Internet route;
- platform-managed workspace/volume mounts and bounded ephemeral/tmpfs storage,
  with Browser and Workspace profile invariants revalidated at the node;
- exact `host:port` TCP/internal-service routes resolved only through
  administrator-owned isolated Docker networks, plus an unexposed Streamable
  HTTP container reverse proxy;
- ownership labels so the node cannot mutate containers it did not create.

Workload Docker environment variables remain limited to non-sensitive
configuration. Names that look like credentials, passwords, private keys,
secrets, or tokens are rejected. `ENV_AT_EXEC` is implemented only inside the
trusted launcher after it reads file-mounted Secret Broker material, so secret
values never enter Docker `Config.Env` or command arguments.

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
SIMPLEPOINT_TOOL_RUNTIME_LAUNCHER_ENABLED
SIMPLEPOINT_TOOL_RUNTIME_LAUNCHER_SOURCE
SIMPLEPOINT_TOOL_RUNTIME_LAUNCHER_MOUNT_TARGET
SIMPLEPOINT_TOOL_RUNTIME_EGRESS_ENABLED
SIMPLEPOINT_TOOL_RUNTIME_EGRESS_NETWORK
SIMPLEPOINT_TOOL_RUNTIME_EGRESS_PROXY_URL
SIMPLEPOINT_TOOL_RUNTIME_EGRESS_SIGNING_KEY
SIMPLEPOINT_TOOL_RUNTIME_TCP_EGRESS_ROUTES
SIMPLEPOINT_TOOL_RUNTIME_INTERNAL_SERVICE_ROUTES
SIMPLEPOINT_TOOL_RUNTIME_TRANSPORT_NETWORK
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

Endpoint routes use a comma-separated `endpoint=network` format, for example:

```text
SIMPLEPOINT_TOOL_RUNTIME_INTERNAL_SERVICE_ROUTES=postgres:5432=open-simplepoint-runtime-postgres
SIMPLEPOINT_TOOL_RUNTIME_TCP_EGRESS_ROUTES=db.example.com:5432=open-simplepoint-runtime-db-route
```

The request may select only an exact configured endpoint. The Runtime never
accepts a network name from a Profile. Each route network must be internal,
attachable, and contain only the approved service or a separately operated
TCP policy gateway for that exact destination. Compose provides a dedicated
`runtime-postgres` network shared only by PostgreSQL and admitted DATA
workloads. `HTTP_EGRESS` remains proxy-mediated; `bridge` is not a policy mode.

Managed storage sources are deterministic platform volume IDs, never host
paths. `WORKSPACE_RO` and object snapshots are forced read-only. Browser
profiles require a bounded `/dev/shm` tmpfs; Workspace profiles require a
managed workspace; DATA profiles require an exact TCP or internal-service
endpoint. Streamable HTTP containers join the internal transport network and
their port/path are accessed only by the Runtime reverse proxy, never published
on the host.

`process.userMode` defaults to `RUNTIME_DEFAULT` (`65532:65532`). Use
`IMAGE_DEFAULT` only for an audited upstream image that cannot run under the
fixed identity; named image users are resolved from the immutable image before
managed-volume ownership is initialized. `process.initializationCommand` is an
optional shell-free argv executed only when a managed volume is first created,
with no network, a read-only root filesystem, dropped capabilities, and bounded
resources. It is intended for operations such as `git -C /workspace init`, not
for package installation or mutable image setup.

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
POST   /internal/v1/mcp/probe
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

`POST /internal/v1/mcp/probe` is a control-plane-only admission operation. It
starts a disposable digest-pinned stdio or Streamable HTTP workload, performs
`initialize`, sends
`notifications/initialized`, and probes `tools/list`, `resources/list`, and
`prompts/list`. The container and its secret material are reclaimed on both
success and failure. Only a sanitized capability report is returned; process
output, environment values, and secret values are never included. Publishing
an OCI stdio Runtime Profile stores this report together with its deterministic
hash in the immutable deployment revision.

An MCP OCI image may use normal OCI annotations plus legacy SimplePoint package
hints:

```text
org.opencontainers.image.title
io.simplepoint.mcp.transport=streamable-http|stdio
io.simplepoint.mcp.protocol-version=2025-11-25
```

For a revision-bound Pool, the immutable Runtime Profile is authoritative and
these labels are optional. Set
`SIMPLEPOINT_TOOL_RUNTIME_REQUIRE_MCP_LABELS=true` only while migrating a
legacy Pool that has no Runtime Profile snapshot. Workload starts remain
digest-pinned even when labels are disabled.

The launcher is built as a separate static binary and copied into the existing
Runtime secret volume at node startup. Third-party MCP images are not rebuilt,
wrapped, or modified. Direct FILE bindings do not require the launcher;
`ENV_AT_EXEC` fails closed when the launcher is disabled or unavailable.

The AI control plane performs capacity-aware assignment, lease renewal,
observation, retry, deadline stop, image prewarming, elastic replicas,
scale-to-zero, and final container reclamation. A Redis-backed hashed session
directory and Rendezvous Hash now distribute managed MCP sessions across READY
replicas. `DEDICATED` atomically reserves one workload, `POOL_AFFINE` enforces
the Profile `maxSessions` capacity, and `SHARED_STATELESS` uses deterministic
shared routing without durable per-session occupancy. Stale capacity members
are pruned atomically, active assignments renew both mapping and occupancy TTL,
connection failures release the assignment before failover, and every terminal,
lost, or lease-expired workload atomically removes all fenced Redis assignments
it still owns. A failed Tool request is never replayed automatically.

## Subject-owned OAuth and Agent/Workflow calls

Managed OAuth credentials are stored as user-owned Provider Connections, not
as global fields on an MCP Server or Runtime Pool. Authorization Code + PKCE
binds the callback state to the current platform subject. A Profile uses a
deferred reference such as `provider://github/access-token`; the encrypted
access token is resolved only immediately before dispatch and is materialized
as a `0400` file. The trusted launcher may expose that file to the original
child process through `ENV_AT_EXEC`, but the token is never written into the
Profile, Deployment Revision, Docker environment, process arguments, or API
response. Refresh increments the credential version and replaces the managed
workload. Disconnect clears local token material and also replaces the
workload/session so a stale process cannot continue using it.

Managed tools reach Agents and Workflows through the same publication path:

```text
Runtime Profile revision -> MCP capability snapshot -> published Skill
  -> capability token -> Agent/Workflow execution -> invocation ledger
```

The Skill pins the server, capability snapshot, and tool name. A Tool is
treated as effectful unless its MCP annotations explicitly declare it read-only;
effectful bindings require the Skill execution-approval policy. Execution
propagates tenant, user, execution, and session context to workload selection.
The ledger records both capability snapshot and active Runtime revision while
keeping credentials and sensitive output out of audit payloads.

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

## Six-community-server release gate

Digest-pinned Runtime Profiles and source evidence live in
`testdata/community-mcp-profiles/` for GitHub, Filesystem, Git, PostgreSQL
(DBHub), Docker Hub, and Playwright. The current result ledger is
[`COMMUNITY_MCP_COMPATIBILITY.md`](COMMUNITY_MCP_COMPATIBILITY.md). Verify the
checked-in contract offline, or
also prove that the recorded upstream tags have not moved:

```bash
./scripts/shell/verify_community_mcp_provenance.sh
./scripts/shell/verify_community_mcp_provenance.sh --remote
```

The optional Compose overlay supplies only acceptance infrastructure: a
read-only PostgreSQL target, an isolated browser test site, and a local OCI
registry for generated SimplePoint Skill manifests. The six MCP images still
come directly from their community publishers/catalog and are never rebuilt:

```bash
docker compose \
  -f docker-compose.yml \
  -f docker/compose.community-mcp-e2e.yml \
  -f docker/compose.community-mcp-host-e2e.yml \
  up -d mcp-e2e-postgres mcp-e2e-site registry tool-runtime ai \
       agent-runtime workflow-runtime
```

The host overlay is needed only when AI and the gateway run directly on the
development host; it points the containerized Runtime Node at the host control
plane and keeps all mounts repository-relative.

When the AI service itself runs on the development host, publish and verify the
temporary Skill through the registry's host port:

```bash
MCP_E2E_REGISTRY_REFERENCE=127.0.0.1:5001/simplepoint/community-mcp-e2e \
MCP_COMMUNITY_E2E_SERVICE=filesystem \
MCP_E2E_COOKIE_FILE=/path/to/cookies.txt \
./scripts/shell/verify_community_mcp_e2e.sh
```

Keep the default `registry:5000/...` reference when AI runs inside the Compose
network. The local `dev.env` allowlist admits only the host form, while the
Compose service admits only the internal form; neither mode broadens the
production registry policy.

Run one service per CI matrix job with an authenticated platform session:

```bash
for service in filesystem git postgresql dockerhub playwright; do
  MCP_COMMUNITY_E2E_SERVICE="${service}" \
  MCP_E2E_AUTHORIZATION="Bearer ${CI_ACCESS_TOKEN}" \
  MCP_E2E_AGENT_MODEL_ID="${CI_MODEL_ID}" \
  MCP_E2E_REQUIRE_AGENT=true \
  ./scripts/shell/verify_community_mcp_e2e.sh
done
```

Each job imports the Profile, publishes an immutable revision, runs protocol
admission, binds a Pool, waits for READY, performs a direct Tool call, executes
an MCP-backed Skill, invokes that Skill from an Agent Workflow, executes an
Agent through a configured model, and checks the invocation ledger
for both capability snapshot and Runtime revision IDs. Effectful or unannotated
tools exercise approval: Filesystem writes a bounded workspace file and Git
checks out a pre-seeded acceptance branch; both operations are idempotent so
Skill, Workflow, and Agent paths can use the same immutable binding. PostgreSQL
must return the probe row and reject an INSERT, while Playwright must navigate,
click the test button, and observe the changed DOM in the same browser session.
Pool shutdown and workload/secret cleanup run even on failure.
`MCP_E2E_REQUIRE_AGENT=false` may skip the model-dependent Agent step only for
local diagnosis; such a run is not eligible for `LIVE_E2E` status.
For a deterministic orchestration-only local gate, set
`MCP_E2E_USE_MODEL_STUB=true`; this validates Agent tool selection/routing but
does not claim external model-quality coverage.

GitHub is a separate interactive OAuth gate; no PAT is accepted as a substitute
for the platform Authorization Code + PKCE connection:

```bash
MCP_COMMUNITY_E2E_SERVICE=github \
MCP_E2E_GITHUB_CLIENT_ID="${GITHUB_OAUTH_CLIENT_ID}" \
MCP_E2E_GITHUB_CLIENT_SECRET="${GITHUB_OAUTH_CLIENT_SECRET}" \
MCP_E2E_GITHUB_REDIRECT_URI="http://127.0.0.1:8080/ai/workbench/mcp-servers/oauth/callback" \
MCP_E2E_GITHUB_WAIT_FOR_OAUTH=true \
MCP_E2E_AUTHORIZATION="Bearer ${CI_ACCESS_TOKEN}" \
MCP_E2E_AGENT_MODEL_ID="${CI_MODEL_ID}" \
MCP_E2E_REQUIRE_AGENT=true \
./scripts/shell/verify_community_mcp_e2e.sh
```

Open the printed one-time URL as the same platform user. The runner then checks
that this subject's Provider Connection dispatches the dedicated GitHub
workload; unit and service integration tests enforce rejection when a different
subject attempts to resolve it. Set `MCP_E2E_KEEP_RESOURCES=true` only while
diagnosing a failed gate.

To install the six verified servers as stable development resources instead of
creating time-stamped acceptance resources, use persistent mode. It uses the
codes `community-<service>-mcp`, keeps the published Profile/revision and Pool,
discovers an initial capability snapshot, and configures the Pool for
scale-to-zero when idle. Re-running the same command is idempotent:

```bash
for service in filesystem git postgresql dockerhub playwright; do
  MCP_COMMUNITY_E2E_SERVICE="${service}" \
  MCP_E2E_COOKIE_FILE=/path/to/cookies.txt \
  MCP_E2E_PERSIST_RESOURCES=true \
  ./scripts/shell/verify_community_mcp_e2e.sh
done
```

Run GitHub separately with the OAuth variables shown above. Set
`MCP_E2E_VERIFY_PERSISTENT_CHAIN=true` and `MCP_E2E_AGENT_MODEL_ID` only when a
stable MCP-backed Skill, Workflow, and Agent should also be created and
executed. Persistent PostgreSQL and Playwright retain the acceptance overlay's
read-only database and isolated browser-site boundaries; publish a new reviewed
Profile revision before pointing them at another database or network target.

The local OAuth App callback must use the dedicated browser callback endpoint
shown above and `127.0.0.1`, matching the host OIDC client and the documented
local UI origin. Browser cookies do not cross between `localhost` and
`127.0.0.1`; mixing them redirects the GitHub callback to the platform login
page. The authenticated GET callback completes the exchange server-side and
returns to the host entry page, so it does not depend on a front-end deep link.
