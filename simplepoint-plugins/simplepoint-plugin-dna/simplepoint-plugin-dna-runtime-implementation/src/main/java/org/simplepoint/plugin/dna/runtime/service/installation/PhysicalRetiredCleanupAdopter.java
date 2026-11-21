package org.simplepoint.plugin.dna.runtime.service.installation;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import javax.sql.DataSource;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingInventory.Binding;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingInventory.Model;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;

/** Offline recovery of original binding reservations; preserves model counters and all grants. */
public final class PhysicalRetiredCleanupAdopter {
  private final DataSource storage;

  public PhysicalRetiredCleanupAdopter(DataSource storage) {
    this.storage = Objects.requireNonNull(storage, "storage");
  }

  /** Read-only preflight includes every missing binding, rather than excluding unknown orphans. */
  public void review(PhysicalRetiredCleanupPlan plan) {
    execute(plan, false);
  }

  /** Old writers and exact native absence must be verified by the offline coordinator first. */
  public void adopt(PhysicalRetiredCleanupPlan plan) {
    execute(plan, true);
  }

  /** Resolves a lost acknowledgement without reexecuting a write. */
  public boolean hasReceipt(PhysicalRetiredCleanupPlan plan) {
    PhysicalOwnershipInstallationAdapter.offline();
    try (var connection = storage.getConnection()) {
      return receipts(connection, plan);
    } catch (SQLException failure) {
      throw unavailable(failure);
    }
  }

