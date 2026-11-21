package org.simplepoint.plugin.dna.runtime.service.installation;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Objects;
import javax.sql.DataSource;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingInventory;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingInventory.Binding;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingInventory.Model;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingInventory.Target;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalIsolationMode;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.api.service.PhysicalOwnershipMaintenance;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;

/** Explicit offline adapter with owned storage; no component scan, HTTP or RPC declaration. */
public final class PhysicalOwnershipInstallationAdapter implements PhysicalOwnershipMaintenance {
  private static final String INVENTORY =
      """
      SELECT b.id AS binding_id, b.physical_model_id, b.tenant_id AS binding_tenant,
        b.data_source_id, b.catalog_name, b.schema_name, b.table_name, b.isolation_mode,
        b.physical_namespace_id, b.lock_version AS binding_revision,
        b.deleted_at AS binding_deleted,
        m.id AS model_id, m.tenant_id AS model_tenant, m.app_id, m.form_id, m.status,
        m.latest_version, m.active_version, m.lock_version AS model_revision,
        m.deleted_at AS model_deleted, c.evidence_checksum AS cleanup_evidence
      FROM simpoint_dna_physical_bindings b
      FULL OUTER JOIN simpoint_dna_physical_models m ON m.id=b.physical_model_id
      LEFT JOIN simpoint_dna_retired_cleanup_evidence c ON c.model_id=m.id AND c.binding_id=b.id
      ORDER BY b.id COLLATE "C", m.id COLLATE "C"
      LIMIT ?
      """;
  private final DataSource controlStorage;

  /** Receives only the selected dna-runtime control store from the offline module binding. */
  public PhysicalOwnershipInstallationAdapter(DataSource controlStorage) {
    this.controlStorage = Objects.requireNonNull(controlStorage, "controlStorage");
  }

  @Override
  public PhysicalBindingInventory inventory() {
    return query(PhysicalOwnershipInstallationAdapter::read);
  }

  /** Reads immutable definitions and completed publications in one offline control snapshot. */
  public PhysicalOwnershipModelReview models(PhysicalBindingInventory expected) {
    Objects.requireNonNull(expected, "expected");
    return query(
        connection -> {
          var current = read(connection);
          if (!current.fingerprint().equals(expected.fingerprint())) {
            throw conflict("CHANGED");
          }
          return PhysicalOwnershipModelReader.read(connection, current);
        });
  }

  private <T> T query(SqlRead<T> reader) {
    offline();
    // One bounded PostgreSQL statement gives a consistent snapshot without retaining a transaction
    // during subsequent physical-target review. JDBC intentionally bypasses request-scoped filters.
    try (var connection = controlStorage.getConnection()) {
      if (!connection.getAutoCommit()
          || !"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
        throw conflict("CONTROL_STORE");
      }
      return snapshot(connection, reader);
    } catch (SQLException failure) {
      // Driver errors may include URLs. Offline reports retain diagnostic state, never credentials.
      throw new IllegalStateException(
          "DNA_OWNERSHIP_INVENTORY_UNAVAILABLE",
          new SQLException(
              "Control binding inventory read failed",
              failure.getSQLState(),
              failure.getErrorCode()));
    } catch (IllegalArgumentException failure) {
      throw conflict("INVALID_BINDING");
    }
  }

  private static <T> T snapshot(Connection connection, SqlRead<T> reader) throws SQLException {
    try {
      connection.setAutoCommit(false);
      try (var settings = connection.createStatement()) {
        settings.setQueryTimeout(30);
        settings.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY");
        // PostgreSQL then fails if row security would filter a table instead of quietly producing
        // an incomplete all-tenant inventory. This setting does not grant an RLS bypass.
        settings.execute("SET LOCAL row_security = off");
      }
      var result = reader.read(connection);
      connection.rollback();
      connection.setAutoCommit(true);
      return result;
    } catch (SQLException | RuntimeException failure) {
      // Never restore autocommit after uncertain rollback or return a failed read scope to a pool.
      try {
        connection.abort(Runnable::run);
      } catch (SQLException | RuntimeException discardFailure) {
        failure.addSuppressed(new IllegalStateException("Inventory connection discard failed"));
      }
      throw failure;
    }
  }

  @FunctionalInterface
  private interface SqlRead<T> {
    T read(Connection connection) throws SQLException;
  }

  static PhysicalBindingInventory read(Connection connection) throws SQLException {
    try (var statement = connection.prepareStatement(INVENTORY)) {
      statement.setQueryTimeout(30);
      statement.setInt(1, PhysicalBindingInventory.MAX_BINDINGS + 1);
      var bindings = new ArrayList<Binding>();
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          if (bindings.size() == PhysicalBindingInventory.MAX_BINDINGS) {
            throw conflict("LIMIT");
          }
          bindings.add(binding(rows));
        }
      }
      return new PhysicalBindingInventory(1, bindings);
    }
  }

  private static Binding binding(ResultSet rows) throws SQLException {
    String binding = rows.getString("binding_id");
    String model = rows.getString("model_id");
    String tenant = rows.getString("model_tenant");
    if (binding == null
        || model == null
        || !model.equals(rows.getString("physical_model_id"))
        || tenant == null
        || !tenant.equals(rows.getString("binding_tenant"))) {
      throw conflict("INCOMPLETE_BINDING");
    }
    return new Binding(
        binding,
        model,
        tenant,
        new Target(
            rows.getString("data_source_id"),
            rows.getString("catalog_name"),
            rows.getString("schema_name"),
            rows.getString("table_name"),
            PhysicalIsolationMode.valueOf(rows.getString("isolation_mode"))),
        rows.getString("physical_namespace_id"),
        rows.getLong("binding_revision"),
        instant(rows, "binding_deleted"),
        new Model(
            rows.getString("app_id"),
            rows.getString("form_id"),
            PhysicalModelStatus.valueOf(rows.getString("status")),
            rows.getInt("latest_version"),
            rows.getObject("active_version", Integer.class),
            rows.getLong("model_revision"),
            instant(rows, "model_deleted")),
        rows.getString("cleanup_evidence"));
  }

  private static Instant instant(ResultSet rows, String column) throws SQLException {
    var value = rows.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  static void offline() {
    if (RequestContextHolder.getRequestAttributes() != null
        || AuthorizationContextHolder.getContext() != null
        || org.simplepoint.core.RequestContextHolder.getContext(
                org.simplepoint.core.RequestContextHolder.TENANT_CONTEXT_KEY, Object.class)
            != null
        || org.simplepoint.core.RequestContextHolder.getContext(
                org.simplepoint.core.RequestContextHolder.USER_CONTEXT_KEY, Object.class)
            != null
        || SecurityContextHolder.getContext().getAuthentication() != null
        || TransactionSynchronizationManager.isActualTransactionActive()) {
      throw conflict("OFFLINE_REQUIRED");
    }
  }

  private static IllegalStateException conflict(String code) {
    return new IllegalStateException("DNA_OWNERSHIP_INVENTORY_" + code);
  }
}
