package org.simplepoint.plugin.dna.core.api.spi;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps vendor-specific native database type names to standard JDBC type codes.
 *
 * <p>Each {@link JdbcDatabaseDialect} returns a type mapping that understands the native type
 * vocabulary of its target database. The mapping normalizes vendor-specific type names to
 * standard {@link java.sql.Types} constants so that federated metadata and query results
 * present a consistent JDBC interface regardless of the underlying datasource.</p>
 *
 * <p>Implementations should be stateless and thread-safe. Extend {@link StandardJdbcTypeMapping}
 * and call {@link StandardJdbcTypeMapping#register(String, int)} to add vendor-specific entries.</p>
 */
public class JdbcTypeMapping {

  private final Map<String, Integer> typeNameMap;

  /**
   * Creates an empty type mapping. Subclasses populate entries via {@link #register(String, int)}.
   */
  protected JdbcTypeMapping() {
    this.typeNameMap = new LinkedHashMap<>();
  }

  /**
   * Copy constructor that inherits all entries from a parent mapping.
   *
   * @param parent parent mapping to copy from
   */
  protected JdbcTypeMapping(final JdbcTypeMapping parent) {
    this.typeNameMap = new LinkedHashMap<>(parent.typeNameMap);
  }

  /**
   * Registers a native type name to JDBC type code mapping.
   * Names are stored in upper-case for case-insensitive lookup.
   *
   * @param nativeTypeName native database type name
   * @param jdbcType JDBC type code from {@link java.sql.Types}
   */
  protected final void register(final String nativeTypeName, final int jdbcType) {
    if (nativeTypeName != null) {
      typeNameMap.put(nativeTypeName.toUpperCase(), jdbcType);
    }
  }

  /**
   * Resolves the standard JDBC type code for a given native type name and vendor-reported JDBC type.
   *
   * <p>Resolution order:</p>
   * <ol>
   *   <li>Look up the exact native type name (upper-cased, trimmed) in the type map</li>
   *   <li>Strip trailing size/precision specifiers and try again (e.g. {@code VARCHAR(255)} → {@code VARCHAR})</li>
   *   <li>If the vendor-reported JDBC type is valid (not 0 and not {@link Types#OTHER}), return it</li>
   *   <li>Fall back to {@link Types#VARCHAR}</li>
   * </ol>
   *
   * @param nativeTypeName native type name from the database driver
   * @param vendorJdbcType JDBC type code reported by the database driver
   * @return resolved standard JDBC type code
   */
  public int resolveJdbcType(final String nativeTypeName, final int vendorJdbcType) {
    if (nativeTypeName != null) {
      String normalized = nativeTypeName.trim().toUpperCase();

      // Exact match
      Integer mapped = typeNameMap.get(normalized);
      if (mapped != null) {
        return mapped;
      }

      // Strip size/precision qualifiers: "VARCHAR(255)" → "VARCHAR", "NUMERIC(10,2)" → "NUMERIC"
      int parenIndex = normalized.indexOf('(');
      if (parenIndex > 0) {
        mapped = typeNameMap.get(normalized.substring(0, parenIndex).trim());
        if (mapped != null) {
          return mapped;
        }
      }

      // Strip UNSIGNED / SIGNED / ZEROFILL / ARRAY suffixes
      String stripped = stripModifiers(normalized);
      if (!stripped.equals(normalized)) {
        mapped = typeNameMap.get(stripped);
        if (mapped != null) {
          return mapped;
        }
      }
    }

    // Vendor-reported type is usually reliable if not OTHER/NULL
    if (vendorJdbcType != 0 && vendorJdbcType != Types.OTHER) {
      return vendorJdbcType;
    }

    return Types.VARCHAR;
  }

  /**
   * Returns the canonical JDBC type name for a given JDBC type code.
   *
   * @param jdbcType JDBC type code
   * @return canonical type name
   */
  public String resolveTypeName(final int jdbcType) {
    return switch (jdbcType) {
      case Types.BIT -> "BIT";
      case Types.BOOLEAN -> "BOOLEAN";
      case Types.TINYINT -> "TINYINT";
      case Types.SMALLINT -> "SMALLINT";
      case Types.INTEGER -> "INTEGER";
      case Types.BIGINT -> "BIGINT";
      case Types.REAL -> "REAL";
      case Types.FLOAT -> "FLOAT";
      case Types.DOUBLE -> "DOUBLE";
      case Types.DECIMAL -> "DECIMAL";
      case Types.NUMERIC -> "NUMERIC";
      case Types.CHAR -> "CHAR";
      case Types.VARCHAR -> "VARCHAR";
      case Types.LONGVARCHAR -> "LONGVARCHAR";
      case Types.NCHAR -> "NCHAR";
      case Types.NVARCHAR -> "NVARCHAR";
      case Types.LONGNVARCHAR -> "LONGNVARCHAR";
      case Types.CLOB -> "CLOB";
      case Types.NCLOB -> "NCLOB";
      case Types.BINARY -> "BINARY";
      case Types.VARBINARY -> "VARBINARY";
      case Types.LONGVARBINARY -> "LONGVARBINARY";
      case Types.BLOB -> "BLOB";
      case Types.DATE -> "DATE";
      case Types.TIME -> "TIME";
      case Types.TIME_WITH_TIMEZONE -> "TIME_WITH_TIMEZONE";
      case Types.TIMESTAMP -> "TIMESTAMP";
      case Types.TIMESTAMP_WITH_TIMEZONE -> "TIMESTAMP_WITH_TIMEZONE";
      case Types.ARRAY -> "ARRAY";
      case Types.STRUCT -> "STRUCT";
      case Types.REF -> "REF";
      case Types.REF_CURSOR -> "REF_CURSOR";
      case Types.SQLXML -> "SQLXML";
      case Types.ROWID -> "ROWID";
      case Types.DATALINK -> "DATALINK";
      case Types.JAVA_OBJECT -> "JAVA_OBJECT";
      case Types.DISTINCT -> "DISTINCT";
      case Types.NULL -> "NULL";
      case Types.OTHER -> "OTHER";
      default -> "VARCHAR";
    };
  }

  /**
   * Returns whether the given JDBC type is a signed numeric type.
   *
   * @param jdbcType JDBC type code
   * @return true if signed
   */
  public boolean isSigned(final int jdbcType) {
    return switch (jdbcType) {
      case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
           Types.REAL, Types.FLOAT, Types.DOUBLE,
           Types.DECIMAL, Types.NUMERIC -> true;
      default -> false;
    };
  }

  /**
   * Returns whether the given JDBC type is case-sensitive.
   *
   * @param jdbcType JDBC type code
   * @return true if case-sensitive
   */
  public boolean isCaseSensitive(final int jdbcType) {
    return switch (jdbcType) {
      case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
           Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
           Types.CLOB, Types.NCLOB -> true;
      default -> false;
    };
  }

  /**
   * Returns the Java class name for the given JDBC type code.
   *
   * @param jdbcType JDBC type code
   * @return fully qualified Java class name
   */
  public String getJavaClassName(final int jdbcType) {
    return switch (jdbcType) {
      case Types.BIT, Types.BOOLEAN -> Boolean.class.getName();
      case Types.TINYINT -> Byte.class.getName();
      case Types.SMALLINT -> Short.class.getName();
      case Types.INTEGER -> Integer.class.getName();
      case Types.BIGINT -> Long.class.getName();
      case Types.REAL -> Float.class.getName();
      case Types.FLOAT, Types.DOUBLE -> Double.class.getName();
      case Types.DECIMAL, Types.NUMERIC -> BigDecimal.class.getName();
      case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
           Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR -> String.class.getName();
      case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> byte[].class.getName();
      case Types.DATE -> java.sql.Date.class.getName();
      case Types.TIME, Types.TIME_WITH_TIMEZONE -> java.sql.Time.class.getName();
      case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> java.sql.Timestamp.class.getName();
      case Types.BLOB -> java.sql.Blob.class.getName();
      case Types.CLOB -> java.sql.Clob.class.getName();
      case Types.NCLOB -> java.sql.NClob.class.getName();
      case Types.ARRAY -> java.sql.Array.class.getName();
      case Types.STRUCT -> java.sql.Struct.class.getName();
      case Types.REF -> java.sql.Ref.class.getName();
      case Types.SQLXML -> java.sql.SQLXML.class.getName();
      case Types.ROWID -> java.sql.RowId.class.getName();
      default -> Object.class.getName();
    };
  }

  /**
   * Returns an unmodifiable view of all registered type name mappings.
   *
   * @return type name to JDBC type code map
   */
  public Map<String, Integer> registeredMappings() {
    return Collections.unmodifiableMap(typeNameMap);
  }

  private static String stripModifiers(final String typeName) {
    String result = typeName;
    for (String modifier : MODIFIERS) {
      int index = result.indexOf(modifier);
      if (index > 0) {
        result = result.substring(0, index).trim();
      }
    }
    return result;
  }

  private static final String[] MODIFIERS = {
      " UNSIGNED", " SIGNED", " ZEROFILL", " ARRAY", " WITH TIME ZONE", " WITHOUT TIME ZONE"
  };
}
