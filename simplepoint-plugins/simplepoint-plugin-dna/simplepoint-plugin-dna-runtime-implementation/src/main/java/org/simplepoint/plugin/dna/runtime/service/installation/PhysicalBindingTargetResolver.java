package org.simplepoint.plugin.dna.runtime.service.installation;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.simplepoint.data.ownership.PhysicalIdentifierRules;
import org.simplepoint.data.ownership.PhysicalOwnershipException;
import org.simplepoint.data.ownership.PhysicalOwnershipInspection;
import org.simplepoint.data.ownership.PhysicalOwnershipInstaller;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingInventory;

/** Explicit offline native-target resolution; no RPC, automatic discovery, reconnect or writes. */
public final class PhysicalBindingTargetResolver {
  private PhysicalBindingTargetResolver() {}

  /**
   * Resolves every retained binding after the control read has released its connection. Sources are
   * explicitly bound by exact managed datasource ID and persisted catalog, including empty
   * defaults. Missing/extra bindings fail before any connection; credentials never enter results.
   */
  public static PhysicalBindingTargetReview review(
      PhysicalBindingInventory inventory, Map<Source, DataSource> sources) {
    PhysicalOwnershipInstallationAdapter.offline();
    Objects.requireNonNull(inventory, "inventory");
    sources = Map.copyOf(sources);
    var grouped = new HashMap<Source, ArrayList<PhysicalBindingInventory.Binding>>();
    for (var binding : inventory.bindings()) {
      var target = binding.target();
      grouped
          .computeIfAbsent(
              new Source(target.dataSourceId(), target.catalogName()), ignored -> new ArrayList<>())
          .add(binding);
    }
    if (!grouped.keySet().equals(sources.keySet())) {
      throw conflict("SOURCE_BINDINGS");
    }
    var resolved = new ArrayList<PhysicalBindingTargetReview.Binding>();
    // Stable connection order makes review failures reproducible; no transaction spans sources.
    var ordered =
        new TreeMap<Source, ArrayList<PhysicalBindingInventory.Binding>>(
            java.util.Comparator.comparing(Source::dataSourceId)
                .thenComparing(Source::catalogName));
    ordered.putAll(grouped);
    for (var entry : ordered.entrySet()) {
      try (var connection = sources.get(entry.getKey()).getConnection()) {
        if (!connection.getAutoCommit()) {
          throw conflict("TRANSACTION");
        }
        var rules = PhysicalIdentifierRules.on(connection);
        String catalog = catalog(connection, rules.postgres());
        if (!entry.getKey().catalogName().isEmpty()
            && !entry.getKey().catalogName().equals(catalog)) {
          throw conflict("CATALOG");
        }
        var namespaces = new HashMap<String, PhysicalOwnershipInspection>();
        for (var binding : entry.getValue()) {
          String schema = binding.target().schemaName();
          if (!rules.postgres() && !schema.isEmpty() && !schema.equals(catalog)) {
            throw conflict("SCHEMA");
          }
          String namespace =
              rules.postgres() ? (schema.isEmpty() ? connection.getSchema() : schema) : catalog;
          if (namespace == null || namespace.isBlank()) {
            throw conflict("NAMESPACE");
          }
          var inspection = namespaces.get(namespace);
          if (inspection == null) {
            inspection = PhysicalOwnershipInstaller.inspect(connection, namespace);
            if (!catalog.equals(inspection.target().database())) {
              throw conflict("CATALOG");
            }
            namespaces.put(namespace, inspection);
          }
          if (binding.namespaceId() != null
              && (!inspection.complete()
                  || !binding.namespaceId().equals(inspection.namespaceId().toString()))) {
            throw conflict("NAMESPACE_IDENTITY");
          }
          resolved.add(
              new PhysicalBindingTargetReview.Binding(
                  binding.bindingId(),
                  inspection,
                  rules.canonical(binding.target().tableName(), true)));
        }
        // Detect namespace replacement or directory installation during the bounded source read.
        for (var namespace : namespaces.entrySet()) {
          if (!namespace
              .getValue()
              .equals(PhysicalOwnershipInstaller.inspect(connection, namespace.getKey()))) {
            throw conflict("NAMESPACE_CHANGED");
          }
        }
      } catch (PhysicalOwnershipException failure) {
        // Preserve the typed infrastructure diagnostic, but not driver causes/suppressed errors.
        throw new IllegalStateException(
            "DNA_OWNERSHIP_TARGET_" + failure.code().name(),
            new PhysicalOwnershipException(failure.code()));
      } catch (SQLException failure) {
        throw new IllegalStateException(
            "DNA_OWNERSHIP_TARGET_UNAVAILABLE",
            new SQLException(
                "Native target review failed", failure.getSQLState(), failure.getErrorCode()));
      }
    }
    return new PhysicalBindingTargetReview(1, inventory.fingerprint(), resolved);
  }

  private static String catalog(Connection connection, boolean postgres) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(10);
      try (var rows =
          statement.executeQuery(
              postgres ? "SELECT pg_catalog.current_database()" : "SELECT DATABASE()")) {
        if (!rows.next()) {
          throw conflict("CATALOG");
        }
        String value = rows.getString(1);
        if (value == null || value.isBlank() || rows.next()) {
          throw conflict("CATALOG");
        }
        return value;
      }
    }
  }

  private static IllegalStateException conflict(String code) {
    return new IllegalStateException("DNA_OWNERSHIP_TARGET_" + code);
  }

  /** Exact persisted source coordinates; offline callers supply an already connected catalog. */
  public record Source(String dataSourceId, String catalogName) {
    /** Preserves the empty default-catalog sentinel and does not trim native identifiers. */
    public Source {
      if (dataSourceId == null
          || dataSourceId.isBlank()
          || dataSourceId.length() > 64
          || catalogName == null
          || catalogName.length() > 128
          || dataSourceId.indexOf('\0') >= 0
          || catalogName.indexOf('\0') >= 0) {
        throw new IllegalArgumentException("Invalid physical target source binding");
      }
    }
  }
}
