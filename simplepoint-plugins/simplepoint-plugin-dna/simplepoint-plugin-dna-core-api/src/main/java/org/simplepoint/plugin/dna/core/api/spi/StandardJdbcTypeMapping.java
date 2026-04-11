package org.simplepoint.plugin.dna.core.api.spi;

import java.sql.Types;

/**
 * Standard JDBC type mapping covering all SQL standard types and common aliases.
 *
 * <p>This mapping handles type names that are universally defined by the SQL standard
 * or shared across multiple database vendors. Vendor-specific mappings should extend
 * this class and call {@link #register(String, int)} to add their native types.</p>
 */
public class StandardJdbcTypeMapping extends JdbcTypeMapping {

  /** Shared singleton instance for generic / fallback use. */
  public static final StandardJdbcTypeMapping INSTANCE = new StandardJdbcTypeMapping();

  /**
   * Populates all standard SQL and JDBC type name mappings.
   */
  public StandardJdbcTypeMapping() {
    super();
    registerBooleanTypes();
    registerIntegerTypes();
    registerFloatingPointTypes();
    registerDecimalTypes();
    registerCharacterTypes();
    registerNationalCharacterTypes();
    registerLobTypes();
    registerBinaryTypes();
    registerDateTimeTypes();
    registerCollectionTypes();
    registerSpecialTypes();
  }

  /**
   * Copy constructor that inherits standard mappings and all entries from a parent.
   *
   * @param parent parent mapping
   */
  protected StandardJdbcTypeMapping(final JdbcTypeMapping parent) {
    super(parent);
  }

  // --- Boolean ---

  private void registerBooleanTypes() {
    register("BIT", Types.BIT);
    register("BOOL", Types.BOOLEAN);
    register("BOOLEAN", Types.BOOLEAN);
  }

  // --- Integer ---

  private void registerIntegerTypes() {
    register("TINYINT", Types.TINYINT);
    register("SMALLINT", Types.SMALLINT);
    register("INT2", Types.SMALLINT);
    register("MEDIUMINT", Types.INTEGER);
    register("INT", Types.INTEGER);
    register("INT4", Types.INTEGER);
    register("INTEGER", Types.INTEGER);
    register("BIGINT", Types.BIGINT);
    register("INT8", Types.BIGINT);
    register("SERIAL", Types.BIGINT);
    register("BIGSERIAL", Types.BIGINT);
    register("SMALLSERIAL", Types.SMALLINT);
  }

  // --- Floating point ---

  private void registerFloatingPointTypes() {
    register("REAL", Types.REAL);
    register("FLOAT4", Types.REAL);
    register("FLOAT", Types.FLOAT);
    register("FLOAT8", Types.DOUBLE);
    register("DOUBLE", Types.DOUBLE);
    register("DOUBLE PRECISION", Types.DOUBLE);
  }

  // --- Decimal / Numeric ---

  private void registerDecimalTypes() {
    register("DECIMAL", Types.DECIMAL);
    register("DEC", Types.DECIMAL);
    register("NUMERIC", Types.NUMERIC);
    register("NUMBER", Types.NUMERIC);
    register("FIXED", Types.DECIMAL);
    register("MONEY", Types.DECIMAL);
    register("SMALLMONEY", Types.DECIMAL);
  }

  // --- Character ---

  private void registerCharacterTypes() {
    register("CHAR", Types.CHAR);
    register("CHARACTER", Types.CHAR);
    register("BPCHAR", Types.CHAR);
    register("VARCHAR", Types.VARCHAR);
    register("VARCHAR2", Types.VARCHAR);
    register("CHARACTER VARYING", Types.VARCHAR);
    register("TINYTEXT", Types.VARCHAR);
    register("TEXT", Types.LONGVARCHAR);
    register("MEDIUMTEXT", Types.LONGVARCHAR);
    register("LONGTEXT", Types.LONGVARCHAR);
    register("LONGVARCHAR", Types.LONGVARCHAR);
    register("STRING", Types.VARCHAR);
  }

  // --- National character ---

  private void registerNationalCharacterTypes() {
    register("NCHAR", Types.NCHAR);
    register("NATIONAL CHAR", Types.NCHAR);
    register("NATIONAL CHARACTER", Types.NCHAR);
    register("NVARCHAR", Types.NVARCHAR);
    register("NVARCHAR2", Types.NVARCHAR);
    register("NATIONAL VARCHAR", Types.NVARCHAR);
    register("NATIONAL CHARACTER VARYING", Types.NVARCHAR);
    register("NTEXT", Types.LONGNVARCHAR);
    register("LONGNVARCHAR", Types.LONGNVARCHAR);
  }

  // --- LOB ---

  private void registerLobTypes() {
    register("CLOB", Types.CLOB);
    register("NCLOB", Types.NCLOB);
    register("BLOB", Types.BLOB);
    register("TINYBLOB", Types.BLOB);
    register("MEDIUMBLOB", Types.BLOB);
    register("LONGBLOB", Types.BLOB);
  }

  // --- Binary ---

  private void registerBinaryTypes() {
    register("BINARY", Types.BINARY);
    register("VARBINARY", Types.VARBINARY);
    register("LONGVARBINARY", Types.LONGVARBINARY);
    register("BYTEA", Types.VARBINARY);
    register("RAW", Types.VARBINARY);
    register("LONG RAW", Types.LONGVARBINARY);
    register("IMAGE", Types.LONGVARBINARY);
  }

  // --- Date / Time ---

  private void registerDateTimeTypes() {
    register("DATE", Types.DATE);
    register("TIME", Types.TIME);
    register("TIMETZ", Types.TIME_WITH_TIMEZONE);
    register("TIME WITH TIME ZONE", Types.TIME_WITH_TIMEZONE);
    register("TIME WITHOUT TIME ZONE", Types.TIME);
    register("TIMESTAMP", Types.TIMESTAMP);
    register("TIMESTAMPTZ", Types.TIMESTAMP_WITH_TIMEZONE);
    register("TIMESTAMP WITH TIME ZONE", Types.TIMESTAMP_WITH_TIMEZONE);
    register("TIMESTAMP WITHOUT TIME ZONE", Types.TIMESTAMP);
    register("DATETIME", Types.TIMESTAMP);
    register("DATETIME2", Types.TIMESTAMP);
    register("SMALLDATETIME", Types.TIMESTAMP);
    register("DATETIMEOFFSET", Types.TIMESTAMP_WITH_TIMEZONE);
  }

  // --- Collection / Structured ---

  private void registerCollectionTypes() {
    register("ARRAY", Types.ARRAY);
    register("STRUCT", Types.STRUCT);
    register("REF", Types.REF);
    register("REF CURSOR", Types.REF_CURSOR);
  }

  // --- Special ---

  private void registerSpecialTypes() {
    register("XML", Types.SQLXML);
    register("SQLXML", Types.SQLXML);
    register("ROWID", Types.ROWID);
    register("UROWID", Types.ROWID);
    register("DATALINK", Types.DATALINK);
    register("NULL", Types.NULL);
    register("OTHER", Types.OTHER);
  }
}