  private void execute(PhysicalRetiredCleanupPlan plan, boolean write) {
    Objects.requireNonNull(plan, "plan");
    PhysicalOwnershipInstallationAdapter.offline();
    try (var connection = storage.getConnection()) {
      if (!connection.getAutoCommit()
          || !"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
        throw reject("CONTROL_STORE");
      }
      try {
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
          statement.setQueryTimeout(30);
          statement.execute(
              "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ" + (write ? "" : ", READ ONLY"));
          statement.execute("SET LOCAL row_security = off");
          statement.execute("SET LOCAL lock_timeout = '30s'");
          if (write) {
            statement.execute(
                "LOCK TABLE simpoint_dna_physical_models,"
                    + "simpoint_dna_physical_bindings,simpoint_dna_physical_model_versions,"
                    + "simpoint_dna_schema_migrations,simpoint_dna_resource_projections,"
                    + "simpoint_dna_retired_cleanup_evidence IN SHARE ROW EXCLUSIVE MODE");
          }
        }
        boolean complete = receipts(connection, plan);
        verify(connection, plan, complete);
        if (write && !complete) {
          insert(connection, plan);
        }
        if (write) {
          connection.commit();
        } else {
          connection.rollback();
        }
        connection.setAutoCommit(true);
      } catch (SQLException | RuntimeException failure) {
        try {
          connection.rollback();
        } catch (SQLException rollback) {
          failure.addSuppressed(new IllegalStateException("Cleanup adoption rollback failed"));
        }
        try {
          connection.abort(Runnable::run);
        } catch (SQLException discard) {
          failure.addSuppressed(new IllegalStateException("Cleanup adoption discard failed"));
        }
        throw failure;
      }
    } catch (SQLException failure) {
      throw unavailable(failure);
    }
  }

  private static void verify(
      Connection connection, PhysicalRetiredCleanupPlan plan, boolean complete)
      throws SQLException {
    var missing = new HashSet<String>();
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(30);
      try (var rows =
          statement.executeQuery(
              "SELECT m.id FROM simpoint_dna_physical_models m"
                  + " LEFT JOIN simpoint_dna_physical_bindings b ON b.physical_model_id=m.id"
                  + " WHERE b.id IS NULL LIMIT 10001")) {
        while (rows.next()) {
          missing.add(rows.getString(1));
        }
      }
    }
    var expected =
        plan.inventory().bindings().stream()
            .map(Binding::modelId)
            .collect(java.util.stream.Collectors.toSet());
    if (complete ? !missing.isEmpty() : !missing.equals(expected)) {
      throw reject("INCOMPLETE_SCOPE");
    }
    String sql =
        "SELECT m.*,"
            + " EXISTS(SELECT 1 FROM simpoint_dna_physical_model_versions v"
            + " WHERE v.physical_model_id=m.id) AS has_versions,"
            + " EXISTS(SELECT 1 FROM simpoint_dna_schema_migrations p"
            + " WHERE p.physical_model_id=m.id) AS has_publications,"
            + " EXISTS(SELECT 1 FROM simpoint_dna_resource_projections r"
            + " WHERE r.physical_model_id=m.id AND NOT r.formally_offline) AS has_live_projection"
            + " FROM simpoint_dna_physical_models m WHERE m.id=?";
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(30);
      for (var binding : plan.inventory().bindings()) {
        statement.setString(1, binding.modelId());
        try (var row = statement.executeQuery()) {
          if (!row.next()) {
            throw reject("MODEL_CHANGED");
          }
          var model =
              new Model(
                  row.getString("app_id"),
                  row.getString("form_id"),
                  PhysicalModelStatus.valueOf(row.getString("status")),
                  row.getInt("latest_version"),
                  row.getObject("active_version", Integer.class),
                  row.getLong("lock_version"),
                  instant(row.getTimestamp("deleted_at")));
          if (!binding.tenantId().equals(row.getString("tenant_id"))
              || !binding.model().equals(model)
              || row.getBoolean("has_versions")
              || row.getBoolean("has_publications")
              || row.getBoolean("has_live_projection")
              || row.next()) {
            throw reject("MODEL_CHANGED");
          }
        }
      }
    }
    if (complete) {
      var actual = PhysicalOwnershipInstallationAdapter.read(connection).bindings();
      if (!actual.containsAll(plan.inventory().bindings())) {
        throw reject("BINDING_CHANGED");
      }
    }
  }

  private static void insert(Connection connection, PhysicalRetiredCleanupPlan plan)
      throws SQLException {
    try (var binding =
            connection.prepareStatement(
                "INSERT INTO simpoint_dna_physical_bindings"
                    + " (id,physical_model_id,tenant_id,data_source_id,catalog_name,schema_name,table_name,"
                    + "isolation_mode,physical_namespace_id,lock_version,created_at,updated_at,deleted_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?)");
        var evidence =
            connection.prepareStatement(
                "INSERT INTO simpoint_dna_retired_cleanup_evidence"
                    + " (model_id,binding_id,evidence_checksum,operation_id,intent_fingerprint,completed_at)"
                    + " VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)")) {
      binding.setQueryTimeout(30);
      evidence.setQueryTimeout(30);
      for (var entry : plan.inventory().bindings()) {
        binding.setString(1, entry.bindingId());
        binding.setString(2, entry.modelId());
        binding.setString(3, entry.tenantId());
        binding.setString(4, entry.target().dataSourceId());
        binding.setString(5, entry.target().catalogName());
        binding.setString(6, entry.target().schemaName());
        binding.setString(7, entry.target().tableName());
        binding.setString(8, entry.target().isolationMode().name());
        binding.setString(9, entry.namespaceId());
        binding.setLong(10, entry.revision());
        binding.setTimestamp(11, java.sql.Timestamp.from(entry.deletedAt()));
        binding.executeUpdate();
        evidence.setString(1, entry.modelId());
        evidence.setString(2, entry.bindingId());
        evidence.setString(3, plan.evidenceChecksum());
        evidence.setString(4, plan.operation().toString());
        evidence.setString(5, plan.fingerprint());
        evidence.executeUpdate();
      }
    }
  }

  private static boolean receipts(Connection connection, PhysicalRetiredCleanupPlan plan)
      throws SQLException {
    int found = 0;
    try (var statement =
        connection.prepareStatement(
            "SELECT binding_id,evidence_checksum,"
                + "operation_id,intent_fingerprint FROM simpoint_dna_retired_cleanup_evidence"
                + " WHERE model_id=?")) {
      statement.setQueryTimeout(30);
      for (var binding : plan.inventory().bindings()) {
        statement.setString(1, binding.modelId());
        try (var row = statement.executeQuery()) {
          if (!row.next()) {
            continue;
          }
          if (!binding.bindingId().equals(row.getString(1))
              || !plan.evidenceChecksum().equals(row.getString(2))
              || !plan.operation().toString().equals(row.getString(3))
              || !plan.fingerprint().equals(row.getString(4))
              || row.next()) {
            throw reject("RECEIPT_CONFLICT");
          }
          found++;
        }
      }
    }
    if (found != 0 && found != plan.inventory().bindings().size()) {
      throw reject("PARTIAL_RECEIPT");
    }
    return found != 0;
  }

  private static Instant instant(java.sql.Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private static IllegalStateException unavailable(SQLException failure) {
    return new IllegalStateException(
        "DNA_RETIRED_CLEANUP_UNAVAILABLE",
        new SQLException(
            "Retired cleanup adoption failed", failure.getSQLState(), failure.getErrorCode()));
  }

  private static IllegalStateException reject(String code) {
    return new IllegalStateException("DNA_RETIRED_CLEANUP_" + code);
  }
}
