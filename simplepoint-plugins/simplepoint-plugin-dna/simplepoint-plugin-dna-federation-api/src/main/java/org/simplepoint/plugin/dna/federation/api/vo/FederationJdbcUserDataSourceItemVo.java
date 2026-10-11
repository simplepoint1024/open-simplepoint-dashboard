package org.simplepoint.plugin.dna.federation.api.vo;

/**
 * Datasource option exposed for JDBC-user assignment.
 *
 * @param id datasource id
 * @param code datasource code
 * @param name datasource name
 * @param databaseProductName database product name
 */
@org.simplepoint.core.query.TableQuery({
    @org.simplepoint.core.query.QueryField("code"),
    @org.simplepoint.core.query.QueryField("name"),
    @org.simplepoint.core.query.QueryField("databaseProductName")
})
public record FederationJdbcUserDataSourceItemVo(
    @org.simplepoint.core.annotation.PermissionFields(
        resourceName = "org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition", value = "id")
    String id,
    @org.simplepoint.core.annotation.PermissionFields(
        resourceName = "org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition", value = "code")
    @io.swagger.v3.oas.annotations.media.Schema(title = "i18n:dna.dataSources.title.code", maxLength = 128)
    String code,
    @org.simplepoint.core.annotation.PermissionFields(
        resourceName = "org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition", value = "name")
    @io.swagger.v3.oas.annotations.media.Schema(title = "i18n:dna.dataSources.title.name", maxLength = 128)
    String name,
    @org.simplepoint.core.annotation.PermissionFields(
        resourceName = "org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition", value = "databaseProductName")
    @io.swagger.v3.oas.annotations.media.Schema(title = "i18n:dna.dataSources.title.databaseProductName", maxLength = 128)
    String databaseProductName
) {
}
