package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.impl.FederationSqlStatementBoundary.unsupported;

import java.util.Locale;
import java.util.Set;
import org.apache.calcite.sql.SqlBasicTypeNameSpec;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlUserDefinedTypeNameSpec;

/**
 * Bounded write-expression grammar. Native type/default/index checks must still certify execution.
 */
final class FederationWriteExpressionPolicy {
  private static final Set<String> FUNCTIONS =
      Set.of(
          "ABS",
          "CAST",
          "CEIL",
          "CEILING",
          "FLOOR",
          "MOD",
          "POWER",
          "SQRT",
          "ROUND",
          "SIGN",
          "EXP",
          "LN",
          "LOG10",
          "LOWER",
          "UPPER",
          "TRIM",
          "LTRIM",
          "RTRIM",
          "CHAR_LENGTH",
          "CHARACTER_LENGTH",
          "OCTET_LENGTH",
          "LENGTH",
          "SUBSTRING",
          "SUBSTR",
          "REPLACE",
          "CONCAT",
          "POSITION",
          "COALESCE",
          "NULLIF",
          "GREATEST",
          "LEAST",
          "COUNT",
          "SUM",
          "AVG",
          "MIN",
          "MAX",
          "CURRENT_DATE",
          "CURRENT_TIME",
          "CURRENT_TIMESTAMP",
          "LOCALTIME",
          "LOCALTIMESTAMP",
          "EXTRACT",
          "ROW_NUMBER",
          "RANK",
          "DENSE_RANK",
          "LAG",
          "LEAD",
          "FIRST_VALUE",
          "LAST_VALUE");
  private static final Set<String> TYPES =
      Set.of(
          "BOOLEAN",
          "TINYINT",
          "SMALLINT",
          "INTEGER",
          "BIGINT",
          "DECIMAL",
          "NUMERIC",
          "REAL",
          "FLOAT",
          "DOUBLE",
          "CHAR",
          "VARCHAR",
          "BINARY",
          "VARBINARY",
          "DATE",
          "TIME",
          "TIMESTAMP",
          "TIME_WITH_LOCAL_TIME_ZONE",
          "TIMESTAMP_WITH_LOCAL_TIME_ZONE",
          "TIME_TZ",
          "TIMESTAMP_TZ");
  private int visited;
  private final boolean catalog;

  private FederationWriteExpressionPolicy(boolean catalog) {
    this.catalog = catalog;
  }

  static void verify(SqlNode node) {
    new FederationWriteExpressionPolicy(false).walk(node, 0);
  }

  static void verifyCatalog(SqlNode node) {
    new FederationWriteExpressionPolicy(true).walk(node, 0);
  }

  static Set<String> builtinFunctions() {
    return FUNCTIONS;
  }

  private void walk(SqlNode node, int depth) {
    if (node == null) {
      return;
    }
    if (++visited > 4096 || depth > 64) {
      throw unsupported();
    }
    if (catalog && (node.isA(SqlKind.QUERY) || node.isA(SqlKind.DML) || node.isA(SqlKind.DDL))) {
      throw unsupported();
    }
    if (node instanceof SqlDataTypeSpec type) {
      var name = type.getTypeName();
      boolean nativeType =
          catalog
              && (type.getTypeNameSpec() instanceof SqlBasicTypeNameSpec
                  || type.getTypeNameSpec() instanceof SqlUserDefinedTypeNameSpec)
              && name.names.size() == 1
              && !name.isComponentQuoted(0)
              && Set.of(
                      "TEXT",
                      "UUID",
                      "JSON",
                      "JSONB",
                      "BYTEA",
                      "INT2",
                      "INT4",
                      "INT8",
                      "FLOAT4",
                      "FLOAT8",
                      "BPCHAR",
                      "TIMESTAMPTZ",
                      "TIMETZ")
                  .contains(name.getSimple().toUpperCase(Locale.ROOT));
      if (!nativeType
          && (!(type.getTypeNameSpec() instanceof SqlBasicTypeNameSpec)
              || !TYPES.contains(name.getSimple().toUpperCase(Locale.ROOT)))) {
        throw unsupported();
      }
    } else if (node instanceof SqlNodeList list) {
      for (var child : list) {
        walk(child, depth + 1);
      }
    } else if (node instanceof SqlCall call) {
      if (call.getKind() == SqlKind.NEXT_VALUE || call.getKind() == SqlKind.CURRENT_VALUE) {
        throw unsupported();
      }
      if (call.getOperator() instanceof SqlFunction function) {
        var name = function.getSqlIdentifier();
        boolean nativeFunction =
            catalog
                && (call.getKind() == SqlKind.CAST
                    || Set.of("NOW", "GEN_RANDOM_UUID", "UUID", "UUID_TO_BIN", "BIN_TO_UUID")
                        .contains(function.getName().toUpperCase(Locale.ROOT)));
        boolean catalogUuid = catalog && "UUID".equalsIgnoreCase(function.getName());
        if (name != null && (name.names.size() != 1 || name.isComponentQuoted(0) && !catalogUuid)
            || !nativeFunction
                && !FUNCTIONS.contains(function.getName().toUpperCase(Locale.ROOT))) {
          throw unsupported();
        }
      }
      for (var child : call.getOperandList()) {
        walk(child, depth + 1);
      }
    }
  }
}
