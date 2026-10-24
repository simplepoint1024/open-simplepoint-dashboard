package org.simplepoint.data.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.simplepoint.data.migration.ModuleMigrationPlan.ModuleSchema;

/** Explicit, verified V1 legacy adoption; ordinary migration never calls this administrator API. */
public final class ModuleSchemaAdoption {
  private static final String RECEIPTS = "sp_schema_adoptions_v1";

  /** Installer-owned contribution; install it explicitly before applying reviewed adoptions. */
  public static ModuleMigrationPlan receiptMigrations(String store) {
    return new ModuleMigrationPlan(List.of(ModuleMigrationPlan.loadSchema(
        "schema-adoption", store, ModuleSchemaAdoption.class.getClassLoader())));
  }

  /** Reads exact durable completion without executing or repairing an uncertain adoption. */
  public boolean completed(ModuleSchema schema, DataSource source, Approval approval) {
    if (!baseline(schema).sha256().equals(approval.scriptFingerprint())) {
      throw failure("ADOPTION_ARTIFACT_MISMATCH", schema, null);
    }
    try (var connection = source.getConnection()) {
      if (!hasTable(connection, RECEIPTS)) {
        return false;
      }
      var saved = receipt(connection, schema);
      if (saved == null) {
        return false;
      }
      requireReceipt(schema, saved, approval);
      if (!"APPLIED".equals(saved[4])) {
        return false;
      }
      requireCompletedHistory(connection, schema);
      verifyBaseline(connection, schema);
      return true;
    } catch (SQLException failed) {
      throw failure("ADOPTION_DATABASE_UNAVAILABLE", schema, failed);
    }
  }

  /**
   * Records V1 only after matching the reviewed physical contract under table locks. Subsequent
   * versions still execute through normal migrations, including their data transformations.
   * The installer must stop/fence old writers and validate domain data before granting approval.
   */
  public void adopt(ModuleSchema schema, DataSource source, Approval approval) {
    Objects.requireNonNull(approval);
    var baseline = baseline(schema);
    if (!baseline.sha256().equals(approval.scriptFingerprint())) {
      throw failure("ADOPTION_ARTIFACT_MISMATCH", schema, null);
    }
    try (var connection = source.getConnection()) {
      if (!connection.getAutoCommit()
          || !"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
        throw failure("INVALID_ADOPTION_CONNECTION", schema, null);
      }
      // Session lock spans Flyway's separate, short metadata transaction. Never steal a lease.
      try (var statement = connection.prepareStatement(
          "select pg_advisory_lock(hashtextextended(current_database() || ':' || current_schema() || ':schema-adoption',0))")) {
        statement.execute();
      }
      try {
        connection.setAutoCommit(false);
        var existing = receipt(connection, schema);
        if (existing != null) {
          requireReceipt(schema, existing, approval);
          if ("APPLIED".equals(existing[4])) {
            requireCompletedHistory(connection, schema);
            verifyBaseline(connection, schema);
            connection.commit();
            return;
          }
        }
        lockExistingTables(connection, schema);
        var contract = PostgresSchemaContract.capture(connection, schema.tables());
        if (!contract.fingerprint().equals(approval.schemaFingerprint())) {
          throw failure("ADOPTION_SCHEMA_MISMATCH", schema, null);
        }
        if (existing == null) {
          if (hasHistory(connection, schema)) {
            throw failure("UNOWNED_ADOPTION_HISTORY", schema, null);
          }
          try (var statement = connection.prepareStatement("insert into " + RECEIPTS
              + "(module_id,operation_id,script_checksum,schema_checksum,evidence_checksum,state)"
              + " values (?,?,?,?,?,'PREPARED')")) {
            statement.setString(1, schema.module());
            statement.setString(2, approval.operation().toString());
            statement.setString(3, approval.scriptFingerprint());
            statement.setString(4, approval.schemaFingerprint());
            statement.setString(5, approval.evidenceFingerprint());
            statement.executeUpdate();
          }
        }
        connection.commit();
        // Retain table locks through baseline creation and receipt completion. Flyway only
        // writes its separate history table; business schema changes cannot race this check.
        lockExistingTables(connection, schema);
        if (!PostgresSchemaContract.capture(connection, schema.tables()).fingerprint()
            .equals(approval.schemaFingerprint())) {
          throw failure("ADOPTION_SCHEMA_MISMATCH", schema, null);
        }
        if (!hasHistory(connection, schema)) {
          Flyway.configure(ModuleSchemaAdoption.class.getClassLoader())
              .dataSource(source).table(schema.historyTable()).locations(new String[0])
              .javaMigrationClassProvider(Set::of).createSchemas(false).cleanDisabled(true)
              .baselineVersion("1").baselineDescription("Verified module adoption " + schema.module())
              .load().baseline();
        }
        requireAdoptionBaseline(connection, schema);
        try (var statement = connection.prepareStatement("update " + RECEIPTS
            + " set state='APPLIED' where module_id=? and operation_id=? and state='PREPARED'")) {
          statement.setString(1, schema.module());
          statement.setString(2, approval.operation().toString());
          if (statement.executeUpdate() != 1) {
            throw failure("ADOPTION_RECEIPT_CONFLICT", schema, null);
          }
        }
        connection.commit();
      } finally {
        if (!connection.getAutoCommit()) {
          connection.rollback();
          connection.setAutoCommit(true);
        }
        try (var statement = connection.prepareStatement(
            "select pg_advisory_unlock(hashtextextended(current_database() || ':' || current_schema() || ':schema-adoption',0))")) {
          statement.execute();
        }
      }
    } catch (SQLException failed) {
      throw failure("ADOPTION_DATABASE_UNAVAILABLE", schema, failed);
    }
  }

