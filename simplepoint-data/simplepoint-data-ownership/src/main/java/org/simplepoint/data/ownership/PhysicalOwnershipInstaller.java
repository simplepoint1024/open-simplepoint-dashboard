package org.simplepoint.data.ownership;

import static org.simplepoint.data.ownership.PhysicalOwnershipException.Code.INSTALLATION_CONFLICT;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/**
 * Explicit installation of the immutable namespace protocol. No Spring, domain runtime, broker or
 * automatic discovery is loaded. The installer must persist the chosen UUID before starting DDL.
 */
public final class PhysicalOwnershipInstaller {
  private PhysicalOwnershipInstaller() {}

  /**
   * Verifies reviewed absent objects while excluding participating namespace writers. Only an
   * offline installer may use the callback to commit the corresponding control-store evidence.
   */
  public static void withAbsentReviewed(
      Connection connection,
      PhysicalNamespaceDescriptor expected,
      java.util.List<String> tables,
      Runnable action)
      throws SQLException {
    var namespace = PhysicalNamespace.resolve(connection, expected.namespace());
    try (var lock = NamespaceSessionLock.acquire(connection, namespace)) {
      verifyExpected(connection, namespace, expected);
      String sql =
          namespace.postgres
              ? "SELECT 1 FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n"
                  + " ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=? LIMIT 1"
              : "SELECT 1 FROM information_schema.TABLES WHERE BINARY TABLE_SCHEMA=?"
                  + " AND BINARY TABLE_NAME=? LIMIT 1";
      try (var statement = connection.prepareStatement(sql)) {
        statement.setQueryTimeout(30);
        for (String table : tables) {
          statement.setString(1, namespace.name);
          statement.setString(2, table);
          try (var rows = statement.executeQuery()) {
            if (rows.next()) {
              throw new PhysicalOwnershipException(INSTALLATION_CONFLICT);
            }
          }
        }
      }
      action.run();
      lock.requireHeld();
    }
  }

  /**
   * Atomically records reviewed legacy claims and their target-store receipt, without activation.
   * The trusted offline coordinator must first retire old writers, persist the review and verify
   * the complete domain inventory. The verifier reads structure on this same locked connection.
   */
  public static void adoptReviewed(
      Connection connection, PhysicalAdoptionManifest manifest, AdoptionVerifier verifier)
      throws SQLException {
    OwnershipAdoption.apply(connection, manifest, verifier);
  }

  /**
   * Reads the immutable adoption receipt and retained claims on a fresh connection after an
   * uncertain response. This does not re-certify current table structure or activate runtime.
   */
  public static boolean hasAdoptionReceipt(Connection connection, PhysicalAdoptionManifest manifest)
      throws SQLException {
    return OwnershipAdoption.verifyReceipt(connection, manifest);
  }

  /**
   * Final native activation after the offline coordinator has verified all native and control
   * receipts and old-writer retirement. The final structural verifier runs under the namespace
   * lock. A lost response is reconciled with hasActivationReceipt, never a new operation.
   */
  public static void activateAdopted(
      Connection connection, PhysicalAdoptionManifest manifest, AdoptionVerifier verifier)
      throws SQLException {
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(verifier, "verifier");
    var namespace = PhysicalNamespace.resolve(connection, manifest.target().namespace());
    try (var lock = NamespaceSessionLock.acquire(connection, namespace)) {
      verifyAdoptionTarget(connection, namespace, manifest);
      OwnershipActivation.activateAdopted(connection, namespace, manifest, verifier, lock);
      lock.requireHeld();
    }
  }

  /** Reads an exact adopted activation receipt without reexecuting activation or schema checks. */
  public static boolean hasActivationReceipt(
      Connection connection, PhysicalAdoptionManifest manifest) throws SQLException {
    Objects.requireNonNull(manifest, "manifest");
    var namespace = PhysicalNamespace.resolve(connection, manifest.target().namespace());
    try (var lock = NamespaceSessionLock.acquire(connection, namespace)) {
      verifyAdoptionTarget(connection, namespace, manifest);
      boolean complete = OwnershipActivation.adopted(connection, namespace, manifest);
      lock.requireHeld();
      return complete;
    }
  }

