package org.simplepoint.plugin.dna.core.service.dialect.type;

import java.sql.Types;
import org.simplepoint.plugin.dna.core.api.spi.StandardJdbcTypeMapping;

/**
 * JDBC type mapping for PostgreSQL databases.
 *
 * <p>Covers PostgreSQL-specific types including {@code JSONB}, {@code UUID}, network address types,
 * full-text search types, range types, geometric types, and array notation.</p>
 */
public final class PostgresqlJdbcTypeMapping extends StandardJdbcTypeMapping {

  /** Shared singleton instance. */
  public static final PostgresqlJdbcTypeMapping INSTANCE = new PostgresqlJdbcTypeMapping();

  private PostgresqlJdbcTypeMapping() {
    super();
    registerPostgresTypes();
  }

  private void registerPostgresTypes() {
    // Boolean
    register("BOOL", Types.BOOLEAN);

    // Integer aliases
    register("INT2", Types.SMALLINT);
    register("INT4", Types.INTEGER);
    register("INT8", Types.BIGINT);
    register("SERIAL", Types.INTEGER);
    register("BIGSERIAL", Types.BIGINT);
    register("SMALLSERIAL", Types.SMALLINT);
    register("OID", Types.BIGINT);
    register("XID", Types.INTEGER);
    register("CID", Types.INTEGER);
    register("TID", Types.OTHER);

    // Floating-point
    register("FLOAT4", Types.REAL);
    register("FLOAT8", Types.DOUBLE);
    register("MONEY", Types.NUMERIC);

    // Character
    register("BPCHAR", Types.CHAR);
    register("NAME", Types.VARCHAR);
    register("TEXT", Types.VARCHAR);

    // Binary
    register("BYTEA", Types.BINARY);

    // JSON
    register("JSON", Types.OTHER);
    register("JSONB", Types.OTHER);
    register("JSONPATH", Types.OTHER);

    // UUID
    register("UUID", Types.OTHER);

    // Date/Time
    register("TIMESTAMPTZ", Types.TIMESTAMP_WITH_TIMEZONE);
    register("TIMESTAMP WITH TIME ZONE", Types.TIMESTAMP_WITH_TIMEZONE);
    register("TIMESTAMP WITHOUT TIME ZONE", Types.TIMESTAMP);
    register("TIMETZ", Types.TIME_WITH_TIMEZONE);
    register("TIME WITH TIME ZONE", Types.TIME_WITH_TIMEZONE);
    register("TIME WITHOUT TIME ZONE", Types.TIME);
    register("INTERVAL", Types.OTHER);
    register("ABSTIME", Types.TIMESTAMP);
    register("RELTIME", Types.OTHER);

    // Network address
    register("INET", Types.OTHER);
    register("CIDR", Types.OTHER);
    register("MACADDR", Types.OTHER);
    register("MACADDR8", Types.OTHER);

    // Geometric
    register("POINT", Types.OTHER);
    register("LINE", Types.OTHER);
    register("LSEG", Types.OTHER);
    register("BOX", Types.OTHER);
    register("PATH", Types.OTHER);
    register("POLYGON", Types.OTHER);
    register("CIRCLE", Types.OTHER);

    // Full-text search
    register("TSVECTOR", Types.OTHER);
    register("TSQUERY", Types.OTHER);

    // Range types
    register("INT4RANGE", Types.OTHER);
    register("INT8RANGE", Types.OTHER);
    register("NUMRANGE", Types.OTHER);
    register("TSRANGE", Types.OTHER);
    register("TSTZRANGE", Types.OTHER);
    register("DATERANGE", Types.OTHER);
    register("INT4MULTIRANGE", Types.OTHER);
    register("INT8MULTIRANGE", Types.OTHER);
    register("NUMMULTIRANGE", Types.OTHER);
    register("TSMULTIRANGE", Types.OTHER);
    register("TSTZMULTIRANGE", Types.OTHER);
    register("DATEMULTIRANGE", Types.OTHER);

    // Bit string
    register("BIT", Types.BIT);
    register("VARBIT", Types.OTHER);
    register("BIT VARYING", Types.OTHER);

    // Enum
    register("ENUM", Types.VARCHAR);

    // Other
    register("HSTORE", Types.OTHER);
    register("XML", Types.SQLXML);
    register("PG_LSN", Types.OTHER);
    register("PG_SNAPSHOT", Types.OTHER);
    register("VOID", Types.NULL);
    register("RECORD", Types.STRUCT);
    register("REGCLASS", Types.OTHER);
    register("REGTYPE", Types.OTHER);
    register("REGPROC", Types.OTHER);
    register("REFCURSOR", Types.REF_CURSOR);

    // Array notation (PostgreSQL uses _type for arrays)
    register("_INT2", Types.ARRAY);
    register("_INT4", Types.ARRAY);
    register("_INT8", Types.ARRAY);
    register("_FLOAT4", Types.ARRAY);
    register("_FLOAT8", Types.ARRAY);
    register("_TEXT", Types.ARRAY);
    register("_VARCHAR", Types.ARRAY);
    register("_BOOL", Types.ARRAY);
    register("_NUMERIC", Types.ARRAY);
    register("_TIMESTAMP", Types.ARRAY);
    register("_TIMESTAMPTZ", Types.ARRAY);
    register("_DATE", Types.ARRAY);
    register("_UUID", Types.ARRAY);
    register("_JSON", Types.ARRAY);
    register("_JSONB", Types.ARRAY);
    register("_BYTEA", Types.ARRAY);
  }

  @Override
  public int resolveJdbcType(final String nativeTypeName, final int vendorJdbcType) {
    if (nativeTypeName != null && nativeTypeName.trim().startsWith("_")) {
      // PostgreSQL array types are prefixed with underscore
      String normalized = nativeTypeName.trim().toUpperCase();
      int mapped = super.resolveJdbcType(normalized, vendorJdbcType);
      if (mapped == Types.ARRAY) {
        return Types.ARRAY;
      }
      // Any underscore-prefixed type is likely an array
      return Types.ARRAY;
    }
    return super.resolveJdbcType(nativeTypeName, vendorJdbcType);
  }
}
