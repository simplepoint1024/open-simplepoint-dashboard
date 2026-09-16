package org.simplepoint.plugin.dna.core.service.dialect.type;

import java.sql.Types;
import org.simplepoint.plugin.dna.core.api.spi.StandardJdbcTypeMapping;

/**
 * JDBC type mapping for MySQL and MariaDB databases.
 *
 * <p>Covers MySQL-specific types including {@code YEAR}, {@code ENUM}, {@code SET},
 * {@code JSON}, unsigned integer variants, spatial types, and MariaDB extensions.</p>
 */
public final class MysqlJdbcTypeMapping extends StandardJdbcTypeMapping {

  /** Shared singleton instance. */
  public static final MysqlJdbcTypeMapping INSTANCE = new MysqlJdbcTypeMapping();

  private MysqlJdbcTypeMapping() {
    super();
    registerMysqlTypes();
  }

  private void registerMysqlTypes() {
    // Integer variants with UNSIGNED
    register("TINYINT UNSIGNED", Types.SMALLINT);
    register("SMALLINT UNSIGNED", Types.INTEGER);
    register("MEDIUMINT UNSIGNED", Types.INTEGER);
    register("INT UNSIGNED", Types.BIGINT);
    register("INTEGER UNSIGNED", Types.BIGINT);
    register("BIGINT UNSIGNED", Types.NUMERIC);

    // MySQL-specific integer alias
    register("YEAR", Types.SMALLINT);

    // Floating-point
    register("FLOAT UNSIGNED", Types.REAL);
    register("DOUBLE UNSIGNED", Types.DOUBLE);
    register("DECIMAL UNSIGNED", Types.DECIMAL);

    // String / Text
    register("ENUM", Types.VARCHAR);
    register("SET", Types.VARCHAR);
    register("TINYTEXT", Types.VARCHAR);
    register("LONGTEXT", Types.LONGVARCHAR);
    register("MEDIUMTEXT", Types.LONGVARCHAR);

    // Binary / BLOB
    register("TINYBLOB", Types.BLOB);
    register("MEDIUMBLOB", Types.BLOB);
    register("LONGBLOB", Types.BLOB);

    // JSON
    register("JSON", Types.LONGVARCHAR);

    // Date/Time
    register("DATETIME", Types.TIMESTAMP);
    register("TIMESTAMP", Types.TIMESTAMP);

    // Spatial
    register("GEOMETRY", Types.BINARY);
    register("POINT", Types.BINARY);
    register("LINESTRING", Types.BINARY);
    register("POLYGON", Types.BINARY);
    register("MULTIPOINT", Types.BINARY);
    register("MULTILINESTRING", Types.BINARY);
    register("MULTIPOLYGON", Types.BINARY);
    register("GEOMETRYCOLLECTION", Types.BINARY);

    // BIT
    register("BIT", Types.BIT);
  }
}
