package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.impl.FederationSqlStatementBoundary.unsupported;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.simplepoint.data.ownership.PhysicalIdentifierRules;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException.Code;
import org.simplepoint.plugin.dna.federation.service.impl.FederationDdlPlan.ObjectKind;
import org.simplepoint.plugin.dna.federation.service.impl.FederationDdlPlan.Operation;
import org.simplepoint.plugin.dna.federation.service.impl.FederationDdlPlan.Role;

/** Original native names resolved on the executing connection, including absent destinations. */
final class FederationNativeSqlObjects {
  private final Connection connection;
  private final PhysicalIdentifierRules rules;
  private final String sourceCode;
  private final LinkedHashMap<String, Use> namespaceGuards = new LinkedHashMap<>();

  FederationNativeSqlObjects(Connection connection, String sourceCode) throws SQLException {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.rules = PhysicalIdentifierRules.on(connection);
    this.sourceCode = Objects.requireNonNull(sourceCode, "sourceCode");
  }

  List<Use> resolve(FederationDmlPlan plan) throws SQLException {
    namespaceGuards.clear();
    verifyFunctionResolution(plan.sourceSql());
    var uses = new ArrayList<Use>();
    for (var object : plan.objects()) {
      var name = name(object.names(), object.quoted());
      var table = lookup(name);
      if (table.kind() == null) {
        throw new FederationSqlWriteException(Code.SQL_WRITE_OWNERSHIP_UNAVAILABLE);
      }
      add(uses, table, object.role() == FederationDmlPlan.Role.TARGET);
    }
    verifyDmlEffects(uses);
    return finish(uses);
  }

  List<Use> resolve(FederationDdlPlan plan) throws SQLException {
    namespaceGuards.clear();
    verifyFunctionResolution(plan.sourceSql());
    if (plan.temporary()
        || plan.cascade()
        || rules.postgres() && plan.operation() == Operation.RENAME) {
      // Temporary namespaces and cascading dependency closure are not silently approximated.
      throw unsupported();
    }
    var uses = new ArrayList<Use>();
    Table anchor = null;
    Table indexOwner = null;
    for (var object : plan.objects()) {
      if (object.role() == Role.INDEX_OWNER) {
        indexOwner = lookup(name(object.names(), object.quoted()));
        break;
      }
    }
    for (var object : plan.objects()) {
      var name = name(object.names(), object.quoted());
      if (object.kind() == ObjectKind.INDEX && !rules.postgres()) {
        // MySQL index identities are local to their explicitly named table, not database tables.
        if (indexOwner == null || name.size() != 1) {
          throw unsupported();
        }
        continue;
      }
      boolean write = object.role() != Role.DEPENDENCY;
      Table table;
      if (object.role() == Role.RENAME_TARGET) {
        if (anchor == null) {
          throw unsupported();
        }
        String destination =
            rules.postgres()
                ? anchor.namespace()
                : name.size() == 1 ? defaultNamespace() : name.getFirst();
        table = creation(name, destination);
      } else if (plan.operation() == Operation.CREATE && object.role() == Role.TARGET) {
        String destination =
            object.kind() == ObjectKind.INDEX && indexOwner != null
                ? indexOwner.namespace()
                : name.size() == 1 ? defaultNamespace() : name.getFirst();
        table = creation(name, destination);
      } else {
        if (!rules.postgres()
            && object.role() == Role.REFERENCE_TARGET
            && name.size() == 1
            && anchor != null) {
          name = List.of(anchor.namespace(), name.getFirst());
        }
        table = lookup(name);
      }
      add(uses, table, write);
      if (object.role() == Role.TARGET && object.kind() != ObjectKind.INDEX) {
        anchor = table;
      }
    }
    FederationNativeDdlEffects.verify(connection, rules.postgres(), uses);
    return finish(uses);
  }

