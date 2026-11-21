package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.impl.FederationSqlStatementBoundary.unsupported;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

/** Native expression dependencies, inspected without executing user functions or default values. */
final class FederationPostgresExpressionEffects {
  private static final Set<String> TYPES =
      Set.of(
          "bool",
          "int2",
          "int4",
          "int8",
          "float4",
          "float8",
          "numeric",
          "money",
          "text",
          "varchar",
          "bpchar",
          "bytea",
          "date",
          "time",
          "timetz",
          "timestamp",
          "timestamptz",
          "interval",
          "uuid",
          "json",
          "jsonb",
          "bit",
          "varbit");

  private FederationPostgresExpressionEffects() {}

  static void verify(Connection connection, FederationNativeSqlObjects.Use use)
      throws SQLException {
    columns(connection, use.identity());
    dependencies(connection, use.identity());
    indexes(connection, use.identity());
    if (use.write()) {
      defaults(connection, use.identity());
      constraints(connection, use.identity());
    }
  }

  private static void columns(Connection connection, String table) throws SQLException {
    String sql =
        "SELECT n.nspname,t.typname,t.typtype FROM pg_catalog.pg_attribute a"
            + " JOIN pg_catalog.pg_type t ON t.oid=a.atttypid"
            + " JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace"
            + " WHERE a.attrelid=CAST(? AS pg_catalog.oid) AND a.attnum>0 AND NOT a.attisdropped";
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(10);
      statement.setString(1, table);
      try (var rows = statement.executeQuery()) {
        int count = 0;
        while (rows.next()) {
          if (++count > 2048
              || !"pg_catalog".equals(rows.getString(1))
              || !TYPES.contains(rows.getString(2))
              || !"b".equals(rows.getString(3))) {
            throw unsupported();
          }
        }
      }
    }
  }

  private static void dependencies(Connection connection, String table) throws SQLException {
    // A function marked IMMUTABLE can still have external effects. Its declaration is not a trust
    // boundary. Include expression indexes and column defaults, not only the named table's edges.
    String sql =
        "WITH target AS (SELECT CAST(? AS pg_catalog.oid) AS id), objects AS ( SELECT"
            + " 'pg_catalog.pg_class'::pg_catalog.regclass AS classid,id AS objid FROM target UNION"
            + " ALL SELECT 'pg_catalog.pg_class'::pg_catalog.regclass,i.indexrelid FROM"
            + " pg_catalog.pg_index i,target t WHERE i.indrelid=t.id UNION ALL SELECT"
            + " 'pg_catalog.pg_attrdef'::pg_catalog.regclass,a.oid FROM pg_catalog.pg_attrdef"
            + " a,target t WHERE a.adrelid=t.id UNION ALL SELECT"
            + " 'pg_catalog.pg_constraint'::pg_catalog.regclass,c.oid FROM pg_catalog.pg_constraint"
            + " c,target t WHERE c.conrelid=t.id) SELECT 1 FROM objects o JOIN pg_catalog.pg_depend"
            + " d ON d.classid=o.classid AND d.objid=o.objid LEFT JOIN pg_catalog.pg_proc p ON"
            + " d.refclassid='pg_catalog.pg_proc'::pg_catalog.regclass AND p.oid=d.refobjid LEFT"
            + " JOIN pg_catalog.pg_operator op ON"
            + " d.refclassid='pg_catalog.pg_operator'::pg_catalog.regclass AND op.oid=d.refobjid"
            + " LEFT JOIN pg_catalog.pg_type ty ON"
            + " d.refclassid='pg_catalog.pg_type'::pg_catalog.regclass AND ty.oid=d.refobjid JOIN"
            + " pg_catalog.pg_namespace n ON"
            + " n.oid=COALESCE(p.pronamespace,op.oprnamespace,ty.typnamespace) WHERE"
            + " n.nspname<>'pg_catalog' LIMIT 1";
    rejectRows(connection, sql, table);
  }

  private static void indexes(Connection connection, String table) throws SQLException {
    String sql =
        "SELECT 1 FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class c"
            + " ON c.oid=i.indexrelid JOIN pg_catalog.pg_am am ON am.oid=c.relam"
            + " WHERE i.indrelid=CAST(? AS pg_catalog.oid) AND ("
            + " am.amname NOT IN ('btree','hash','gist','gin','brin','spgist') OR EXISTS ("
            + " SELECT 1 FROM pg_catalog.unnest(i.indclass) AS oc(id)"
            + " JOIN pg_catalog.pg_opclass op ON op.oid=oc.id"
            + " JOIN pg_catalog.pg_namespace n ON n.oid=op.opcnamespace"
            + " WHERE n.nspname<>'pg_catalog')) LIMIT 1";
    rejectRows(connection, sql, table);
    String expressions =
        "SELECT CASE WHEN pg_catalog.octet_length(indexprs::text)<=65536"
            + " THEN pg_catalog.pg_get_expr(indexprs,indrelid) END, indexprs IS NOT NULL,"
            + " CASE WHEN pg_catalog.octet_length(indpred::text)<=65536"
            + " THEN pg_catalog.pg_get_expr(indpred,indrelid) END, indpred IS NOT NULL"
            + " FROM pg_catalog.pg_index WHERE indrelid=CAST(? AS pg_catalog.oid)";
    try (var statement = connection.prepareStatement(expressions)) {
      statement.setQueryTimeout(10);
      statement.setString(1, table);
      try (var rows = statement.executeQuery()) {
        int count = 0;
        while (rows.next()) {
          if (++count > 2048) {
            throw unsupported();
          }
          if (rows.getBoolean(2)) {
            String expression = rows.getString(1);
            if (expression == null) {
              throw unsupported();
            }
            FederationCatalogExpression.verify("(" + expression + ")", true);
          }
          if (rows.getBoolean(4)) {
            FederationCatalogExpression.verify(rows.getString(3), true);
          }
        }
      }
    }
  }

  private static void defaults(Connection connection, String table) throws SQLException {
    String sql =
        "SELECT pg_catalog.left(a.adbin::text,7)='{CONST ', CASE WHEN"
            + " pg_catalog.octet_length(a.adbin::text)<=65536 THEN"
            + " pg_catalog.pg_get_expr(a.adbin,a.adrelid) END, (SELECT pg_catalog.count(*) FROM"
            + " pg_catalog.pg_depend d JOIN pg_catalog.pg_class s ON s.oid=d.refobjid AND"
            + " s.relkind='S' WHERE d.classid='pg_catalog.pg_attrdef'::pg_catalog.regclass AND"
            + " d.objid=a.oid AND d.refclassid='pg_catalog.pg_class'::pg_catalog.regclass), EXISTS"
            + " (SELECT 1 FROM pg_catalog.pg_depend d JOIN pg_catalog.pg_class s ON"
            + " s.oid=d.refobjid AND s.relkind='S' JOIN pg_catalog.pg_depend owner ON"
            + " owner.classid='pg_catalog.pg_class'::pg_catalog.regclass AND owner.objid=s.oid AND"
            + " owner.objsubid=0 AND owner.refclassid='pg_catalog.pg_class'::pg_catalog.regclass"
            + " AND owner.refobjid=a.adrelid AND owner.refobjsubid=a.adnum AND owner.deptype IN"
            + " ('a','i') WHERE d.classid='pg_catalog.pg_attrdef'::pg_catalog.regclass AND"
            + " d.objid=a.oid AND d.refclassid='pg_catalog.pg_class'::pg_catalog.regclass) FROM"
            + " pg_catalog.pg_attrdef a WHERE a.adrelid=CAST(? AS pg_catalog.oid)";
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(10);
      statement.setString(1, table);
      try (var rows = statement.executeQuery()) {
        int count = 0;
        while (rows.next()) {
          if (++count > 2048) {
            throw unsupported();
          }
          if (rows.getBoolean(1)) {
            // Native Const has no executable children; its type was checked in columns(). This
            // preserves literal Unicode/backslash/JSON defaults without reparsing their contents.
            continue;
          }
          String expression = rows.getString(2);
          if (rows.getLong(3) == 1
              && rows.getBoolean(4)
              && FederationCatalogExpression.ownedSequenceDefault(expression)) {
            continue;
          }
          FederationCatalogExpression.verify(expression, true);
        }
      }
    }
  }

  private static void constraints(Connection connection, String table) throws SQLException {
    String sql =
        "SELECT CASE WHEN pg_catalog.octet_length(conbin::text)<=65536"
            + " THEN pg_catalog.pg_get_expr(conbin,conrelid) END"
            + " FROM pg_catalog.pg_constraint WHERE conrelid=CAST(? AS pg_catalog.oid)"
            + " AND conbin IS NOT NULL";
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(10);
      statement.setString(1, table);
      try (var rows = statement.executeQuery()) {
        int count = 0;
        while (rows.next()) {
          if (++count > 2048) {
            throw unsupported();
          }
          FederationCatalogExpression.verify(rows.getString(1), true);
        }
      }
    }
  }

  private static void rejectRows(Connection connection, String sql, String table)
      throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(10);
      statement.setString(1, table);
      try (var rows = statement.executeQuery()) {
        if (rows.next()) {
          throw unsupported();
        }
      }
    }
  }
}
