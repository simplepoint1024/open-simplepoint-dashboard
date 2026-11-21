package org.simplepoint.data.ownership;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Objects;

/** Atomic local-store claims and immutable receipt; activation is a separate coordinated step. */
final class OwnershipAdoption {
  static final String KEY = "adoption-v1";
  static final String KIND = "legacy-physical-namespace-v1";

  private OwnershipAdoption() {}

  static void apply(
      Connection connection,
      PhysicalAdoptionManifest manifest,
      PhysicalOwnershipInstaller.AdoptionVerifier verifier)
      throws SQLException {
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(verifier, "verifier");
    var namespace = PhysicalNamespace.resolve(connection, manifest.target().namespace());
    try (var lock = NamespaceSessionLock.acquire(connection, namespace)) {
      if (!manifest.target().equals(PhysicalNamespaceDescriptor.capture(connection, namespace.name))
          || !manifest
              .namespaceId()
              .equals(OwnershipSchema.installedIdentity(connection, namespace))) {
        throw conflict();
      }
      OwnershipSchema.verify(connection, namespace);
      PhysicalOwnershipSession.requireHeader(connection, namespace, manifest.namespaceId());
      if (OwnershipActivation.present(connection, namespace, manifest.namespaceId())) {
        throw conflict();
      }
      if (!namespace.postgres) {
        MysqlTriggerMetadataAccess.requireNamespace(connection, namespace.name);
      }
      var allowed = new HashSet<String>();
      allowed.add(OwnershipSchema.HEADER);
      allowed.add(KEY);
      for (var claim : manifest.claims()) {
        if (!claim.table().equals(namespace.canonical(connection, claim.table(), true))) {
          throw conflict();
        }
        allowed.add(PhysicalObjectClaims.key(claim.table()));
        if (OwnershipSchema.objectExists(connection, namespace, claim.table()) != claim.exists()) {
          throw conflict();
        }
        var owner =
            PhysicalObjectClaims.owner(
                connection, namespace, manifest.namespaceId(), claim.table());
        if (owner.isPresent() && !owner.get().equals(claim.owner())) {
          throw conflict();
        }
      }
      try (var statement = connection.createStatement()) {
        statement.setQueryTimeout(30);
        try (var rows = statement.executeQuery("SELECT entry_key FROM " + namespace.directory())) {
          while (rows.next()) {
            if (!allowed.remove(rows.getString(1))) {
              throw conflict();
            }
          }
        }
      }
      boolean complete = receipt(connection, namespace, manifest);
      if (complete && !allowed.isEmpty()) {
        throw OwnershipSchema.invalid();
      }
      verifier.verify(connection, manifest);
      if (!connection.getAutoCommit()) {
        var failure =
            new PhysicalOwnershipException(PhysicalOwnershipException.Code.SESSION_STATE_INVALID);
        NamespaceSessionLock.abort(connection, failure);
        throw failure;
      }
      lock.requireHeld();
      if (complete) {
        return;
      }
      transaction(connection, namespace, manifest, lock);
    }
  }

  static boolean verifyReceipt(Connection connection, PhysicalAdoptionManifest manifest)
      throws SQLException {
    Objects.requireNonNull(manifest, "manifest");
    var namespace = PhysicalNamespace.resolve(connection, manifest.target().namespace());
    try (var lock = NamespaceSessionLock.acquire(connection, namespace)) {
      if (!manifest.target().equals(PhysicalNamespaceDescriptor.capture(connection, namespace.name))
          || !manifest
              .namespaceId()
              .equals(OwnershipSchema.installedIdentity(connection, namespace))) {
        throw conflict();
      }
      OwnershipSchema.verify(connection, namespace);
      PhysicalOwnershipSession.requireHeader(connection, namespace, manifest.namespaceId());
      boolean found = receipt(connection, namespace, manifest);
      if (found) {
        for (var claim : manifest.claims()) {
          if (!PhysicalObjectClaims.owner(
                  connection, namespace, manifest.namespaceId(), claim.table())
              .filter(claim.owner()::equals)
              .isPresent()) {
            throw OwnershipSchema.invalid();
          }
        }
      }
      lock.requireHeld();
      return found;
    }
  }

  static boolean receipt(
      Connection connection, PhysicalNamespace namespace, PhysicalAdoptionManifest manifest)
      throws SQLException {
    String fingerprint = receiptFingerprint(connection, namespace, manifest.namespaceId());
    if (fingerprint == null) {
      return false;
    }
    if (!manifest.fingerprint().equals(fingerprint)) {
      throw conflict();
    }
    return true;
  }

  static String receiptFingerprint(
      Connection connection, PhysicalNamespace namespace, java.util.UUID identity)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT"
                + " namespace_id,contract_version,schema_checksum,object_name,owner_module,"
                + "owner_tenant,owner_resource,created_at_ms"
                + " FROM "
                + namespace.directory()
                + " WHERE entry_key=?")) {
      statement.setQueryTimeout(10);
      statement.setString(1, KEY);
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          return null;
        }
        String fingerprint = rows.getString(7);
        if (!identity.toString().equals(rows.getString(1))
            || rows.getInt(2) != OwnershipSchema.VERSION
            || !OwnershipSchema.checksum(namespace).equals(rows.getString(3))
            || !KIND.equals(rows.getString(4))
            || !"data-ownership".equals(rows.getString(5))
            || rows.getString(6) != null
            || fingerprint == null
            || !fingerprint.matches("[0-9a-f]{64}")
            || rows.getLong(8) <= 0
            || rows.next()) {
          throw conflict();
        }
        return fingerprint;
      }
    }
  }

  private static void transaction(
      Connection connection,
      PhysicalNamespace namespace,
      PhysicalAdoptionManifest manifest,
      NamespaceSessionLock lock)
      throws SQLException {
    try {
      connection.setAutoCommit(false);
      for (var claim : manifest.claims()) {
        if (PhysicalObjectClaims.owner(connection, namespace, manifest.namespaceId(), claim.table())
            .isEmpty()) {
          PhysicalObjectClaims.insert(
              connection, namespace, manifest.namespaceId(), claim.table(), claim.owner());
        }
      }
      try (var statement =
          connection.prepareStatement(
              "INSERT INTO "
                  + namespace.directory()
                  + " (entry_key,namespace_id,contract_version,schema_checksum,object_name,"
                  + "owner_module,owner_tenant,owner_resource,created_at_ms)"
                  + " VALUES (?,?,?,?,?,'data-ownership',NULL,?,?)")) {
        statement.setQueryTimeout(30);
        statement.setString(1, KEY);
        statement.setString(2, manifest.namespaceId().toString());
        statement.setInt(3, OwnershipSchema.VERSION);
        statement.setString(4, OwnershipSchema.checksum(namespace));
        statement.setString(5, KIND);
        statement.setString(6, manifest.fingerprint());
        statement.setLong(7, System.currentTimeMillis());
        if (statement.executeUpdate() != 1) {
          throw OwnershipSchema.invalid();
        }
      }
      lock.requireHeld();
      connection.commit();
      connection.setAutoCommit(true);
    } catch (SQLException | RuntimeException failure) {
      try {
        connection.rollback();
      } catch (SQLException | RuntimeException rollback) {
        failure.addSuppressed(rollback);
      }
      NamespaceSessionLock.abort(connection, failure);
      // A lost COMMIT reply is resolved by the same immutable manifest on a fresh connection.
      throw failure;
    }
  }

  private static PhysicalOwnershipException conflict() {
    return new PhysicalOwnershipException(PhysicalOwnershipException.Code.INSTALLATION_CONFLICT);
  }
}
