# Identity credential adapter

This module belongs to `identity` and is included by `simplepoint-plugin-rbac-module`. It implements
`security.credentials:1` using the separate `simplepoint-plugin-rbac-authentication` policy library.
The OAuth browser adapter does not depend on this module. DNA depends only on the credential API;
password hashes, MFA seeds and persistence entities never cross that API.

## Contract and lifecycle

- `open(username, password, oneTimeCode)` requires an authenticated OAuth service client with the
  platform invocation scope. Password verification, account policy, shared attempt budgets and
  configured MFA run inside the identity owner. `open` is not idempotent and must not be replayed
  automatically after a timeout or an uncertain response.
- A successful response contains a random 256-bit opaque handle and bounded public identity.
  Redis stores a hash of caller plus handle and only user ID, authentication version and expiry.
  The handle is bound to the verified calling service, not a submitted caller parameter.
- `check(handle)` validates expiry and reloads current account status/version. Disabling, deleting,
  locking or expiring an account, or changing its authorization version, invalidates the proof.
  `close(handle)` revokes only that caller's proof and is idempotent.
- Lifetime defaults to `20m`, bounded to `1m`–`8h`, through
  `simplepoint.security.authentication.credential-session-lifetime`. Expiry is fixed, not sliding;
  consumers reconnect and supply a fresh configured second factor. Closing a local connection
  prevents further use even if remote revocation is unavailable; the Redis proof still expires.

JDBC rechecks the proof, tenant membership and current DNA grants for every server operation.
Authorization contexts are recalculated per operation. Immutable operation snapshots avoid sharing
mutable grants between concurrent requests; metadata cache hits cannot bypass METADATA permission.
Cache keys use the current permitted metadata-source set and distributed schema versions.

## Audit and failure semantics

`CredentialAudit` commits `security.credential-audit:1` to the identity owner's JDBC outbox in a
separate local transaction. Its historical producer alias remains `common`, including in an aggregate
host. Audit storage failure prevents successful session issuance and triggers revocation of an
undisclosed proof. This ordering does not claim a distributed transaction between Redis and SQL;
an undisclosed proof left by simultaneous cleanup failure expires without being returned.

Success/failure payloads contain a bounded verified caller, optional known user ID, username
fingerprint and canonical outcome. They never contain a submitted password, OTP or session handle.
The auditing module validates producer/schema/scope and persists the outcome in its inbox transaction.
Broker confirmation is not consumer completion; broker downtime leaves the durable outbox pending.

Provision `sp.events.auditing.credential-audit` and the
`sp.events.common / security.credential-audit.v1` binding before enabling the producer. The deployment
fixture `doc/deployment/rabbitmq/login-audit-v1.json` includes both browser and credential subscriptions.
It does not replace the existing common resource-lifecycle ACL. Modular hosts derive the declarations
from `IdentityEventDeclarations` and `AuditEventDeclarations`, with per-owner broker credentials.

`INVALID`, `SESSION_INVALID`, and `UNAVAILABLE` are canonical domain errors. JDBC transports also
return `messageKey`, diagnostic `errorCode`, and SQLState `28000` or `08004`; old clients can continue
reading `errorMessage`. Input factors and session handles are redacted from record `toString()`.

## Migration and verification

`security.user:1` and its entity lookup adapter are removed. Upgrade identity and DNA consumers
coherently; do not retain an entity/password export as a compatibility route. Module descriptors
reference `CredentialAuthenticationService` directly, so capability routing follows that contract.

Tests use real isolated Redis, real Spring transaction proxies with H2 outbox storage, and the real
RPC codec/dispatcher around the credential provider. Factor/account repositories and audit consumers
have explicitly separate tests with mocks; real TCP tests cover the JDBC adapters. These are not
production deployment or complete JDBC→RPC→identity→MQ→audit acceptance. Installation automation,
production storage bindings, TLS/deployment acceptance and full topology scenarios remain in the
module composition plan.