  private static void verifyAdoptionTarget(
      Connection connection, PhysicalNamespace namespace, PhysicalAdoptionManifest manifest)
      throws SQLException {
    verifyExpected(connection, namespace, manifest.target());
    if (!manifest.namespaceId().equals(OwnershipSchema.installedIdentity(connection, namespace))) {
      throw new PhysicalOwnershipException(INSTALLATION_CONFLICT);
    }
    OwnershipSchema.verify(connection, namespace);
    PhysicalOwnershipSession.requireHeader(connection, namespace, manifest.namespaceId());
  }

  /** Read-only domain verification invoked while the inactive namespace lock remains held. */
  @FunctionalInterface
  public interface AdoptionVerifier {
    /** Throws on any mismatch; must not change connection state, execute DDL or write data. */
    void verify(Connection connection, PhysicalAdoptionManifest manifest) throws SQLException;
  }

  /**
   * Inspects installation without repairing it. A marked, empty MySQL directory without a header is
   * explicitly incomplete; corrupt rows, wrong schema and impossible PostgreSQL partial state
   * remain errors. The namespace lock prevents a participating installer from changing the view.
   */
  public static PhysicalOwnershipInspection inspect(
      Connection connection, String canonicalNamespace) throws SQLException {
    var namespace = PhysicalNamespace.resolve(connection, canonicalNamespace);
    try (var lock = NamespaceSessionLock.acquire(connection, namespace)) {
      var descriptor = PhysicalNamespaceDescriptor.capture(connection, namespace.name);
      UUID identity = OwnershipSchema.installedIdentity(connection, namespace);
      if (identity == null) {
        lock.requireHeld();
        return new PhysicalOwnershipInspection(descriptor, null, false);
      }
      OwnershipSchema.verify(connection, namespace);
      boolean complete = PhysicalOwnershipSession.hasHeader(connection, namespace);
      if (complete) {
        PhysicalOwnershipSession.requireHeader(connection, namespace, identity);
      } else {
        requireResumableHeader(connection, namespace);
      }
      lock.requireHeld();
      return new PhysicalOwnershipInspection(descriptor, identity, complete);
    }
  }

  /**
   * Installs or resumes exactly one declared namespace identity. Existing unmarked tables are not
   * adopted. This does not certify legacy business-table ownership or enable SQL-console access.
   */
  public static void install(Connection connection, String canonicalNamespace, UUID namespaceId)
      throws SQLException {
    installExact(connection, canonicalNamespace, namespaceId, null);
  }

  /** Installs only the exact reviewed native target and protocol on the executing connection. */
  public static void installReviewed(
      Connection connection, PhysicalNamespaceDescriptor expected, UUID namespaceId)
      throws SQLException {
    Objects.requireNonNull(expected, "expected");
    installExact(connection, expected.namespace(), namespaceId, expected);
  }

  /**
   * Activates a newly provisioned empty namespace. The trusted installer must also establish that
   * no legacy control bindings or old writers target it. This is never a legacy-adoption shortcut:
   * any preexisting relation or retained claim requires the separate full adoption protocol.
   */
  public static void activateFresh(Connection connection, String canonicalNamespace, UUID identity)
      throws SQLException {
    Objects.requireNonNull(identity, "identity");
    var namespace = PhysicalNamespace.resolve(connection, canonicalNamespace);
    try (var lock = NamespaceSessionLock.acquire(connection, namespace)) {
      if (!identity.equals(OwnershipSchema.installedIdentity(connection, namespace))) {
        throw new PhysicalOwnershipException(INSTALLATION_CONFLICT);
      }
      OwnershipSchema.verify(connection, namespace);
      PhysicalOwnershipSession.requireHeader(connection, namespace, identity);
      OwnershipActivation.activateFresh(connection, namespace, identity);
      lock.requireHeld();
    }
  }

