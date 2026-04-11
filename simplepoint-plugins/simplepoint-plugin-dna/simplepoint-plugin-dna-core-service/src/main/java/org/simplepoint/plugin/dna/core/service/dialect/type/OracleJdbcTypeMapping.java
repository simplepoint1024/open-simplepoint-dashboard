package org.simplepoint.plugin.dna.core.service.dialect.type;

import java.sql.Types;
import org.simplepoint.plugin.dna.core.api.spi.StandardJdbcTypeMapping;

/**
 * JDBC type mapping for Oracle databases.
 *
 * <p>Covers Oracle-specific types including {@code NUMBER}, {@code VARCHAR2}, {@code NVARCHAR2},
 * {@code BINARY_FLOAT}, {@code BINARY_DOUBLE}, {@code RAW}, {@code LONG RAW}, {@code CLOB},
 * {@code NCLOB}, {@code BLOB}, {@code BFILE}, interval types, and Oracle object types.</p>
 */
public final class OracleJdbcTypeMapping extends StandardJdbcTypeMapping {

  /** Shared singleton instance. */
  public static final OracleJdbcTypeMapping INSTANCE = new OracleJdbcTypeMapping();

  private OracleJdbcTypeMapping() {
    super();
    registerOracleTypes();
  }

  private void registerOracleTypes() {
    // Numeric
    register("NUMBER", Types.NUMERIC);
    register("BINARY_FLOAT", Types.REAL);
    register("BINARY_DOUBLE", Types.DOUBLE);
    register("BINARY_INTEGER", Types.INTEGER);
    register("PLS_INTEGER", Types.INTEGER);
    register("NATURAL", Types.INTEGER);
    register("NATURALN", Types.INTEGER);
    register("POSITIVE", Types.INTEGER);
    register("POSITIVEN", Types.INTEGER);
    register("SIGNTYPE", Types.INTEGER);
    register("SIMPLE_INTEGER", Types.INTEGER);
    register("SIMPLE_FLOAT", Types.REAL);
    register("SIMPLE_DOUBLE", Types.DOUBLE);

    // Character
    register("VARCHAR2", Types.VARCHAR);
    register("NVARCHAR2", Types.NVARCHAR);
    register("CHAR", Types.CHAR);
    register("NCHAR", Types.NCHAR);
    register("LONG", Types.LONGVARCHAR);
    register("STRING", Types.VARCHAR);

    // LOB
    register("CLOB", Types.CLOB);
    register("NCLOB", Types.NCLOB);
    register("BLOB", Types.BLOB);
    register("BFILE", Types.OTHER);

    // Binary
    register("RAW", Types.VARBINARY);
    register("LONG RAW", Types.LONGVARBINARY);

    // Date/Time
    register("DATE", Types.TIMESTAMP);
    register("TIMESTAMP", Types.TIMESTAMP);
    register("TIMESTAMP WITH TIME ZONE", Types.TIMESTAMP_WITH_TIMEZONE);
    register("TIMESTAMP WITH LOCAL TIME ZONE", Types.TIMESTAMP);
    register("INTERVAL YEAR TO MONTH", Types.OTHER);
    register("INTERVAL DAY TO SECOND", Types.OTHER);

    // Oracle-specific identifiers
    register("ROWID", Types.ROWID);
    register("UROWID", Types.ROWID);

    // XML
    register("XMLTYPE", Types.SQLXML);
    register("SYS.XMLTYPE", Types.SQLXML);

    // Spatial
    register("SDO_GEOMETRY", Types.STRUCT);
    register("MDSYS.SDO_GEOMETRY", Types.STRUCT);

    // JSON (Oracle 21c+)
    register("JSON", Types.OTHER);

    // Boolean (Oracle 23c+)
    register("BOOLEAN", Types.BOOLEAN);

    // REF CURSOR
    register("REF CURSOR", Types.REF_CURSOR);
    register("SYS_REFCURSOR", Types.REF_CURSOR);

    // Object / Collection
    register("ANYDATA", Types.OTHER);
    register("ANYTYPE", Types.OTHER);
    register("ANYDATASET", Types.OTHER);
  }

  @Override
  public int resolveJdbcType(final String nativeTypeName, final int vendorJdbcType) {
    // Oracle returns Types.NUMERIC for all number types; resolve precision-based subtypes
    // via the vendor code (Oracle driver usually sets -7 for BOOLEAN, etc.)
    if ("NUMBER".equalsIgnoreCase(nativeTypeName != null ? nativeTypeName.trim() : "")) {
      // Vendor code from Oracle JDBC: -7 = BIT (boolean), -6 = TINYINT, 5 = SMALLINT, 4 = INTEGER
      if (vendorJdbcType == Types.BIT || vendorJdbcType == Types.BOOLEAN) {
        return Types.BOOLEAN;
      }
      if (vendorJdbcType == Types.TINYINT || vendorJdbcType == Types.SMALLINT
          || vendorJdbcType == Types.INTEGER || vendorJdbcType == Types.BIGINT) {
        return vendorJdbcType;
      }
    }
    return super.resolveJdbcType(nativeTypeName, vendorJdbcType);
  }
}
