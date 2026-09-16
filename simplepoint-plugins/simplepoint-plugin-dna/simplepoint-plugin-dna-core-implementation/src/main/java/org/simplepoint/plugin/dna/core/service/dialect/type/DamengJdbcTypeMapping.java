package org.simplepoint.plugin.dna.core.service.dialect.type;

import java.sql.Types;
import org.simplepoint.plugin.dna.core.api.spi.StandardJdbcTypeMapping;

/**
 * JDBC type mapping for Dameng (DM) databases.
 *
 * <p>Covers Dameng-specific types that largely follow Oracle conventions, including
 * {@code VARCHAR2}, {@code NUMBER}, {@code BINARY_FLOAT}, {@code BINARY_DOUBLE},
 * interval types, and Dameng-specific extensions like {@code IMAGE} and {@code TEXT}.</p>
 */
public final class DamengJdbcTypeMapping extends StandardJdbcTypeMapping {

  /** Shared singleton instance. */
  public static final DamengJdbcTypeMapping INSTANCE = new DamengJdbcTypeMapping();

  private DamengJdbcTypeMapping() {
    super();
    registerDamengTypes();
  }

  private void registerDamengTypes() {
    // Numeric
    register("NUMBER", Types.NUMERIC);
    register("BINARY_FLOAT", Types.REAL);
    register("BINARY_DOUBLE", Types.DOUBLE);
    register("PLS_INTEGER", Types.INTEGER);
    register("BINARY_INTEGER", Types.INTEGER);

    // Integer
    register("INT", Types.INTEGER);
    register("INTEGER", Types.INTEGER);
    register("BIGINT", Types.BIGINT);
    register("TINYINT", Types.TINYINT);
    register("SMALLINT", Types.SMALLINT);
    register("BYTE", Types.TINYINT);

    // Character (Oracle-compatible)
    register("VARCHAR2", Types.VARCHAR);
    register("NVARCHAR2", Types.NVARCHAR);
    register("CHAR", Types.CHAR);
    register("NCHAR", Types.NCHAR);
    register("LONG", Types.LONGVARCHAR);
    register("LONGVARCHAR", Types.LONGVARCHAR);

    // LOB
    register("CLOB", Types.CLOB);
    register("NCLOB", Types.NCLOB);
    register("BLOB", Types.BLOB);
    register("BFILE", Types.OTHER);

    // Text / Image (DM extensions)
    register("TEXT", Types.LONGVARCHAR);
    register("IMAGE", Types.LONGVARBINARY);

    // Binary
    register("RAW", Types.VARBINARY);
    register("LONG RAW", Types.LONGVARBINARY);
    register("BINARY", Types.BINARY);
    register("VARBINARY", Types.VARBINARY);

    // Boolean
    register("BIT", Types.BIT);
    register("BOOLEAN", Types.BOOLEAN);

    // Date/Time
    register("DATE", Types.TIMESTAMP);
    register("TIME", Types.TIME);
    register("TIMESTAMP", Types.TIMESTAMP);
    register("TIMESTAMP WITH TIME ZONE", Types.TIMESTAMP_WITH_TIMEZONE);
    register("TIMESTAMP WITH LOCAL TIME ZONE", Types.TIMESTAMP);
    register("DATETIME", Types.TIMESTAMP);
    register("INTERVAL YEAR TO MONTH", Types.OTHER);
    register("INTERVAL DAY TO SECOND", Types.OTHER);

    // Oracle-compatible identifiers
    register("ROWID", Types.ROWID);

    // XML
    register("XMLTYPE", Types.SQLXML);

    // Other
    register("ARRAY", Types.ARRAY);
    register("CLASS", Types.STRUCT);
    register("RECORD", Types.STRUCT);
  }
}
