package org.simplepoint.plugin.dna.core.service.dialect.type;

import java.sql.Types;
import org.simplepoint.plugin.dna.core.api.spi.StandardJdbcTypeMapping;

/**
 * JDBC type mapping for Apache Hive (including HiveServer2 / Beeline).
 *
 * <p>Covers Hive-specific types including {@code STRING}, {@code BINARY}, sized numeric types
 * ({@code TINYINT} through {@code BIGINT}), complex types ({@code ARRAY}, {@code MAP},
 * {@code STRUCT}, {@code UNIONTYPE}), and Hive-specific aliases.</p>
 */
public final class HiveJdbcTypeMapping extends StandardJdbcTypeMapping {

  /** Shared singleton instance. */
  public static final HiveJdbcTypeMapping INSTANCE = new HiveJdbcTypeMapping();

  private HiveJdbcTypeMapping() {
    super();
    registerHiveTypes();
  }

  private void registerHiveTypes() {
    // Boolean
    register("BOOLEAN", Types.BOOLEAN);

    // Integer types
    register("TINYINT", Types.TINYINT);
    register("SMALLINT", Types.SMALLINT);
    register("INT", Types.INTEGER);
    register("INTEGER", Types.INTEGER);
    register("BIGINT", Types.BIGINT);

    // Floating-point
    register("FLOAT", Types.FLOAT);
    register("DOUBLE", Types.DOUBLE);
    register("DOUBLE PRECISION", Types.DOUBLE);

    // Decimal
    register("DECIMAL", Types.DECIMAL);
    register("NUMERIC", Types.DECIMAL);
    register("DEC", Types.DECIMAL);

    // Character / string
    register("STRING", Types.VARCHAR);
    register("VARCHAR", Types.VARCHAR);
    register("CHAR", Types.CHAR);

    // Binary
    register("BINARY", Types.BINARY);

    // Date/time
    register("DATE", Types.DATE);
    register("TIMESTAMP", Types.TIMESTAMP);
    register("INTERVAL_YEAR_MONTH", Types.OTHER);
    register("INTERVAL_DAY_TIME", Types.OTHER);

    // Complex types (base names; parameterized resolved in resolveJdbcType)
    register("ARRAY", Types.ARRAY);
    register("MAP", Types.JAVA_OBJECT);
    register("STRUCT", Types.STRUCT);
    register("UNIONTYPE", Types.JAVA_OBJECT);

    // Void (null)
    register("VOID", Types.NULL);
  }

  @Override
  public int resolveJdbcType(final String nativeTypeName, final int vendorJdbcType) {
    if (nativeTypeName == null || nativeTypeName.isBlank()) {
      return vendorJdbcType != 0 ? vendorJdbcType : Types.VARCHAR;
    }
    String upper = nativeTypeName.toUpperCase().trim();

    // Handle parameterized complex types: ARRAY<...>, MAP<...>, STRUCT<...>, UNIONTYPE<...>
    if (upper.startsWith("ARRAY<") || upper.startsWith("ARRAY(")) {
      return Types.ARRAY;
    }
    if (upper.startsWith("MAP<") || upper.startsWith("MAP(")) {
      return Types.JAVA_OBJECT;
    }
    if (upper.startsWith("STRUCT<") || upper.startsWith("STRUCT(")) {
      return Types.STRUCT;
    }
    if (upper.startsWith("UNIONTYPE<") || upper.startsWith("UNIONTYPE(")) {
      return Types.JAVA_OBJECT;
    }

    return super.resolveJdbcType(nativeTypeName, vendorJdbcType);
  }
}
