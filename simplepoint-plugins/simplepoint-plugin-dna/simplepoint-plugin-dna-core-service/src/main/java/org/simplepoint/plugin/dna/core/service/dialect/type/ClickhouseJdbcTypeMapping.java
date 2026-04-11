package org.simplepoint.plugin.dna.core.service.dialect.type;

import java.sql.Types;
import org.simplepoint.plugin.dna.core.api.spi.StandardJdbcTypeMapping;

/**
 * JDBC type mapping for ClickHouse analytical database.
 *
 * <p>Covers ClickHouse-specific types including sized integer variants ({@code Int8} through
 * {@code Int256}), unsigned variants, {@code FixedString}, {@code LowCardinality}, {@code Nullable},
 * {@code Enum8/Enum16}, {@code Map}, {@code Tuple}, {@code Nested}, and date types.</p>
 */
public final class ClickhouseJdbcTypeMapping extends StandardJdbcTypeMapping {

  /** Shared singleton instance. */
  public static final ClickhouseJdbcTypeMapping INSTANCE = new ClickhouseJdbcTypeMapping();

  private ClickhouseJdbcTypeMapping() {
    super();
    registerClickhouseTypes();
  }

  private void registerClickhouseTypes() {
    // Signed integers
    register("INT8", Types.TINYINT);
    register("INT16", Types.SMALLINT);
    register("INT32", Types.INTEGER);
    register("INT64", Types.BIGINT);
    register("INT128", Types.NUMERIC);
    register("INT256", Types.NUMERIC);

    // Unsigned integers
    register("UINT8", Types.SMALLINT);
    register("UINT16", Types.INTEGER);
    register("UINT32", Types.BIGINT);
    register("UINT64", Types.NUMERIC);
    register("UINT128", Types.NUMERIC);
    register("UINT256", Types.NUMERIC);

    // Floating-point
    register("FLOAT32", Types.REAL);
    register("FLOAT64", Types.DOUBLE);

    // Decimal
    register("DECIMAL32", Types.DECIMAL);
    register("DECIMAL64", Types.DECIMAL);
    register("DECIMAL128", Types.DECIMAL);
    register("DECIMAL256", Types.DECIMAL);

    // Boolean
    register("BOOL", Types.BOOLEAN);

    // String
    register("STRING", Types.VARCHAR);
    register("FIXEDSTRING", Types.CHAR);

    // UUID
    register("UUID", Types.OTHER);

    // Date/Time
    register("DATE", Types.DATE);
    register("DATE32", Types.DATE);
    register("DATETIME", Types.TIMESTAMP);
    register("DATETIME64", Types.TIMESTAMP);

    // Enum
    register("ENUM8", Types.VARCHAR);
    register("ENUM16", Types.VARCHAR);

    // IP
    register("IPV4", Types.OTHER);
    register("IPV6", Types.OTHER);

    // JSON
    register("JSON", Types.OTHER);
    register("OBJECT('JSON')", Types.OTHER);

    // Geo
    register("POINT", Types.OTHER);
    register("RING", Types.OTHER);
    register("LINESTRING", Types.OTHER);
    register("MULTILINESTRING", Types.OTHER);
    register("POLYGON", Types.OTHER);
    register("MULTIPOLYGON", Types.OTHER);

    // Composite
    register("ARRAY", Types.ARRAY);
    register("TUPLE", Types.STRUCT);
    register("MAP", Types.OTHER);
    register("NESTED", Types.STRUCT);
    register("NOTHING", Types.NULL);
  }

  @Override
  public int resolveJdbcType(final String nativeTypeName, final int vendorJdbcType) {
    if (nativeTypeName == null) {
      return super.resolveJdbcType(null, vendorJdbcType);
    }
    String trimmed = nativeTypeName.trim();
    String upper = trimmed.toUpperCase();

    // Unwrap Nullable(...) and LowCardinality(...)
    if (upper.startsWith("NULLABLE(") && upper.endsWith(")")) {
      String inner = trimmed.substring("Nullable(".length(), trimmed.length() - 1);
      return resolveJdbcType(inner, vendorJdbcType);
    }
    if (upper.startsWith("LOWCARDINALITY(") && upper.endsWith(")")) {
      String inner = trimmed.substring("LowCardinality(".length(), trimmed.length() - 1);
      return resolveJdbcType(inner, vendorJdbcType);
    }

    // Array(T) → ARRAY
    if (upper.startsWith("ARRAY(")) {
      return Types.ARRAY;
    }

    // Map(K, V) → OTHER
    if (upper.startsWith("MAP(")) {
      return Types.OTHER;
    }

    // Tuple(...) → STRUCT
    if (upper.startsWith("TUPLE(")) {
      return Types.STRUCT;
    }

    // FixedString(N) → CHAR
    if (upper.startsWith("FIXEDSTRING(")) {
      return Types.CHAR;
    }

    // Enum8/Enum16 with values
    if (upper.startsWith("ENUM8(") || upper.startsWith("ENUM16(")) {
      return Types.VARCHAR;
    }

    // DateTime64(precision) → TIMESTAMP
    if (upper.startsWith("DATETIME64(")) {
      return Types.TIMESTAMP;
    }

    // Decimal(P, S) variants
    if (upper.startsWith("DECIMAL32(") || upper.startsWith("DECIMAL64(")
        || upper.startsWith("DECIMAL128(") || upper.startsWith("DECIMAL256(")) {
      return Types.DECIMAL;
    }

    return super.resolveJdbcType(nativeTypeName, vendorJdbcType);
  }
}