  /** Re-resolves the adapter's exact qualified name on its owned execution connection. */
  List<Use> resolveBound(String namespace, String table, boolean write) throws SQLException {
    if (rules.postgres()) {
      FederationNativeFunctionResolution.postgres(connection);
    }
    var uses = new ArrayList<Use>();
    var resolved = lookup(List.of(rules.canonical(namespace, true), rules.canonical(table, true)));
    if (resolved.kind() == null) {
      throw new FederationSqlWriteException(Code.SQL_WRITE_OWNERSHIP_UNAVAILABLE);
    }
    add(uses, resolved, write);
    verifyDmlEffects(uses);
    return List.copyOf(uses);
  }

  private void verifyDmlEffects(List<Use> uses) throws SQLException {
    for (var use : uses) {
      FederationNativeTableEffects.verify(connection, rules.postgres(), use);
    }
  }

  private void verifyFunctionResolution(String sql) throws SQLException {
    if (rules.postgres()) {
      FederationNativeFunctionResolution.postgres(connection);
    } else {
      FederationNativeFunctionResolution.mysqlSource(sql);
    }
  }

  private List<Use> finish(List<Use> uses) {
    uses.addAll(namespaceGuards.values());
    if (uses.size() > 512) {
      throw unsupported();
    }
    return List.copyOf(uses);
  }

  private List<String> name(List<String> names, List<Boolean> quoted) throws SQLException {
    if (names.size() != quoted.size() || names.isEmpty()) {
      throw unsupported();
    }
    int start = names.size() > 1 && names.getFirst().equalsIgnoreCase(sourceCode) ? 1 : 0;
    var result = new ArrayList<String>();
    for (int index = start; index < names.size(); index++) {
      result.add(rules.canonical(names.get(index), quoted.get(index)));
    }
    if (rules.postgres() && result.size() == 3) {
      if (!Objects.equals(connection.getCatalog(), result.getFirst())) {
        throw new FederationSqlWriteException(Code.SQL_WRITE_SCOPE_INVALID);
      }
      result.removeFirst();
    }
    if (result.isEmpty() || result.size() > 2) {
      throw new FederationSqlWriteException(Code.SQL_WRITE_SCOPE_INVALID);
    }
    return List.copyOf(result);
  }

  private Table creation(List<String> name, String defaultNamespace) throws SQLException {
    String namespace = name.size() == 2 ? name.getFirst() : defaultNamespace;
    var existing = lookup(List.of(namespace, name.getLast()));
    return new Table(namespace, name.getLast(), existing.identity(), existing.kind());
  }

