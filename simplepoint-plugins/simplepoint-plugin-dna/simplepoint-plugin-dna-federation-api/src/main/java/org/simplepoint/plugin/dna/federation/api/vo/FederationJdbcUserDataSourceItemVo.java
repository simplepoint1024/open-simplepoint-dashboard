package org.simplepoint.plugin.dna.federation.api.vo;

/**
 * Datasource option exposed for JDBC-user assignment.
 *
 * @param id datasource id
 * @param code datasource code
 * @param name datasource name
 * @param databaseProductName database product name
 */
public record FederationJdbcUserDataSourceItemVo(
    String id,
    String code,
    String name,
    String databaseProductName
) {
}
