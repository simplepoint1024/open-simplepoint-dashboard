# Physical ownership installation adapter

JDK/JDBC-only adapter between the platform installation journal and physical namespace ownership.
It does not load domain implementations, Spring, brokers or runtime discovery. The dependency gate
`verifyInstallationOwnershipDependencies` enforces its production boundary.

`review` captures native database coordinates and a caller-selected stable namespace UUID without
writing DDL. `prepare` validates all reviewed targets and rejects duplicate aliases before creating
any installation effects. Persist the exact target list privately and durably before running its
actions with `InstallationRunner`. The host's `ownership-review`/`ownership-install` commands do this.

An action uses `installReviewed`, then rereads the installed identity and protocol. Completed physical
receipts reconcile lost journal acknowledgements. A RUNNING attempt without a receipt requires the
existing explicit installer recovery protocol; a connection error is not proof that an attempt failed.
After the runner returns, always call `verifyComplete`, including when its journal was already complete.
This checks current physical effects and does not recreate a subsequently removed directory.

The descriptor stores server/database/namespace identities and a schema checksum, without JDBC URLs,
accounts or credentials. PostgreSQL requires privileged `pg_control_system` access; MySQL uses the
server UUID and database name because it provides no corresponding database OID. These coordinates
do not prove old writers are stopped or distinguish copied/split-brain database installations.

Installation completion is **not** legacy adoption or runtime activation. The runtime refuses inactive
namespaces. The host deliberately reports `legacyAdopted=false` and `activated=false`; legacy binding
inventory, ownership adoption and coordinated activation must be completed separately before deployment.

Native tests use isolated namespaces with `PHYSICAL_OWNERSHIP_TEST_POSTGRES_URL/USER/PASSWORD` and
`PHYSICAL_OWNERSHIP_TEST_MYSQL_URL/USER/PASSWORD`. The control journal is PostgreSQL; physical targets
are tested on both PostgreSQL and MySQL, including header interruption and journal receipt recovery.