  /** Pins scripts hidden by an adopted baseline, which native Flyway validation cannot check. */
  static void verifyBaseline(Connection connection, ModuleSchema schema) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("select \"version\" from \"" + schema.historyTable()
            + "\" where \"type\"='BASELINE' and \"success\"=true")) {
      while (rows.next()) {
        if ("0".equals(rows.getString(1))) {
          continue;
        }
        if (!"1".equals(rows.getString(1))) {
          throw failure("UNVERIFIED_ADOPTION_BASELINE", schema, null);
        }
        var saved = receipt(connection, schema);
        if (saved == null || !"APPLIED".equals(saved[4])
            || !baseline(schema).sha256().equals(saved[1])) {
          throw failure("ADOPTION_RECEIPT_MISMATCH", schema, null);
        }
      }
    }
  }

  private static void requireAdoptionBaseline(Connection connection, ModuleSchema schema)
      throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("select \"version\",\"type\",\"success\" from \""
            + schema.historyTable() + "\"")) {
      if (!rows.next() || !"1".equals(rows.getString(1)) || !"BASELINE".equals(rows.getString(2))
          || !rows.getBoolean(3) || rows.next()) {
        throw failure("ADOPTION_HISTORY_CONFLICT", schema, null);
      }
    }
  }

  private static boolean hasHistory(Connection connection, ModuleSchema schema) throws SQLException {
    return hasTable(connection, schema.historyTable());
  }

  private static boolean hasTable(Connection connection, String name) throws SQLException {
    try (var result = connection.getMetaData().getTables(connection.getCatalog(),
        connection.getSchema(), name, new String[] {"TABLE"})) {
      while (result.next()) {
        if (name.equals(result.getString("TABLE_NAME"))) {
          return true;
        }
      }
      return false;
    }
  }

  private static void requireCompletedHistory(Connection connection, ModuleSchema schema)
      throws SQLException {
    if (!hasHistory(connection, schema)) {
      throw failure("ADOPTION_HISTORY_CONFLICT", schema, null);
    }
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("select \"version\",\"success\" from \""
            + schema.historyTable() + "\" where \"type\"='BASELINE'")) {
      if (!rows.next() || !"1".equals(rows.getString(1)) || !rows.getBoolean(2) || rows.next()) {
        throw failure("ADOPTION_HISTORY_CONFLICT", schema, null);
      }
    }
  }

  private static String[] receipt(Connection connection, ModuleSchema schema) throws SQLException {
    try (var statement = connection.prepareStatement("select operation_id,script_checksum,"
        + "schema_checksum,evidence_checksum,state from " + RECEIPTS + " where module_id=?")) {
      statement.setString(1, schema.module());
      try (var result = statement.executeQuery()) {
        return result.next() ? new String[] {result.getString(1), result.getString(2),
            result.getString(3), result.getString(4), result.getString(5)} : null;
      }
    }
  }

  private static void lockExistingTables(Connection connection, ModuleSchema schema) throws SQLException {
    for (String name : schema.tables().stream().sorted().toList()) {
      if (hasTable(connection, name)) {
        try (var statement = connection.createStatement()) {
          statement.execute("lock table \"" + name + "\" in share row exclusive mode");
        }
      }
    }
  }

  private static ModuleMigrationPlan.Script baseline(ModuleSchema schema) {
    var scripts = schema.scripts().getOrDefault("postgresql", List.of()).stream()
        .filter(script -> script.name().startsWith("V1__")).toList();
    if (scripts.size() != 1) {
      throw failure("UNSUPPORTED_ADOPTION_BASELINE", schema, null);
    }
    return scripts.getFirst();
  }

  private static void requireReceipt(ModuleSchema schema, String[] saved, Approval approval) {
    if (!saved[0].equals(approval.operation().toString())
        || !saved[1].equals(approval.scriptFingerprint())
        || !saved[2].equals(approval.schemaFingerprint())
        || !saved[3].equals(approval.evidenceFingerprint())) {
      throw failure("ADOPTION_RECEIPT_CONFLICT", schema, null);
    }
  }

  private static ModuleMigrationException failure(String code, ModuleSchema schema, Throwable cause) {
    return new ModuleMigrationException(code, schema.module(), cause);
  }

  /** Reviewed installer inputs; digests contain no account values or credentials. */
  public record Approval(UUID operation, String scriptFingerprint, String schemaFingerprint,
      String evidenceFingerprint) {
    /** Requires explicit operation, artifact, structural and domain-evidence identities. */
    public Approval {
      Objects.requireNonNull(operation);
      for (String digest : List.of(scriptFingerprint, schemaFingerprint, evidenceFingerprint)) {
        if (!digest.matches("[a-f0-9]{64}")) {
          throw new IllegalArgumentException("Invalid adoption approval digest");
        }
      }
    }
  }
}
