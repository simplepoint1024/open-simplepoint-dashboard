package org.simplepoint.plugin.dna.core.service.dialect.type;

import java.sql.Types;
import org.simplepoint.plugin.dna.core.api.spi.StandardJdbcTypeMapping;

/**
 * JDBC type mapping for Trino (formerly PrestoSQL) query engine.
 *
 * <p>Trino extends the Presto type system with additional types such as {@code UUID},
 * {@code IPADDRESS}, {@code SphericalGeography}, and digest types. Parameterized types
 * like {@code ARRAY(...)}, {@code MAP(...)}, and {@code ROW(...)} are unwrapped before lookup.</p>
 */
public final class TrinoJdbcTypeMapping extends StandardJdbcTypeMapping {

  /** Shared singleton instance. */
  public static final TrinoJdbcTypeMapping INSTANCE = new TrinoJdbcTypeMapping();

  private TrinoJdbcTypeMapping() {
    super();
    registerTrinoTypes();
  }

  private void registerTrinoTypes() {
    // Boolean
    register("BOOLEAN", Types.BOOLEAN);

    // Integer types
    register("TINYINT", Types.TINYINT);
    register("SMALLINT", Types.SMALLINT);
    register("INTEGER", Types.INTEGER);
    register("INT", Types.INTEGER);
    register("BIGINT", Types.BIGINT);

    // Floating-point
    register("REAL", Types.REAL);
    register("DOUBLE", Types.DOUBLE);

    // Decimal
    register("DECIMAL", Types.DECIMAL);

    // Character
    register("VARCHAR", Types.VARCHAR);
    register("CHAR", Types.CHAR);

    // Binary
    register("VARBINARY", Types.VARBINARY);

    // Date/time
    register("DATE", Types.DATE);
    register("TIME", Types.TIME);
    register("TIME WITH TIME ZONE", Types.TIME_WITH_TIMEZONE);
    register("TIMESTAMP", Types.TIMESTAMP);
    register("TIMESTAMP WITH TIME ZONE", Types.TIMESTAMP_WITH_TIMEZONE);
    register("INTERVAL YEAR TO MONTH", Types.OTHER);
    register("INTERVAL DAY TO SECOND", Types.OTHER);

    // JSON
    register("JSON", Types.OTHER);

    // Network / identifier types
    register("IPADDRESS", Types.VARCHAR);
    register("UUID", Types.OTHER);

    // Aggregate / statistical types
    register("HYPERLOGLOG", Types.JAVA_OBJECT);
    register("P4HYPERLOGLOG", Types.JAVA_OBJECT);
    register("SETDIGEST", Types.JAVA_OBJECT);
    register("QDIGEST", Types.JAVA_OBJECT);
    register("TDIGEST", Types.JAVA_OBJECT);

    // Structural types (base names; parameterized resolved in resolveJdbcType)
    register("ARRAY", Types.ARRAY);
    register("MAP", Types.JAVA_OBJECT);
    register("ROW", Types.STRUCT);

    // Spatial
    register("GEOMETRY", Types.OTHER);
    register("SPHERICALGEOGRAPHY", Types.OTHER);
    register("BINGTILE", Types.OTHER);
  }

  @Override
  public int resolveJdbcType(final String nativeTypeName, final int vendorJdbcType) {
    if (nativeTypeName == null || nativeTypeName.isBlank()) {
      return vendorJdbcType != 0 ? vendorJdbcType : Types.VARCHAR;
    }
    String upper = nativeTypeName.toUpperCase().trim();

    // Handle parameterized types: ARRAY(...), MAP(...), ROW(...)
    if (upper.startsWith("ARRAY(") || upper.startsWith("ARRAY<")) {
      return Types.ARRAY;
    }
    if (upper.startsWith("MAP(") || upper.startsWith("MAP<")) {
      return Types.JAVA_OBJECT;
    }
    if (upper.startsWith("ROW(") || upper.startsWith("ROW<")) {
      return Types.STRUCT;
    }

    return super.resolveJdbcType(nativeTypeName, vendorJdbcType);
  }
}