  private Table lookup(List<String> name) throws SQLException {
    if (rules.postgres()) {
      if (name.size() == 1) {
        protectSearchPath();
      }
      String sql =
          "SELECT n.nspname, c.relname, c.oid::text, c.relkind FROM pg_catalog.pg_class c"
              + " JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace"
              + " WHERE c.oid=pg_catalog.to_regclass(?)";
      try (var statement = connection.prepareStatement(sql)) {
        statement.setQueryTimeout(10);
        statement.setString(1, String.join(".", name.stream().map(rules::quote).toList()));
        try (var rows = statement.executeQuery()) {
          if (rows.next()) {
            var table =
                new Table(
                    rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4));
            if (rows.next()) {
              throw unsupported();
            }
            return table;
          }
        }
      }
      return new Table(
          name.size() == 2 ? name.getFirst() : defaultNamespace(), name.getLast(), null, null);
    }
    String namespace = name.size() == 2 ? name.getFirst() : defaultNamespace();
    String table = name.getLast();
    rejectTemporaryShadow(namespace, table);
    String sql =
        "SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE FROM information_schema.TABLES"
            + " WHERE BINARY TABLE_SCHEMA=? AND BINARY TABLE_NAME=?";
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(10);
      statement.setString(1, namespace);
      statement.setString(2, table);
      try (var rows = statement.executeQuery()) {
        if (rows.next()) {
          var resolved = new Table(rows.getString(1), rows.getString(2), null, rows.getString(3));
          if (rows.next()) {
            throw unsupported();
          }
          return resolved;
        }
      }
    }
    return new Table(namespace, table, null, null);
  }

  private void rejectTemporaryShadow(String namespace, String table) throws SQLException {
    // information_schema omits session temporary tables, including a qualified-name shadow.
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(10);
      try (var rows =
          statement.executeQuery(
              "SHOW CREATE TABLE " + rules.quote(namespace) + "." + rules.quote(table))) {
        if (!rows.next()) {
          throw unsupported();
        }
        try (var definition = rows.getCharacterStream(2)) {
          char[] prefix = new char[128];
          int length = 0;
          int count;
          while (length < prefix.length
              && (count = definition.read(prefix, length, prefix.length - length)) != -1) {
            length += count;
          }
          if (length <= 0
              || new String(prefix, 0, length)
                  .toUpperCase(Locale.ROOT)
                  .startsWith("CREATE TEMPORARY TABLE")) {
            throw unsupported();
          }
        } catch (java.io.IOException failure) {
          throw new SQLException("Native object metadata could not be read", failure);
        }
      }
    } catch (SQLException failure) {
      if (failure.getErrorCode() != 1146 || !"42S02".equals(failure.getSQLState())) {
        throw failure;
      }
    }
  }

  private String defaultNamespace() throws SQLException {
    if (rules.postgres()) {
      protectSearchPath();
    }
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(10);
      try (var rows =
          statement.executeQuery(
              rules.postgres() ? "SELECT pg_catalog.current_schema()" : "SELECT DATABASE()")) {
        if (!rows.next()) {
          throw unsupported();
        }
        String namespace = rows.getString(1);
        if (namespace == null || namespace.isBlank() || rows.next()) {
          throw unsupported();
        }
        return namespace;
      }
    }
  }

  private void protectSearchPath() throws SQLException {
    // A new managed table in an earlier schema must not shadow a checked, later-schema table
    // between metadata lookup and native execution. Lock every eligible search-path candidate.
    String sql =
        "SELECT n.nspname, n.oid::text FROM pg_catalog.pg_namespace n JOIN"
            + " pg_catalog.unnest(pg_catalog.current_schemas(true)) WITH ORDINALITY p(name,"
            + " position) ON n.nspname=p.name ORDER BY p.position LIMIT 65";
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(10);
      try (var rows = statement.executeQuery(sql)) {
        int count = 0;
        while (rows.next()) {
          if (++count > 64) {
            throw unsupported();
          }
          String name = rows.getString(1);
          String lower = name.toLowerCase(Locale.ROOT);
          if (!lower.startsWith("pg_") && !lower.equals("information_schema")) {
            namespaceGuards.put(name, new Use(name, null, rows.getString(2), "NAMESPACE", false));
          }
        }
      }
    }
  }

  private void add(List<Use> uses, Table table, boolean write) throws SQLException {
    if (uses.size() >= 512) {
      throw unsupported();
    }
    uses.add(new Use(table.namespace(), table.name(), table.identity(), table.kind(), write));
    if (rules.postgres() && ("i".equals(table.kind()) || "I".equals(table.kind()))) {
      String sql =
          "SELECT n.nspname, c.relname, c.oid::text, c.relkind FROM pg_catalog.pg_index i"
              + " JOIN pg_catalog.pg_class c ON c.oid=i.indrelid"
              + " JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace"
              + " WHERE i.indexrelid=CAST(? AS pg_catalog.oid)";
      try (var statement = connection.prepareStatement(sql)) {
        statement.setQueryTimeout(10);
        statement.setString(1, table.identity());
        try (var rows = statement.executeQuery()) {
          if (!rows.next()) {
            throw unsupported();
          }
          uses.add(
              new Use(
                  rows.getString(1),
                  rows.getString(2),
                  rows.getString(3),
                  rows.getString(4),
                  write));
          if (rows.next()) {
            throw unsupported();
          }
        }
      }
    }
  }

  record Use(String namespace, String table, String identity, String kind, boolean write) {}

  private record Table(String namespace, String name, String identity, String kind) {}
}
