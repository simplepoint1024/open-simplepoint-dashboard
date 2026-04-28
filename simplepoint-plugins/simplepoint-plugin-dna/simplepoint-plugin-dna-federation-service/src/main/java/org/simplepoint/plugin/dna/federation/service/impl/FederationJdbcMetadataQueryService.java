package org.simplepoint.plugin.dna.federation.service.impl;

import java.util.List;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.federation.api.vo.FederationJdbcDriverModels;

/**
 * Internal service contract for aggregating JDBC metadata across multiple federation data sources.
 * Separates metadata query responsibility from session/auth management in the driver gateway.
 */
interface FederationJdbcMetadataQueryService {

  /**
   * Returns catalog metadata for the given authorized data sources.
   *
   * @param dataSources authorized data sources
   * @return tabular catalog result
   */
  FederationJdbcDriverModels.TabularResult catalogs(List<JdbcDataSourceDefinition> dataSources);

  /**
   * Returns schema metadata, optionally filtered by catalog and schema pattern.
   *
   * @param dataSources    authorized data sources
   * @param catalogPattern optional catalog pattern
   * @param schemaPattern  optional schema pattern
   * @return tabular schema result
   */
  FederationJdbcDriverModels.TabularResult schemas(
      List<JdbcDataSourceDefinition> dataSources,
      String catalogPattern,
      String schemaPattern
  );

  /**
   * Returns deduplicated table-type metadata across all given data sources.
   *
   * @param dataSources authorized data sources
   * @return tabular table-type result
   */
  FederationJdbcDriverModels.TabularResult tableTypes(List<JdbcDataSourceDefinition> dataSources);

  /**
   * Returns table metadata, optionally filtered by catalog, schema, table pattern and types.
   *
   * @param dataSources    authorized data sources
   * @param catalogPattern optional catalog pattern
   * @param schemaPattern  optional schema pattern
   * @param tablePattern   optional table pattern
   * @param types          optional table types
   * @return tabular table result
   */
  FederationJdbcDriverModels.TabularResult tables(
      List<JdbcDataSourceDefinition> dataSources,
      String catalogPattern,
      String schemaPattern,
      String tablePattern,
      List<String> types
  );

  /**
   * Returns column metadata, optionally filtered by catalog, schema, table and column pattern.
   *
   * @param dataSources    authorized data sources
   * @param catalogPattern optional catalog pattern
   * @param schemaPattern  optional schema pattern
   * @param tablePattern   optional table pattern
   * @param columnPattern  optional column pattern
   * @return tabular column result
   */
  FederationJdbcDriverModels.TabularResult columns(
      List<JdbcDataSourceDefinition> dataSources,
      String catalogPattern,
      String schemaPattern,
      String tablePattern,
      String columnPattern
  );

  /**
   * Returns primary key metadata for a specific table.
   *
   * @param dataSources authorized data sources
   * @param catalog     optional catalog name
   * @param schema      optional schema name
   * @param table       table name
   * @return tabular primary-key result
   */
  FederationJdbcDriverModels.TabularResult primaryKeys(
      List<JdbcDataSourceDefinition> dataSources,
      String catalog,
      String schema,
      String table
  );

  /**
   * Returns index information for a specific table.
   *
   * @param dataSources authorized data sources
   * @param catalog     optional catalog name
   * @param schema      optional schema name
   * @param table       table name
   * @param unique      only unique indexes
   * @param approximate allow approximate results
   * @return tabular index-info result
   */
  FederationJdbcDriverModels.TabularResult indexInfo(
      List<JdbcDataSourceDefinition> dataSources,
      String catalog,
      String schema,
      String table,
      boolean unique,
      boolean approximate
  );

  /**
   * Returns imported (foreign) key metadata for a specific table.
   *
   * @param dataSources authorized data sources
   * @param catalog     optional catalog name
   * @param schema      optional schema name
   * @param table       table name
   * @return tabular imported-keys result
   */
  FederationJdbcDriverModels.TabularResult importedKeys(
      List<JdbcDataSourceDefinition> dataSources,
      String catalog,
      String schema,
      String table
  );

  /**
   * Returns exported (foreign) key metadata for a specific table.
   *
   * @param dataSources authorized data sources
   * @param catalog     optional catalog name
   * @param schema      optional schema name
   * @param table       table name
   * @return tabular exported-keys result
   */
  FederationJdbcDriverModels.TabularResult exportedKeys(
      List<JdbcDataSourceDefinition> dataSources,
      String catalog,
      String schema,
      String table
  );

  /**
   * Returns deduplicated SQL type information across all given data sources.
   *
   * @param dataSources authorized data sources
   * @return tabular type-info result
   */
  FederationJdbcDriverModels.TabularResult typeInfo(List<JdbcDataSourceDefinition> dataSources);
}
