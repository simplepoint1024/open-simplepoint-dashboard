package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.impl.FederationSqlStatementBoundary.unsupported;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;

/** Refuses native overload resolution that could reinterpret the admitted builtin vocabulary. */
final class FederationNativeFunctionResolution {
  private static final Set<String> MYSQL_SPACE_SENSITIVE =
      Set.of(
          "CAST",
          "COUNT",
          "EXTRACT",
          "MAX",
          "MIN",
          "POSITION",
          "SUBSTR",
          "SUBSTRING",
          "SUM",
          "TRIM");

  private FederationNativeFunctionResolution() {}

  static void mysqlSource(String sql) {
    // MySQL can interpret COUNT (x), for example, as a stored function without IGNORE_SPACE.
    // The admitted vocabulary means builtin functions. Do not depend on mutable session modes.
    var tokens = FederationSqlTokens.read(sql);
    for (int index = 0; index + 1 < tokens.size(); index++) {
      var token = tokens.get(index);
      var next = tokens.get(index + 1);
      if (token.kind() == FederationSqlTokens.Kind.WORD
          && MYSQL_SPACE_SENSITIVE.contains(token.value().toUpperCase(Locale.ROOT))
          && next.is("(")
          && token.end() != next.start()) {
        throw unsupported();
      }
    }
  }

  static void postgres(Connection connection) throws SQLException {
    // pg_catalog first does NOT rule out a later schema's more exact argument overload. Reject
    // conflicting visible routines, including variadic/default-argument variants, without invoking
    // them. A database administrator replacing pg_catalog itself is outside this SQL boundary.
    String routines =
        "SELECT 1 FROM pg_catalog.pg_proc p"
            + " JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace"
            + " WHERE n.nspname<>'pg_catalog' AND n.nspname=ANY(pg_catalog.current_schemas(true))"
            + " AND p.proname=ANY(?) LIMIT 1";
    var names =
        FederationWriteExpressionPolicy.builtinFunctions().stream()
            .map(name -> name.toLowerCase(Locale.ROOT))
            .toArray(String[]::new);
    var array = connection.createArrayOf("pg_catalog.text", names);
    try (var statement = connection.prepareStatement(routines)) {
      statement.setQueryTimeout(10);
      statement.setArray(1, array);
      try (var rows = statement.executeQuery()) {
        if (rows.next()) {
          throw unsupported();
        }
      }
    } finally {
      array.free();
    }
    reject(
        connection,
        "SELECT 1 FROM pg_catalog.pg_operator op JOIN pg_catalog.pg_namespace n ON"
            + " n.oid=op.oprnamespace WHERE n.nspname<>'pg_catalog' AND"
            + " n.nspname=ANY(pg_catalog.current_schemas(true)) LIMIT 1");
    reject(
        connection,
        "SELECT 1 FROM pg_catalog.pg_cast c"
            + " JOIN pg_catalog.pg_type s ON s.oid=c.castsource"
            + " JOIN pg_catalog.pg_type t ON t.oid=c.casttarget"
            + " JOIN pg_catalog.pg_namespace sn ON sn.oid=s.typnamespace"
            + " JOIN pg_catalog.pg_namespace tn ON tn.oid=t.typnamespace"
            + " JOIN pg_catalog.pg_proc p ON p.oid=c.castfunc"
            + " JOIN pg_catalog.pg_namespace pn ON pn.oid=p.pronamespace"
            + " WHERE sn.nspname='pg_catalog' AND tn.nspname='pg_catalog'"
            + " AND pn.nspname<>'pg_catalog' LIMIT 1");
    reject(
        connection,
        "SELECT 1 FROM pg_catalog.pg_type t JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace"
            + " WHERE n.nspname<>'pg_catalog' AND n.nspname=ANY(pg_catalog.current_schemas(true))"
            + " AND t.typname IN ('uuid','json','jsonb','text','bytea','serial','bigserial') LIMIT"
            + " 1");
  }

  private static void reject(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(10);
      try (var rows = statement.executeQuery(sql)) {
        if (rows.next()) {
          throw unsupported();
        }
      }
    }
  }
}
