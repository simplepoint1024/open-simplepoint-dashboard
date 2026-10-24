# Shared event schema migrations

This module supplies the `platform-events` schema through `ModuleMigrationContributor`. Its
production dependencies contain no Spring, JPA, broker client or domain implementation. An installer
can therefore plan and apply the event ledger without starting messaging infrastructure.

`@ModuleEvents` declares a shared-schema requirement through `@ModuleSharedSchemas`. The contributor
reads that capability on selected modules and resolves their stores through `ModuleStorageBindings`.
It contributes one ledger per distinct store. No event owner means no contribution. Producer
identities and subscriptions remain the responsibility of the separate event contract planner.

`simplepoint-events-composition` includes this adapter automatically. A migration-only tool includes
this module alongside `simplepoint-data-migrations`; normal `ModuleMigrationPlan.discover(...)` uses
the service-provider declaration. Missing required providers, duplicate owners, overlapping table
ownership and writes to unselected stores are rejected before installation.

The three tables preserve the deployed ledger contract:

- `sp_event_jobs_v1`: owner-scoped outbox/inbox, deduplication and fenced leases.
- `sp_event_replays_v1`: audited replay history.
- `sp_event_quarantine_v1`: bounded untrusted-delivery evidence.

PostgreSQL and H2 have explicit versioned assets. V1 matches the existing portable manual script;
subsequent changes must add versions. Tables with no tracked migration history require explicit
legacy adoption. Restarting migration or moving modules between hosts must not erase existing rows
or reset owner identities. This module neither relocates stored data nor provisions broker accounts.
