package org.simplepoint.data.ownership;

import static org.simplepoint.data.ownership.PhysicalOwnershipException.Code.DIRECTORY_NOT_INSTALLED;
import static org.simplepoint.data.ownership.PhysicalOwnershipException.Code.EXISTING_OBJECT_REQUIRES_ADOPTION;
import static org.simplepoint.data.ownership.PhysicalOwnershipException.Code.OWNER_CONFLICT;
import static org.simplepoint.data.ownership.PhysicalOwnershipException.Code.RESERVED_OBJECT;
import static org.simplepoint.data.ownership.PhysicalOwnershipException.Code.SESSION_STATE_INVALID;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An installed and activated, exclusively locked namespace. Callers close it before returning its
 * connection; driver-level reconnects/retries and caller-controlled session SQL are prohibited.
 */
public final class PhysicalOwnershipSession implements AutoCloseable {
  public static final int MAX_DROP_TABLES = 2000;
  private final Connection connection;
  private final PhysicalNamespace namespace;
  private final NamespaceSessionLock lock;
  private final UUID namespaceId;
  private boolean closed;

  private PhysicalOwnershipSession(
      Connection connection,
      PhysicalNamespace namespace,
      NamespaceSessionLock lock,
      UUID namespaceId) {
    this.connection = connection;
    this.namespace = namespace;
    this.lock = lock;
    this.namespaceId = namespaceId;
  }

