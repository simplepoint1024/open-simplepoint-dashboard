package org.simplepoint.data.ownership;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/** Shared directory row codec; callers own the namespace lock and transaction lifecycle. */
final class PhysicalObjectClaims {
  private PhysicalObjectClaims() {}

  static Optional<PhysicalObjectOwner> owner(
      Connection connection, PhysicalNamespace namespace, UUID identity, String table)
      throws SQLException {
    String sql =
        "SELECT namespace_id,contract_version,schema_checksum,object_name,owner_module,"
            + "owner_tenant,owner_resource,created_at_ms FROM "
            + namespace.directory()
            + " WHERE entry_key=?";
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(10);
      statement.setString(1, key(table));
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        if (!identity.toString().equals(rows.getString(1))
            || rows.getInt(2) != OwnershipSchema.VERSION
            || !OwnershipSchema.checksum(namespace).equals(rows.getString(3))
            || !table.equals(rows.getString(4))
            || rows.getLong(8) <= 0) {
          throw OwnershipSchema.invalid();
        }
        final PhysicalObjectOwner owner;
        try {
          owner = new PhysicalObjectOwner(rows.getString(5), rows.getString(6), rows.getString(7));
        } catch (IllegalArgumentException failure) {
          throw OwnershipSchema.invalid();
        }
        if (rows.next()) {
          throw OwnershipSchema.invalid();
        }
        return Optional.of(owner);
      }
    }
  }

  static void insert(
      Connection connection,
      PhysicalNamespace namespace,
      UUID identity,
      String table,
      PhysicalObjectOwner owner)
      throws SQLException {
    String sql =
        "INSERT INTO "
            + namespace.directory()
            + " (entry_key,namespace_id,contract_version,schema_checksum,object_name,owner_module,"
            + "owner_tenant,owner_resource,created_at_ms) VALUES (?,?,?,?,?,?,?,?,?)";
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(10);
      statement.setString(1, key(table));
      statement.setString(2, identity.toString());
      statement.setInt(3, OwnershipSchema.VERSION);
      statement.setString(4, OwnershipSchema.checksum(namespace));
      statement.setString(5, table);
      statement.setString(6, owner.moduleId());
      statement.setString(7, owner.tenantId());
      statement.setString(8, owner.resourceId());
      statement.setLong(9, System.currentTimeMillis());
      if (statement.executeUpdate() != 1) {
        throw OwnershipSchema.invalid();
      }
    }
  }

  static void retire(Connection connection, PhysicalNamespace namespace, UUID identity,
      String table, PhysicalObjectOwner marker) throws SQLException {
    try (var statement = connection.prepareStatement("UPDATE " + namespace.directory()
        + " SET owner_module=?, owner_tenant=?, owner_resource=? WHERE entry_key=? AND namespace_id=?")) {
      statement.setQueryTimeout(10);
      statement.setString(1, marker.moduleId());
      statement.setString(2, marker.tenantId());
      statement.setString(3, marker.resourceId());
      statement.setString(4, key(table));
      statement.setString(5, identity.toString());
      if (statement.executeUpdate() != 1) throw OwnershipSchema.invalid();
    }
  }

  static String key(String table) {
    return PhysicalNamespace.digest("physical-object-v1:" + table.length() + ":" + table);
  }
}
