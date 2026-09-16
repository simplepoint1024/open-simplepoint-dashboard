package org.simplepoint.plugin.dna.core.service.dialect.type;

import java.sql.Types;
import org.simplepoint.plugin.dna.core.api.spi.StandardJdbcTypeMapping;

/**
 * JDBC type mapping for Microsoft SQL Server.
 *
 * <p>Covers SQL Server-specific types including {@code NTEXT}, {@code IMAGE}, {@code MONEY},
 * {@code SMALLMONEY}, {@code UNIQUEIDENTIFIER}, {@code DATETIMEOFFSET}, {@code SQL_VARIANT},
 * {@code HIERARCHYID}, spatial types, and large value types.</p>
 */
public final class SqlServerJdbcTypeMapping extends StandardJdbcTypeMapping {

  /** Shared singleton instance. */
  public static final SqlServerJdbcTypeMapping INSTANCE = new SqlServerJdbcTypeMapping();

  private SqlServerJdbcTypeMapping() {
    super();
    registerSqlServerTypes();
  }

  private void registerSqlServerTypes() {
    // Integer
    register("TINYINT", Types.TINYINT);
    register("SMALLINT", Types.SMALLINT);
    register("INT", Types.INTEGER);
    register("BIGINT", Types.BIGINT);

    // Numeric
    register("MONEY", Types.DECIMAL);
    register("SMALLMONEY", Types.DECIMAL);
    register("DECIMAL", Types.DECIMAL);
    register("NUMERIC", Types.NUMERIC);

    // Floating-point
    register("REAL", Types.REAL);
    register("FLOAT", Types.DOUBLE);

    // Boolean
    register("BIT", Types.BIT);

    // Character
    register("CHAR", Types.CHAR);
    register("VARCHAR", Types.VARCHAR);
    register("TEXT", Types.LONGVARCHAR);
    register("NCHAR", Types.NCHAR);
    register("NVARCHAR", Types.NVARCHAR);
    register("NTEXT", Types.LONGNVARCHAR);
    register("SYSNAME", Types.NVARCHAR);

    // Large value types
    register("VARCHAR(MAX)", Types.LONGVARCHAR);
    register("NVARCHAR(MAX)", Types.LONGNVARCHAR);
    register("VARBINARY(MAX)", Types.LONGVARBINARY);

    // Binary
    register("BINARY", Types.BINARY);
    register("VARBINARY", Types.VARBINARY);
    register("IMAGE", Types.LONGVARBINARY);
    register("TIMESTAMP", Types.BINARY);
    register("ROWVERSION", Types.BINARY);

    // Date/Time
    register("DATE", Types.DATE);
    register("TIME", Types.TIME);
    register("DATETIME", Types.TIMESTAMP);
    register("DATETIME2", Types.TIMESTAMP);
    register("SMALLDATETIME", Types.TIMESTAMP);
    register("DATETIMEOFFSET", Types.TIMESTAMP_WITH_TIMEZONE);

    // SQL Server-specific
    register("UNIQUEIDENTIFIER", Types.CHAR);
    register("SQL_VARIANT", Types.OTHER);
    register("HIERARCHYID", Types.OTHER);
    register("XML", Types.SQLXML);

    // Spatial
    register("GEOMETRY", Types.OTHER);
    register("GEOGRAPHY", Types.OTHER);

    // Table-valued
    register("TABLE", Types.OTHER);
    register("CURSOR", Types.REF_CURSOR);
  }
}
