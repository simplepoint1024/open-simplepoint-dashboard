# Physical namespace ownership

JDK/JDBC-only infrastructure for identifying and reserving managed physical objects across
datasource aliases. DNA model registration now uses this library to reserve a target before
persisting its control binding. Model publication verifies the persisted namespace and each model
owner on its executing connection, retaining the namespace lock through DDL and final checks.
Shared runtime infrastructure provisioning now claims its exact versioned table identities and
uses the model publication connection when one is already locked. SQL-console direct-target
admission now uses the same namespace sessions for Calcite DML and native DML/DDL; complete
indirect-effect policy and deployment acceptance remain pending.
Installing its directory does **not** activate
SQL admission or certify existing business tables.

## Contract

- Installation stores a caller-persisted namespace UUID in the target namespace. Logical owners
  contain a module ID, optional tenant ID and stable resource ID, independent of deployment hosts.
- `PhysicalOwnershipInstaller.install` is an explicit privileged installation operation. Runtime
  sessions never create a missing directory. Unmarked existing directories, incompatible schema,
  wrong installation identities and corrupted headers fail closed.
- `PhysicalOwnershipSession.open` takes a namespace session lock on the supplied executing JDBC
  connection. PostgreSQL locks are database-local; MySQL locks are server-local and include the
  database name. Separate connections to the same namespace contend for the same lock.
- Runtime sessions require an activation receipt in addition to the installation header. An
  installed but inactive directory rejects registrations, publications and SQL writes. Structural
  inspection remains available to the installer without implicitly authorizing business access.
- `inspect` reports the native target and distinguishes absence, a resumable empty MySQL directory,
  and a complete installation. `installReviewed` checks the reviewed native coordinates and immutable
  protocol checksum under the namespace lock before and after installation. The offline installation
  adapter persists the identity before DDL and reconciles physical receipts through the platform journal.
- `activateFresh` is a privileged empty-namespace operation, never a runtime bootstrap. It rejects
  preexisting tables/views and retained ownership claims. The trusted installer must separately prove
  there are no legacy control bindings or old writers. MySQL additionally requires namespace-wide
  metadata authority; visibility of the directory alone cannot certify an empty database. Legacy
  inventory/adoption and coordinated activation are separate work; this helper does not implement them.
- `adoptReviewed` accepts an immutable bounded manifest and a domain structural verifier. It holds
  the native namespace lock, validates exact canonical owners and object presence, invokes the
  verifier on that same connection, then commits all missing claims and the adoption receipt in one
  local transaction. It never activates the namespace. A failed write rolls back; a lost commit reply
  is resolved using `hasAdoptionReceipt` on a fresh connection with the original manifest.
- `activateAdopted` is the final privileged step after the coordinator verifies all native receipts,
  the domain control receipt and old-writer retirement. It checks retained owners and invokes the
  domain's final structural verifier under the namespace lock. Its activation receipt is bound to
  the immutable adoption fingerprint; fresh activation cannot bypass it. `hasActivationReceipt`
  reconciles uncertain responses without rerunning the mutation. Domain control updates and process
  retirement stay outside this JDK-only library; no cross-database atomicity is implied.
- `claimNew` reserves an absent object durably in autocommit mode, before business DDL/control
  registration. Exact owner replay is idempotent. Existing unowned objects require an explicit
  legacy-adoption protocol. Claims are retained after table deletion and have no ordinary release
  operation; tenant grants and business offline state never filter ownership reads.
- PostgreSQL identifier parsing uses the server lexer and server-encoding byte length. Names
  that PostgreSQL would truncate are rejected. MySQL identifiers use its length/domain limits and
  `lower_case_table_names`; case-folding configurations need their own deployment verification.
- Open validates the directory marker, columns, primary key, foreign keys and triggers. Directories
  require MySQL InnoDB or PostgreSQL heap storage. PostgreSQL additionally rejects
  temporary/unlogged, partitioned/inherited, rule-bearing and RLS directories.
  Stored version/checksum/namespace identity and row invariants are checked on reads. This is
  validation of this directory protocol, not a substitute for adopting a business module schema.

## MySQL metadata privileges

MySQL hides a table's triggers from accounts without its `TRIGGER` privilege. Absence of rows in
`information_schema.TRIGGERS` therefore does not prove the absence of triggers. Opening or
installing the ownership directory now requires this metadata authority on the directory itself.
The SQL-console DML adapter also requires it on each ordinary write target before checking triggers.
Use explicit table privileges when provisioning the backend datasource account; this library does
not grant privileges or activate roles. Existing data read/write permissions remain separate.

`MysqlTriggerMetadataAccess` reads the actual executing connection's enabled roles and
`SHOW GRANTS FOR CURRENT_USER USING ...`, with bounded role/grant counts. It checks global,
database and table scopes, partial global revokes, and the server's `partial_revokes` database-name
semantics. Conflicting matching database patterns cannot be treated as a union of privileges:
without an independently sufficient global/table grant, that ambiguous proof is rejected. A clear
table-level `TRIGGER` grant resolves this ambiguity. No privilege proof is cached across operations.
Missing or unprovable authority produces the infrastructure diagnostic `METADATA_UNAVAILABLE`;
public adapters retain their existing safe ownership-unavailable error envelope.

