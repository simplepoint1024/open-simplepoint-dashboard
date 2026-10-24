# Module event composition

Hosts using `@EnablePlatformModules` add `simplepoint-events-composition` instead of assembling one
host-wide publisher. Business code continues to depend on `simplepoint-events-api` and inject
`EventPublisher`.

## Declaration and identity

Annotate the existing `@PlatformModule` configuration with `@ModuleEvents(MyDeclarations.class)`.
The public no-argument `EventDeclarations` implementation supplies:

- A stable event identity, independent of the physical host.
- Allowed event types and schema versions.
- Durable subscriptions and their explicitly trusted producers.

Use the same contract constants in manifests and handlers. Manifests must be pure metadata: no
database reads, connections, credentials or side effects. The deployment planner can read
`ModuleEventPlan` before creating infrastructure.

Existing identities are preserved: `app-form → app`, `identity → common`, `dna-runtime → dna`,
`audit-logging → auditing`. These are logical aliases, not instructions to route through a service
with that name. Moving a module does not rename old outbox/inbox owners, exchanges or queues.

Assembly rejects duplicate identities, duplicate subscription IDs within an owner, local version
mismatches, undeclared/missing handlers, unowned publisher injection, and unknown binding keys.
Factory methods (including static factories) and component classes determine ownership; request
headers and event payloads cannot select the publisher identity. An injected publisher accepts only
its owner's declared event versions.

## External configuration

Selected domain artifacts contribute `simplepoint.events.enabled=true`. Explicitly disabling events
for a selected event-owning module fails startup. Supply a separate secret for each selected owner;
the broker username is derived from the manifest:

```properties
simplepoint.events.modules.identity.password=${MQ_COMMON_PASSWORD}
simplepoint.events.modules.app-form.password=${MQ_APP_PASSWORD}
simplepoint.events.modules.dna-runtime.password=${MQ_DNA_PASSWORD}
simplepoint.events.modules.audit-logging.password=${MQ_AUDITING_PASSWORD}
```

Broker endpoints, virtual host, TLS/socket configuration and cache settings use the normal Spring
Rabbit configuration. Each channel receives a distinct native and Spring connection factory,
replacing shared credential providers/refresh services with that module's credentials. Correlated
confirms and returns are mandatory. `ModuleBrokerConnections` is the infrastructure extension point
for deployments needing different TLS identities or broker placement; it must return independent
factories with the declared username, confirms and returns.

The normal business `DataSource` and transaction manager are used when unambiguous. A host with
separate stores supplies explicit bean bindings:

```properties
simplepoint.events.modules.identity.data-source=identityDataSource
simplepoint.events.modules.identity.transaction-manager=identityTransactionManager
```

Startup verifies that the manager binds the chosen connection transactionally and that the event
schema exists. This does **not** automatically relocate JPA repositories/entities or migrate business
data; those remain part of the module storage work. An event write outside its bound writable
business transaction is rejected.

## Lifecycle and provisioning

`ModuleMigrationPlan.discover(...)` discovers `ModuleEventMigrations` from the independent
`simplepoint-events-migrations` artifact through the migration
contributor SPI. Existing `@ModuleEvents` declarations and `ModuleStorageBindings` determine which
stores need the ledger; business modules do not repeat SQL ownership or installation configuration.
Each selected event store receives one `platform-events` schema contribution owning the three
`sp_event_*_v1` tables. Several modules sharing a store share this migration history, while every
separate store has its own copy with the same stable history identity and checksums.

The installer must execute and verify this plan before starting event workers. This contribution
does not perform runtime DDL or grant application accounts migration privileges. Existing ledger
tables without tracked history require explicit legacy adoption; moving or selecting another event
owner does not discard receipts, pending jobs, replay records or quarantine evidence. Stores with
no selected event owner receive no event migration. The PostgreSQL and H2 V1 assets preserve the
deployed portable schema; the existing manual script remains available for original service hosts.

All handler declarations are checked before any listener or worker starts. Each channel reuses the
existing JDBC outbox/inbox, broker receipt validation, manual ACK, retry and dispatcher implementation.
Inbox effects and outgoing completion events retain the same local transaction.

Provision every producer exchange, durable subscriber queue, binding and owner ACL before enabling
producers. Modular runtime rejects `declare-topology=true`; production application accounts must not
gain configure permission. A complete installation/provisioning command is still pending.

`moduleEventStorage` and `moduleEventTransport` health indicators are separate. Include storage
health with the host's database readiness checks; transport failure alone must not remove a
database-ready service from RPC discovery. Backlog metrics add `module` and `owner` tags to
`simplepoint.events.jobs{direction,state}`. Host readiness configuration and full deployment
acceptance remain required.

## Verification and current limits

The tests use Spring contexts and real H2 transactions to cover owner-bound injection, separate
connections, shared and explicitly separate stores, rollback, incoming deduplication and atomic
business/inbox/completion changes. Assembly tests deliberately suppress lifecycle startup and
manually dispatch jobs.

`ModuleEventBrokerIntegrationTest` starts actual channel listeners/workers when these environment
variables identify an isolated broker:

- `SP_MODULE_EVENT_RABBIT_PORT`
- `SP_MODULE_EVENT_ONE_PASSWORD`
- `SP_MODULE_EVENT_TWO_PASSWORD`

Provision vhost `/module-verification`, users `legacy-one` and `legacy-two`, their own write exchanges
`sp.events.{identity}`, and read-only access to their own queues
`sp.events.legacy-one.one.receive` / `sp.events.legacy-two.two.receive`. Bind the queues to the other
producer using `fixture.two.v1` / `fixture.one.v1`. The test checks real request/completion delivery,
ACL rejection, forged producer rejection, outbox durability during connection loss and recovery.
Never point it at business data or grant cross-owner permissions.

PostgreSQL migration and JPA transaction checks live in
`simplepoint-platform-composition-verification`: shared and separate module stores preserve
business/outbox rollback, receipt deduplication, atomic inbox effects and restart state. Delivery in
those tests is driven in process. These checks do not prove cluster failover, existing ledger
relocation, all domain flows or a full standalone application. The authorization host's login event
contribution, credential-authentication audit and automated installation still need integration.

## 与模块持久化绑定

当宿主启用 `@EnableModulePersistence` 时，事件通道自动读取 `ModuleStorageBindings` 中所属模块的数据源和事务管理器，无需重复配置 `simplepoint.events.modules.<id>.data-source` / `transaction-manager`。显式覆盖只能解析到同一实例；指向其他存储会拒绝启动。共享 schema 贡献已自动进入模块迁移计划，安装器须在事件运行时启动前执行并校验；运行期绑定本身不执行 DDL。未启用模块持久化的入口保持既有显式存储绑定行为。
