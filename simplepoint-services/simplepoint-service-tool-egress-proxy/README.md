# Open SimplePoint Tool Egress Proxy

`tool-egress-proxy` is an independent, stateless HTTP/HTTPS CONNECT proxy for
OCI workloads that otherwise run on an internal Docker network with no direct
route to the Internet.

Each workload receives a short-lived HMAC policy capability minted by its
trusted Runtime node. The capability fixes the workload ID, expiry, and exact
or wildcard DNS allowlist. The proxy resolves the approved DNS name itself,
dials the selected IP directly, and rejects private, loopback, link-local,
carrier-grade NAT, documentation, benchmark, multicast, and other non-public
addresses. This prevents a permitted DNS name from being used to reach Docker,
cloud metadata, or internal platform services.

Only HTTP on port `80` and HTTPS CONNECT on port `443` are supported. The proxy
is attached both to the workload-only internal network and to a network with
outbound connectivity. Workloads are attached only to the internal network.
The proxy has no database or per-instance session state and can therefore run
as one global replica per Docker node or as multiple interchangeable replicas.

Required configuration:

```text
SIMPLEPOINT_TOOL_EGRESS_SIGNING_KEY
```

Optional bounds:

```text
SIMPLEPOINT_TOOL_EGRESS_ADDRESS=:2892
SIMPLEPOINT_TOOL_EGRESS_CONNECT_TIMEOUT=10s
SIMPLEPOINT_TOOL_EGRESS_MAX_TUNNEL_DURATION=24h
SIMPLEPOINT_TOOL_EGRESS_TOKEN_CLOCK_SKEW=15s
SIMPLEPOINT_TOOL_EGRESS_UPSTREAM_PROXY_URL=http://proxy.example.com:3128
```

`SIMPLEPOINT_TOOL_EGRESS_UPSTREAM_PROXY_URL` optionally chains approved traffic
through an HTTP enterprise proxy. `HTTPS_PROXY` and `HTTP_PROXY` are also used
as fallbacks, including values injected by the local Docker client. The signed
per-workload hostname policy and public-address validation still run first.

Production must inject the same random signing key into trusted Runtime nodes
and Egress Proxy replicas. Never inject this key into a workload.
