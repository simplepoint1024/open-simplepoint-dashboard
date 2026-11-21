package org.simplepoint.data.ownership;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** Immutable activation receipt, separate from a directory's structural installation header. */
final class OwnershipActivation {
  private static final String KEY = "activation-v1";
  private static final String FRESH = "fresh-physical-namespace-v1";
  private static final String ADOPTED = "adopted-physical-namespace-v1";

  private OwnershipActivation() {}

  static void require(Connection connection, PhysicalNamespace namespace, UUID identity)
      throws SQLException {
    if (!present(connection, namespace, identity)) {
      throw new PhysicalOwnershipException(PhysicalOwnershipException.Code.NAMESPACE_NOT_ACTIVATED);
    }
  }

  static boolean present(Connection connection, PhysicalNamespace namespace, UUID identity)
      throws SQLException {
    String sql =
        "SELECT namespace_id, contract_version, schema_checksum, object_name, owner_module,"
            + " owner_tenant, owner_resource, created_at_ms FROM "
            + namespace.directory()
            + " WHERE entry_key=?";
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(10);
      statement.setString(1, KEY);
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          return false;
        }
        String kind = rows.getString(4);
        String expected = FRESH.equals(kind) ? receipt(identity) : null;
        if (ADOPTED.equals(kind)) {
          String adoption = OwnershipAdoption.receiptFingerprint(connection, namespace, identity);
          if (adoption != null) {
            expected = adoptedReceipt(identity, adoption);
          }
        }
        if (!identity.toString().equals(rows.getString(1))
            || rows.getInt(2) != OwnershipSchema.VERSION
            || !OwnershipSchema.checksum(namespace).equals(rows.getString(3))
            || expected == null
            || !"data-ownership".equals(rows.getString(5))
            || rows.getString(6) != null
            || !expected.equals(rows.getString(7))
            || rows.getLong(8) <= 0
            || rows.next()) {
          throw OwnershipSchema.invalid();
        }
        return true;
      }
    }
  }

  static void activateFresh(Connection connection, PhysicalNamespace namespace, UUID identity)
      throws SQLException {
    if (OwnershipAdoption.receiptFingerprint(connection, namespace, identity) != null) {
      throw new PhysicalOwnershipException(
          PhysicalOwnershipException.Code.EXISTING_OBJECT_REQUIRES_ADOPTION);
    }
    if (present(connection, namespace, identity)) {
      return;
    }
    if (!namespace.postgres) {
      MysqlTriggerMetadataAccess.requireNamespace(connection, namespace.name);
    }
    String objects =
        namespace.postgres
            ? "SELECT 1 FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n"
                + " ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname<>?"
                + " AND c.relkind IN ('r','p','v','m','f','S') LIMIT 1"
            : "SELECT 1 FROM information_schema.TABLES WHERE BINARY TABLE_SCHEMA=?"
                + " AND BINARY TABLE_NAME<>? LIMIT 1";
    try (var statement = connection.prepareStatement(objects)) {
      statement.setQueryTimeout(10);
      statement.setString(1, namespace.name);
      statement.setString(2, PhysicalNamespace.DIRECTORY);
      try (var rows = statement.executeQuery()) {
        if (rows.next()) {
          throw new PhysicalOwnershipException(
              PhysicalOwnershipException.Code.EXISTING_OBJECT_REQUIRES_ADOPTION);
        }
      }
    }
    try (var statement =
        connection.prepareStatement(
            "SELECT 1 FROM " + namespace.directory() + " WHERE entry_key<>? LIMIT 1")) {
      statement.setQueryTimeout(10);
      statement.setString(1, OwnershipSchema.HEADER);
      try (var rows = statement.executeQuery()) {
        if (rows.next()) {
          throw new PhysicalOwnershipException(
              PhysicalOwnershipException.Code.EXISTING_OBJECT_REQUIRES_ADOPTION);
        }
      }
    }
    insert(connection, namespace, identity, FRESH, receipt(identity));
    require(connection, namespace, identity);
  }

  static boolean adopted(
      Connection connection, PhysicalNamespace namespace, PhysicalAdoptionManifest manifest)
      throws SQLException {
    if (!OwnershipAdoption.receipt(connection, namespace, manifest)) {
      return false;
    }
    if (!present(connection, namespace, manifest.namespaceId())) {
      return false;
    }
    // An adoption receipt cannot be combined with an older fresh activation.
    try (var statement =
        connection.prepareStatement(
            "SELECT object_name FROM " + namespace.directory() + " WHERE entry_key=?")) {
      statement.setQueryTimeout(10);
      statement.setString(1, KEY);
      try (var rows = statement.executeQuery()) {
        if (!rows.next() || !ADOPTED.equals(rows.getString(1)) || rows.next()) {
          throw OwnershipSchema.invalid();
        }
      }
    }
    return true;
  }

  static void activateAdopted(
      Connection connection,
      PhysicalNamespace namespace,
      PhysicalAdoptionManifest manifest,
      PhysicalOwnershipInstaller.AdoptionVerifier verifier,
      NamespaceSessionLock lock)
      throws SQLException {
    if (!OwnershipAdoption.receipt(connection, namespace, manifest)) {
      throw new PhysicalOwnershipException(PhysicalOwnershipException.Code.INSTALLATION_CONFLICT);
    }
    for (var claim : manifest.claims()) {
      if (!PhysicalObjectClaims.owner(connection, namespace, manifest.namespaceId(), claim.table())
          .filter(claim.owner()::equals)
          .isPresent()) {
        throw OwnershipSchema.invalid();
      }
    }
    if (adopted(connection, namespace, manifest)) {
      return;
    }
    verifier.verify(connection, manifest);
    if (!connection.getAutoCommit()) {
      var failure =
          new PhysicalOwnershipException(PhysicalOwnershipException.Code.SESSION_STATE_INVALID);
      NamespaceSessionLock.abort(connection, failure);
      throw failure;
    }
    lock.requireHeld();
    insert(
        connection,
        namespace,
        manifest.namespaceId(),
        ADOPTED,
        adoptedReceipt(manifest.namespaceId(), manifest.fingerprint()));
    if (!adopted(connection, namespace, manifest)) {
      throw OwnershipSchema.invalid();
    }
  }

  private static void insert(
      Connection connection,
      PhysicalNamespace namespace,
      UUID identity,
      String kind,
      String fingerprint)
      throws SQLException {
    String insert =
        "INSERT INTO "
            + namespace.directory()
            + " (entry_key,namespace_id,contract_version,schema_checksum,object_name,owner_module,"
            + "owner_tenant,owner_resource,created_at_ms) VALUES (?,?,?,?,?,?,NULL,?,?)";
    try (var statement = connection.prepareStatement(insert)) {
      statement.setQueryTimeout(10);
      statement.setString(1, KEY);
      statement.setString(2, identity.toString());
      statement.setInt(3, OwnershipSchema.VERSION);
      statement.setString(4, OwnershipSchema.checksum(namespace));
      statement.setString(5, kind);
      statement.setString(6, "data-ownership");
      statement.setString(7, fingerprint);
      statement.setLong(8, System.currentTimeMillis());
      if (statement.executeUpdate() != 1) {
        throw OwnershipSchema.invalid();
      }
    }
  }

  private static String adoptedReceipt(UUID identity, String adoption) {
    return PhysicalNamespace.digest(ADOPTED + ":" + identity + ":" + adoption);
  }

  private static String receipt(UUID identity) {
    return PhysicalNamespace.digest(FRESH + ":" + identity);
  }
}
