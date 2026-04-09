package org.simplepoint.data.calcite.core.query;

/**
 * Result-set column metadata.
 *
 * @param name     column label
 * @param typeName JDBC type name
 */
public record CalciteQueryColumn(
    String name,
    String typeName
) {
}