  /** Opens only an activated namespace; installation alone never authorizes ordinary SQL. */
  public static PhysicalOwnershipSession open(Connection connection, String canonicalNamespace)
      throws SQLException {
    Objects.requireNonNull(connection, "connection");
    var namespace = PhysicalNamespace.resolve(connection, canonicalNamespace);
    var lock = NamespaceSessionLock.acquire(connection, namespace);
    try {
      UUID identity = OwnershipSchema.installedIdentity(connection, namespace);
      if (identity == null) {
        throw new PhysicalOwnershipException(DIRECTORY_NOT_INSTALLED);
      }
      OwnershipSchema.verify(connection, namespace);
      requireHeader(connection, namespace, identity);
      OwnershipActivation.require(connection, namespace, identity);
      return new PhysicalOwnershipSession(connection, namespace, lock, identity);
    } catch (SQLException | RuntimeException failure) {
      try {
        lock.close();
      } catch (SQLException | RuntimeException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  /** Stable identity stored in the target namespace, independent of datasource aliases or hosts. */
  public UUID namespaceId() {
    return namespaceId;
  }

  /** Revalidates the session lock immediately before an admitted operation; never reacquires it. */
  public synchronized void verifyExecutionSession() throws SQLException {
    if (closed) {
      throw new PhysicalOwnershipException(SESSION_STATE_INVALID);
    }
    lock.requireHeld();
  }

  /**
   * Resolves all retained ownership, without resource lifecycle, tenant visibility or grant
   * filters.
   */
  public synchronized Optional<PhysicalObjectOwner> owner(String tableName, boolean quoted)
      throws SQLException {
    verifyExecutionSession();
    String table = namespace.canonical(connection, tableName, quoted);
    if (PhysicalNamespace.DIRECTORY.equals(table)) {
      return Optional.of(new PhysicalObjectOwner("data-ownership", null, "physical-directory-v1"));
    }
    return PhysicalObjectClaims.owner(connection, namespace, namespaceId, table);
  }

  /**
   * Durably reserves a not-yet-created physical table. Exact replay is idempotent; another owner or
   * an existing unowned object requires explicit reconciliation, never implicit adoption.
   */
  public synchronized void claimNew(String tableName, boolean quoted, PhysicalObjectOwner owner)
      throws SQLException {
    Objects.requireNonNull(owner, "owner");
    verifyExecutionSession();
    if (!connection.getAutoCommit()) {
      throw new PhysicalOwnershipException(SESSION_STATE_INVALID);
    }
    String table = namespace.canonical(connection, tableName, quoted);
    if (PhysicalNamespace.DIRECTORY.equals(table)) {
      throw new PhysicalOwnershipException(RESERVED_OBJECT);
    }
    var existing = owner(table, true);
    if (existing.isPresent()) {
      if (!existing.get().equals(owner)) {
        throw new PhysicalOwnershipException(OWNER_CONFLICT);
      }
      return;
    }
    if (OwnershipSchema.objectExists(connection, namespace, table)) {
      throw new PhysicalOwnershipException(EXISTING_OBJECT_REQUIRES_ADOPTION);
    }
    PhysicalObjectClaims.insert(connection, namespace, namespaceId, table, owner);
  }

  /** Drops a verified group in one namespace without cascading to unselected objects. */
  public synchronized void dropOwnedTables(UUID expectedNamespace, List<OwnedTable> selection)
      throws SQLException {
    verifyExecutionSession();
    if (!connection.getAutoCommit() || !namespaceId.equals(expectedNamespace)
        || selection == null || selection.isEmpty() || selection.size() > MAX_DROP_TABLES) {
      throw new PhysicalOwnershipException(SESSION_STATE_INVALID);
    }
    var names = new LinkedHashMap<String, PhysicalObjectOwner>();
    var retirements = new LinkedHashMap<String, PhysicalObjectOwner>();
    for (OwnedTable request : selection) {
      Objects.requireNonNull(request, "table");
      String table = namespace.canonical(connection, request.name(), true);
      if (PhysicalNamespace.DIRECTORY.equals(table)) {
        throw new PhysicalOwnershipException(RESERVED_OBJECT);
      }
      var marker = retirementMarker(request.owner());
      var existing = owner(table, true).orElseThrow(() -> new PhysicalOwnershipException(OWNER_CONFLICT));
      if (names.putIfAbsent(table, request.owner()) != null
          || !existing.equals(request.owner()) && !existing.equals(marker)) {
        throw new PhysicalOwnershipException(OWNER_CONFLICT);
      }
      if (!existing.equals(marker)) retirements.put(table, marker);
    }
    // The namespace lock fences old workers. A committed marker also fences a worker after
    // a lost DDL result or restart, while allowing an exact retry of the same retired owner.
    for (var entry : retirements.entrySet()) {
      PhysicalObjectClaims.retire(connection, namespace, namespaceId, entry.getKey(), entry.getValue());
    }
    String namesSql = names.keySet().stream()
        .map(name -> namespace.quote(namespace.name) + "." + namespace.quote(name))
        .collect(java.util.stream.Collectors.joining(", "));
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(30);
      statement.execute("DROP TABLE IF EXISTS " + namesSql);
    }
  }

  private static PhysicalObjectOwner retirementMarker(PhysicalObjectOwner owner) {
    return new PhysicalObjectOwner("retired-ownership", owner.tenantId(),
        PhysicalNamespace.digest(owner.moduleId() + "\u0000" + owner.resourceId()));
  }

  /** Exact registered physical ownership; the owning service resolves the tables. */
  public record OwnedTable(String name, PhysicalObjectOwner owner) {
    public OwnedTable {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(owner, "owner");
    }
  }

  static boolean hasHeader(Connection connection, PhysicalNamespace namespace) throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT 1 FROM " + namespace.directory() + " WHERE entry_key=?")) {
      statement.setQueryTimeout(10);
      statement.setString(1, OwnershipSchema.HEADER);
      try (var rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }

  static void requireHeader(Connection connection, PhysicalNamespace namespace, UUID identity)
      throws SQLException {
    String sql =
        "SELECT namespace_id, contract_version, schema_checksum, object_name, owner_module, "
            + "owner_tenant, owner_resource, created_at_ms FROM "
            + namespace.directory()
            + " WHERE entry_key=?";
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(10);
      statement.setString(1, OwnershipSchema.HEADER);
      try (var rows = statement.executeQuery()) {
        if (!rows.next()
            || !identity.toString().equals(rows.getString(1))
            || rows.getInt(2) != OwnershipSchema.VERSION
            || !OwnershipSchema.checksum(namespace).equals(rows.getString(3))
            || rows.getString(4) != null
            || rows.getString(5) != null
            || rows.getString(6) != null
            || rows.getString(7) != null
            || rows.getLong(8) <= 0
            || rows.next()) {
          throw OwnershipSchema.invalid();
        }
      }
    }
  }

  @Override
  public synchronized void close() throws SQLException {
    if (closed) {
      return;
    }
    closed = true;
    lock.close();
  }
}
