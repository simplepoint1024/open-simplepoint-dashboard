package org.simplepoint.data.calcite.core.query;

/**
 * Result-set column metadata.
 *
 * @param name     column label
 * @param typeName JDBC type name
 * @param jdbcType JDBC type code
 */
public record CalciteQueryColumn(
    String name,
    String typeName,
    Integer jdbcType
) {

  public CalciteQueryColumn(final String name, final String typeName) {
    this(name, typeName, null);
  }
}