  private static void installExact(
      Connection connection,
      String canonicalNamespace,
      UUID namespaceId,
      PhysicalNamespaceDescriptor expected)
      throws SQLException {
    Objects.requireNonNull(namespaceId, "namespaceId");
    var namespace = PhysicalNamespace.resolve(connection, canonicalNamespace);
    try (var lock = NamespaceSessionLock.acquire(connection, namespace)) {
      verifyExpected(connection, namespace, expected);
      UUID existing = OwnershipSchema.installedIdentity(connection, namespace);
      if (existing != null && !existing.equals(namespaceId)) {
        throw new PhysicalOwnershipException(INSTALLATION_CONFLICT);
      }
      if (existing == null) {
        create(connection, namespace, namespaceId);
      }
      OwnershipSchema.verify(connection, namespace);
      if (!PhysicalOwnershipSession.hasHeader(connection, namespace)) {
        requireResumableHeader(connection, namespace);
        header(connection, namespace, namespaceId);
      }
      PhysicalOwnershipSession.requireHeader(connection, namespace, namespaceId);
      verifyExpected(connection, namespace, expected);
      lock.requireHeld();
    }
  }

  private static void verifyExpected(
      Connection connection, PhysicalNamespace namespace, PhysicalNamespaceDescriptor expected)
      throws SQLException {
    if (expected != null
        && !expected.equals(PhysicalNamespaceDescriptor.capture(connection, namespace.name))) {
      throw new PhysicalOwnershipException(INSTALLATION_CONFLICT);
    }
  }

  private static void requireResumableHeader(Connection connection, PhysicalNamespace namespace)
      throws SQLException {
    if (namespace.postgres) {
      // PostgreSQL creates the header in the same transaction as the directory.
      throw OwnershipSchema.invalid();
    }
    // MySQL DDL commits independently. Only an empty marked directory may resume its header.
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(10);
      try (var rows =
          statement.executeQuery("SELECT 1 FROM " + namespace.directory() + " LIMIT 1")) {
        if (rows.next()) {
          throw OwnershipSchema.invalid();
        }
      }
    }
  }

  private static void create(Connection connection, PhysicalNamespace namespace, UUID identity)
      throws SQLException {
    String sql =
        OwnershipSchema.template(namespace)
            .replace("@TABLE@", namespace.directory())
            .replace("@MARKER@", OwnershipSchema.marker(identity));
    if (!namespace.postgres) {
      execute(connection, sql);
      return;
    }
    // PostgreSQL table, ownership marker and header commit atomically. A lost reply is reconciled
    // by the same persisted installation UUID on the next connection; it is never blindly replayed.
    connection.setAutoCommit(false);
    Throwable primaryFailure = null;
    try {
      execute(connection, sql);
      execute(
          connection,
          "COMMENT ON TABLE "
              + namespace.directory()
              + " IS '"
              + OwnershipSchema.marker(identity)
              + "'");
      header(connection, namespace, identity);
      connection.commit();
    } catch (SQLException | RuntimeException failure) {
      primaryFailure = failure;
      try {
        connection.rollback();
      } catch (SQLException | RuntimeException rollbackFailure) {
        failure.addSuppressed(rollbackFailure);
      }
      NamespaceSessionLock.abort(connection, failure);
      throw failure;
    } finally {
      try {
        // On failure, neither rollback nor abort is guaranteed to have succeeded. Restoring
        // autocommit on that connection could commit the very operation we just rejected.
        if (primaryFailure == null && !connection.isClosed()) {
          connection.setAutoCommit(true);
        }
      } catch (SQLException | RuntimeException restoreFailure) {
        NamespaceSessionLock.abort(connection, restoreFailure);
        if (primaryFailure != null) {
          primaryFailure.addSuppressed(restoreFailure);
        } else {
          throw restoreFailure;
        }
      }
    }
  }

  private static void header(Connection connection, PhysicalNamespace namespace, UUID identity)
      throws SQLException {
    String sql =
        "INSERT INTO "
            + namespace.directory()
            + " (entry_key, namespace_id, contract_version, schema_checksum, created_at_ms) VALUES"
            + " (?, ?, ?, ?, ?)";
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(10);
      statement.setString(1, OwnershipSchema.HEADER);
      statement.setString(2, identity.toString());
      statement.setInt(3, OwnershipSchema.VERSION);
      statement.setString(4, OwnershipSchema.checksum(namespace));
      statement.setLong(5, System.currentTimeMillis());
      if (statement.executeUpdate() != 1) {
        throw OwnershipSchema.invalid();
      }
    }
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(10);
      statement.execute(sql);
    }
  }
}
