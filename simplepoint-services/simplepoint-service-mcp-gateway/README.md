# SimplePoint MCP Gateway

`simplepoint-service-mcp-gateway` is the independent MCP protocol process. It
connects to remote Streamable HTTP MCP servers and exposes governed northbound
MCP publications using the official MCP Java SDK 2.0.0.

Implemented in this slice:

- MCP `2025-11-25` version negotiation;
- remote `initialize`;
- `tools/list`, `resources/list`, `resources/templates/list`, and `prompts/list`;
- schema-validated `tools/call`;
- governed `resources/read` and `prompts/get`;
- endpoint and private-network validation;
- Bearer and OAuth 2.1 remote authentication;
- RFC 9728 protected-resource metadata, authorization-server discovery,
  PKCE S256, RFC 8707 resource indicators, Client ID Metadata Documents,
  dynamic registration, and refresh;
- northbound Streamable HTTP `POST`, `GET`, and `DELETE` sessions under
  `/mcp/{publication-code}`;
- northbound PS256 issuer, audience, scope, and Origin validation;
- Redis-backed per-publication/client rate limiting;
- immutable publication manifests loaded from the AI control plane;
- bounded persistent southbound sessions with idle eviction and credential
  fingerprint isolation;
- managed OCI stdio MCP sessions through the Runtime Streamable HTTP facade,
  protected by a dedicated TLS 1.3 Gateway identity and workload lease fencing;
- cursor pagination for tools, resources, resource templates, and prompts;
- resource subscriptions, list-changed and resource-updated notifications;
- Redis-broadcast progress and cancellation correlation across Gateway replicas;
- in-place northbound publication updates without dropping active sessions;
- request/result size limits;
- shared-token authenticated internal APIs.

The internal APIs are not public MCP endpoints. External bearer tokens terminate
at the Gateway and are never passed through to remote MCP servers. The host
service keeps a short-lived Redis directory keyed by a hash of
`Mcp-Session-Id`, so requests return to the Gateway replica that owns the SDK
session. A stale mapping is removed and the MCP client must initialize again.

Remote OAuth client selection is deterministic: a pre-registered client ID
wins, then a Client ID Metadata Document is used when the authorization server
advertises support, and Dynamic Client Registration is the fallback. Configure
the Gateway document with an exact public HTTPS URI and at least one redirect:

```bash
export SIMPLEPOINT_MCP_OAUTH_CLIENT_METADATA_URI=\
'https://mcp.example.com/.well-known/oauth-client/open-simplepoint-mcp-gateway'
export SIMPLEPOINT_MCP_OAUTH_REDIRECT_URIS=\
'https://mcp.example.com/ai/workbench/mcp/oauth/callback'
```

The fixed document route remains unavailable when this production-facing
configuration is absent. The current authorization-server slice accepts public
clients (`token_endpoint_auth_method=none`); private-key client authentication
is a later identity-hardening milestone.

The Java SDK 2.0.0 does not expose a server-side cancellation handler or the
active JSON-RPC request ID. A transport-edge adapter therefore accepts the
standard `notifications/cancelled` message and broadcasts cancellation to the
replica holding the southbound call; protocol execution remains in the official
SDK.

For a managed connection, the AI control plane resolves a RUNNING workload and
sends the Gateway its Runtime URL, Lease ID, and fencing token. The Gateway
accepts only trusted private HTTPS Runtime URLs, presents the exact
`spiffe://open-simplepoint/mcp-gateway` identity, and injects fencing headers
into every SDK transport request. Tool code and Docker access remain outside the
Gateway process. The northbound Session ID is sent only over the trusted
Gateway-to-control-plane link. The control plane stores a one-way hash in Redis
and consistently assigns each session across READY Runtime replicas. A
southbound 502 invalidates and quarantines the matching assignment, but the
Gateway does not retry an uncertain Tool call automatically.

Set a strong service-to-service token before using the internal API:

```bash
export SIMPLEPOINT_MCP_GATEWAY_INTERNAL_TOKEN='replace-with-a-long-random-token'
```

Run locally:

```bash
./gradlew :simplepoint-services:simplepoint-service-mcp-gateway:run
```