This check proves trigger metadata visibility, not arbitrary function or cascade safety. See the
[MySQL trigger metadata contract](https://dev.mysql.com/doc/refman/8.4/en/information-schema-triggers-table.html),
[effective grants and partial revokes](https://dev.mysql.com/doc/refman/8.4/en/show-grants.html), and
[database wildcard grant rules](https://dev.mysql.com/doc/refman/8.4/en/grant.html).

The federation SQL-console adapter additionally requires `PROCESS` on its backend MySQL account
to inspect the complete `INNODB_FOREIGN` directory before DML. An account can otherwise delete an
ordinary parent and cascade into an ungranted child while ordinary foreign-key metadata hides the
relationship. The adapter rejects mutable cascading actions and fails closed without this metadata
authority; it never grants the privilege. This is a SQL-console requirement, separate from opening
the ownership directory or end-user business permissions. See the
[InnoDB foreign-key metadata contract](https://dev.mysql.com/doc/refman/8.4/en/information-schema-innodb-foreign-table.html).

## Connection lifecycle

The caller owns the connection. Open and claim require autocommit; an admitted business operation
may begin and finish a transaction while holding the session lock. Before executing, call
`verifyExecutionSession`, which checks the existing lock without reacquiring it. Commit/rollback
the business transaction and restore autocommit **before** closing the ownership session. Close
the session before releasing its connection. Never run borrower-controlled session-management SQL,
transparent reconnects or driver-level write retries on this connection.

Namespace admission now verifies this requirement on the actual unwrapped driver connection,
not a datasource alias or the URL alone. Supported connections are pgJDBC `PgConnection` and
Connector/J `ConnectionImpl`, including the platform's Hikari/SimpleConnection leases. Connector/J
must have effective `autoReconnect`, `autoReconnectForPools` and `reconnectAtTxEnd` disabled and
must not be attached to a multi-host proxy. Configuration profiles and separately supplied driver
properties are inspected after driver resolution. Unknown/opaque implementations fail with
`SESSION_CONFIGURATION_UNSUPPORTED`; adding another driver requires a verified inspection policy.

The retained proof binds both the physical Java connection and the server session ID. Acquisition,
execution checks and release revalidate it without changing settings, reconnecting or reacquiring
a lock. A changed setting or session aborts the lease. The inspection uses only public driver
accessors through bounded reflection, so this production library remains JDK/JDBC-only and does not
read credentials. External database proxies must preserve a server session for the entire lease;
transaction/statement multiplexing cannot be used with these session locks. Database administrators
and arbitrary driver/interceptor code remain trusted infrastructure, outside the SQL console boundary.

A lost acquire/unlock receipt, missing lock or unfinished transaction at close aborts the
connection. Abort failure must lead to discarding the connection outside this library, not returning
it to a pool. Abort acknowledgement does not prove the server already completed rollback/released
its locks. Production code does not retry a potentially committed business write. PostgreSQL
installation failure never restores autocommit after an uncertain rollback/abort, because doing so
could commit a partial installation.

PostgreSQL creates table, marker and header in one transaction. MySQL can commit CREATE before
inserting its header; an installer can resume an empty marked directory with the **same previously
persisted UUID**. This narrow installation recovery does not repair an invalid header or infer
ownership of pre-existing business objects.

The namespace lock currently serializes participating operations in that namespace and fails fast
when busy. It is not a distributed lock across MySQL servers or a bypass of database transactions.
Callers must use a bounded contention policy and verify throughput in the end-to-end workload.

## Integration still required

Before opening relation publication, complete SQL execution admission, including indirect effects,
and verify shared runtime infrastructure integration on actual executing connections. Registration
persists the namespace identity in the control binding; model publication revalidates that expected
identity and matching source/reference owners before admitting its callback. Publication plans
include the expectation while absent legacy metadata retains its old serialization.
A freshly installed/replaced directory must never make legacy models unmanaged.
Provide installation-plan ownership, complete legacy inventory/adoption and activation, coordinated
retirement of old writers, and explicit database privileges. Reuse the platform installation and
migration contribution mechanisms when exposing this installation in a topology; do not add runtime
auto-installation.

SQL admission must inspect the complete statement and resolve physical targets, including aliases,
views, triggers, functions, foreign-key side effects and session-local shadowing. This library does
not inspect SQL or prove that an apparently unowned object cannot affect an owned object. Protect
the directory itself from SQL-console modification. Public adapters must map infrastructure codes
to the owning domain's translated error envelope without leaking physical or tenant identities.

## Verification

Run `:simplepoint-data:simplepoint-data-ownership:test` with disposable database credentials in
`PHYSICAL_OWNERSHIP_TEST_POSTGRES_{URL,USER,PASSWORD}` and
`PHYSICAL_OWNERSHIP_TEST_MYSQL_{URL,USER,PASSWORD}`. Credentials are never recorded in artifacts.
The tests create/drop isolated PostgreSQL schemas and MySQL databases, so the test account needs
those privileges. Missing environment skips the corresponding cases and is not a real-database gate.

The native tests cover alias contention and retained claims, Unicode/case/length identity,
pre-existing objects, missing/corrupt directories, schema drift, interrupted installation,
transaction rollback on abort, lost lock receipts and combined cleanup failures. They do not replace
published-form, authorization, workflow or deployment acceptance.
