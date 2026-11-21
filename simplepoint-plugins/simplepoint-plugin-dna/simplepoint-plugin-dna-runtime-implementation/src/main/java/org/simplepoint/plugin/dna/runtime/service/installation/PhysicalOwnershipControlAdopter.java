package org.simplepoint.plugin.dna.runtime.service.installation;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Offline domain-owned adoption transaction. The installation coordinator must verify native
 * receipts and retire old writers before invoking it; no remote work occurs inside this
 * transaction.
 */
public final class PhysicalOwnershipControlAdopter {
  private static final String LOCK =
      "LOCK TABLE simpoint_dna_physical_models,"
          + "simpoint_dna_physical_bindings,simpoint_dna_physical_model_versions,"
          + "simpoint_dna_schema_migrations,simpoint_dna_relation_guards,"
          + "simpoint_dna_ownership_adoptions,simpoint_dna_retired_cleanup_evidence"
          + " IN SHARE ROW EXCLUSIVE MODE";
  private final DataSource storage;

  /**
   * Receives the explicitly selected DNA control store, never a global or request-selected source.
   */
  public PhysicalOwnershipControlAdopter(DataSource storage) {
    this.storage = Objects.requireNonNull(storage, "storage");
  }

  /** Commits all legacy namespace bindings and the immutable control receipt atomically. */
  public void adopt(PhysicalOwnershipAdoptionPlan plan) {
    execute(plan, true);
  }

  /** Reads an exact receipt after an uncertain response without retrying a write. */
  public boolean hasReceipt(PhysicalOwnershipAdoptionPlan plan) {
    return execute(plan, false);
  }

  private boolean execute(PhysicalOwnershipAdoptionPlan plan, boolean write) {
    Objects.requireNonNull(plan, "plan");
    PhysicalOwnershipInstallationAdapter.offline();
    try (var connection = storage.getConnection()) {
      if (!connection.getAutoCommit()
          || !"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
        throw reject("CONTROL_STORE");
      }
      try {
        connection.setAutoCommit(false);
        try (var settings = connection.createStatement()) {
          settings.setQueryTimeout(30);
          settings.execute(
              "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ" + (write ? "" : ", READ ONLY"));
          settings.execute("SET LOCAL row_security = off");
          settings.execute("SET LOCAL lock_timeout = '30s'");
          if (write) {
            // Lock before the first snapshot: retained/deleted rows and inserts are part of review.
            settings.execute(LOCK);
          }
        }
        boolean complete = receipt(connection, plan);
        if (write) {
          verify(connection, plan, complete);
          if (!complete) {
            updateBindings(connection, plan);
            verify(connection, plan, true);
            insertReceipt(connection, plan);
          }
          connection.commit();
        } else {
          connection.rollback();
        }
        connection.setAutoCommit(true);
        return complete || write;
      } catch (SQLException | RuntimeException failure) {
        try {
          connection.rollback();
        } catch (SQLException | RuntimeException rollback) {
          failure.addSuppressed(new IllegalStateException("Control adoption rollback failed"));
        }
        try {
          connection.abort(Runnable::run);
        } catch (SQLException | RuntimeException discard) {
          failure.addSuppressed(new IllegalStateException("Control adoption discard failed"));
        }
        throw failure;
      }
    } catch (SQLException failure) {
      // Driver messages can contain connection credentials or submitted values.
      throw new IllegalStateException(
          "DNA_OWNERSHIP_ADOPTION_UNAVAILABLE",
          new SQLException(
              "Control adoption failed", failure.getSQLState(), failure.getErrorCode()));
    }
  }

  private static void verify(
      Connection connection, PhysicalOwnershipAdoptionPlan plan, boolean adopted)
      throws SQLException {
    var expected = adopted ? plan.adoptedInventory() : plan.models().inventory();
    var actual = PhysicalOwnershipInstallationAdapter.read(connection);
    if (!actual.fingerprint().equals(expected.fingerprint())) {
      throw reject("CONTROL_CHANGED");
    }
    var expectedModels =
        new PhysicalOwnershipModelReview(
            1, expected, plan.models().definitions(), plan.models().publications());
    if (!PhysicalOwnershipModelReader.read(connection, actual)
        .fingerprint()
        .equals(expectedModels.fingerprint())) {
      throw reject("MODEL_CHANGED");
    }
  }

  private static void updateBindings(Connection connection, PhysicalOwnershipAdoptionPlan plan)
      throws SQLException {
    var adopted =
        plan.adoptedInventory().bindings().stream()
            .collect(java.util.stream.Collectors.toMap(b -> b.bindingId(), b -> b));
    try (var statement =
        connection.prepareStatement(
            "UPDATE simpoint_dna_physical_bindings SET"
                + " physical_namespace_id=?,lock_version=lock_version+1,"
                + "updated_at=CURRENT_TIMESTAMP"
                + " WHERE id=? AND physical_model_id=? AND tenant_id=? AND lock_version=? AND"
                + " physical_namespace_id IS NULL")) {
      statement.setQueryTimeout(30);
      for (var binding : plan.models().inventory().bindings()) {
        if (binding.namespaceId() != null) {
          continue;
        }
        statement.setString(1, adopted.get(binding.bindingId()).namespaceId());
        statement.setString(2, binding.bindingId());
        statement.setString(3, binding.modelId());
        statement.setString(4, binding.tenantId());
        statement.setLong(5, binding.revision());
        if (statement.executeUpdate() != 1) {
          throw reject("CONTROL_CHANGED");
        }
      }
    }
  }

  private static boolean receipt(Connection connection, PhysicalOwnershipAdoptionPlan plan)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT intent_fingerprint,review_fingerprint,"
                + "previous_inventory_fingerprint,adopted_inventory_fingerprint,"
                + "binding_count,completed_at"
                + " FROM simpoint_dna_ownership_adoptions WHERE operation_id=?")) {
      statement.setQueryTimeout(30);
      statement.setString(1, plan.operation().toString());
      try (var row = statement.executeQuery()) {
        if (!row.next()) {
          return false;
        }
        if (!plan.fingerprint().equals(row.getString(1))
            || !plan.reviewFingerprint().equals(row.getString(2))
            || !plan.models().inventory().fingerprint().equals(row.getString(3))
            || !plan.adoptedInventory().fingerprint().equals(row.getString(4))
            || plan.models().inventory().bindings().size() != row.getInt(5)
            || row.getTimestamp(6) == null
            || row.next()) {
          throw reject("RECEIPT_CONFLICT");
        }
        return true;
      }
    }
  }

  private static void insertReceipt(Connection connection, PhysicalOwnershipAdoptionPlan plan)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "INSERT INTO simpoint_dna_ownership_adoptions"
                + " (operation_id,intent_fingerprint,review_fingerprint,"
                + "previous_inventory_fingerprint,adopted_inventory_fingerprint,"
                + "binding_count,completed_at)"
                + " VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)")) {
      statement.setQueryTimeout(30);
      statement.setString(1, plan.operation().toString());
      statement.setString(2, plan.fingerprint());
      statement.setString(3, plan.reviewFingerprint());
      statement.setString(4, plan.models().inventory().fingerprint());
      statement.setString(5, plan.adoptedInventory().fingerprint());
      statement.setInt(6, plan.models().inventory().bindings().size());
      if (statement.executeUpdate() != 1) {
        throw reject("RECEIPT_CONFLICT");
      }
    }
  }

  private static IllegalStateException reject(String reason) {
    return new IllegalStateException("DNA_OWNERSHIP_ADOPTION_" + reason);
  }
}
