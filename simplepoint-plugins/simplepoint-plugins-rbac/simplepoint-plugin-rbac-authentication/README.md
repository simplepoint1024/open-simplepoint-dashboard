# Identity authentication policy

This library belongs to the `identity` logical module. It is reused by the OAuth browser adapter and
by non-browser credential adapters. It contains no HTTP endpoints, OAuth server configuration or
remote provider. It does not constitute a separately deployable domain.

## Shared policy

- `LoginProtection` owns bounded Redis attempt budgets and TOTP/challenge replay protection.
  Input identities are normalized identically in browser and non-browser flows. Canonical account
  budgets prevent email and phone aliases, or different adapters, from multiplying attempts.
- `ProtectedPasswordAuthenticationProvider` applies Spring Security password/account checks and
  the canonical account budget. Its successful result proves only the password factor.
- `MfaVerificationService` checks a local `MfaChallenge`, reloads the account and authorization
  version, decrypts the seed locally, and consumes the accepted TOTP step and challenge.
- `AccountCredentialVerifier` verifies password plus all configured factors in one local call.
  It reloads account state after password verification, rejects a changed authorization version,
  and resets the input budget only after successful factor verification.
- `mfa.TwoFactorSetupService` owns enrollment, confirmation and disabling inside the identity
  transaction. Its implementation locks the current account, stores only an encrypted seed and
  advances the authorization version when the active factor changes. Its EntityManager and
  transaction manager follow identity's storage binding. This is a local interface accepting a
  `User`, not an RPC contract; the HTTP controller remains in the OAuth adapter.

`PendingMfaAuthentication` remains in the OAuth adapter. Its session representation is adapted to a
local `MfaChallenge`; neither browser tokens nor persistence entities are RPC contracts.

## Adapter obligations

A non-browser adapter must authenticate/authorize its service caller, bound its input, invoke the
shared verifier inside the identity owner, durably audit success/failure and return a credential-free
projection. A verifier result alone does not issue a session. Password hashes and MFA seeds must
never leave the identity owner.

Issued sessions must have explicit expiry and account/authorization-version revalidation. A transport
timeout must not automatically replay a credential attempt: attempts and OTP consumption have
effects. The separate `simplepoint-plugin-rbac-credentials` adapter now implements the remote contract,
durable audit and caller-bound sessions; DNA consumes its DTO API. This policy library remains usable
without that adapter, its RPC provider, or its event transport. Production topology acceptance remains
part of the composition plan.

Redis keys/configuration retain their existing names so browser and non-browser adapters share
budgets during migration. Redis unavailability fails authentication. The existing authentication
secret cipher and password encoder remain authoritative; this module introduces no alternative
credential store or default secrets.

## Verification

Run `:simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-authentication:check`
and the OAuth server regression tests. The module tests start an isolated local `redis-server` for
actual concurrent attempt/replay checks. Credential tests use the real Spring password provider and
BCrypt; the account repository and selected MFA collaborators are test doubles. These tests do not
prove a deployed JDBC authentication flow or production database topology.

`AuthenticationStoragePostgresTest` in `simplepoint-platform-composition-verification` additionally
checks the real enrollment service alongside the OIDC adapters with shared and separate PostgreSQL
schemas. It uses a real cipher/password encoder, replacing TOTP and Redis replay controls to isolate
the persistence boundary. It verifies rollback, independent store commits, authorization versions
and rejection of an unrelated primary data source/transaction manager. This does not replace the
Redis policy tests or full deployed login acceptance.
