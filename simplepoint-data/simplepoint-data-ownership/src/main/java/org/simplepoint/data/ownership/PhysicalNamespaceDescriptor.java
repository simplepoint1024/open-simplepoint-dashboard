package org.simplepoint.data.ownership;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Non-secret native coordinates for a reviewed installer target, not a runtime authorization. */
public record PhysicalNamespaceDescriptor(
    String dialect,
    String serverIdentity,
    String database,
    String databaseIdentity,
    String namespace,
    String namespaceIdentity,
    String protocolChecksum) {
  /** Keeps persisted review values bounded and rejects unsupported descriptor shapes. */
  public PhysicalNamespaceDescriptor {
    if (!"postgresql".equals(dialect) && !"mysql".equals(dialect)) {
      throw new IllegalArgumentException("Unsupported physical namespace dialect");
    }
    serverIdentity = PhysicalObjectOwner.required(serverIdentity, 128);
    database = PhysicalObjectOwner.required(database, 255);
    databaseIdentity = PhysicalObjectOwner.required(databaseIdentity, 255);
    namespace = PhysicalObjectOwner.required(namespace, 255);
    namespaceIdentity = PhysicalObjectOwner.required(namespaceIdentity, 255);
    if (protocolChecksum == null || !protocolChecksum.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Invalid physical namespace protocol fingerprint");
    }
  }

  /**
   * Reads the real server/database/namespace on the supplied installer connection. PostgreSQL
   * requires permission to read pg_control_system; no host name or JDBC credential is persisted.
   */
  public static PhysicalNamespaceDescriptor capture(Connection connection, String namespace)
      throws SQLException {
    Objects.requireNonNull(connection, "connection");
    var target = PhysicalNamespace.resolve(connection, namespace);
    String sql =
        target.postgres
            ? "SELECT system_identifier::text, d.datname, d.oid::text, n.nspname, n.oid::text"
                + " FROM pg_catalog.pg_control_system() CROSS JOIN pg_catalog.pg_database d"
                + " CROSS JOIN pg_catalog.pg_namespace n"
                + " WHERE d.datname=pg_catalog.current_database() AND n.nspname=?"
            : "SELECT @@server_uuid, SCHEMA_NAME, SCHEMA_NAME, SCHEMA_NAME, SCHEMA_NAME"
                + " FROM information_schema.SCHEMATA WHERE BINARY SCHEMA_NAME=?";
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(10);
      statement.setString(1, target.name);
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw new PhysicalOwnershipException(
              PhysicalOwnershipException.Code.NAMESPACE_UNAVAILABLE);
        }
        var result =
            new PhysicalNamespaceDescriptor(
                target.postgres ? "postgresql" : "mysql",
                rows.getString(1),
                rows.getString(2),
                rows.getString(3),
                rows.getString(4),
                rows.getString(5),
                OwnershipSchema.checksum(target));
        if (rows.next()) {
          throw new PhysicalOwnershipException(
              PhysicalOwnershipException.Code.NAMESPACE_UNAVAILABLE);
        }
        return result;
      }
    }
  }
}
