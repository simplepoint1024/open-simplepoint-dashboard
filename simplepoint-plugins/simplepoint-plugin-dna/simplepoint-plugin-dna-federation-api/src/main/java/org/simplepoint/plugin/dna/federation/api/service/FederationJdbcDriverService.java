package org.simplepoint.plugin.dna.federation.api.service;

import java.util.List;
import org.simplepoint.plugin.dna.federation.api.vo.FederationJdbcDriverModels;
import org.simplepoint.plugin.dna.federation.api.vo.FederationQueryModels;

/**
 * Service contract for the external DNA JDBC driver gateway.
 */
public interface FederationJdbcDriverService {

  /**
   * Stateful JDBC driver session reused by transports that keep a long-lived server connection.
   */
  interface DriverSession extends AutoCloseable {

    @Override
    void close();
  }

  /**
   * Verifies credentials and returns connection metadata.
   *
   * @param request driver request
   * @return ping result
   */
  FederationJdbcDriverModels.PingResult ping(FederationJdbcDriverModels.DriverRequest request);

  /**
   * Returns the current federation catalog as a JDBC metadata result.
   *
   * @param request driver request
   * @return tabular metadata result
   */
  FederationJdbcDriverModels.TabularResult catalogs(FederationJdbcDriverModels.DriverRequest request);

  /**
   * Returns schemas exposed by the current federation catalog.
   *
   * @param request driver request
   * @param schemaPattern optional schema pattern
   * @return tabular metadata result
   */
  FederationJdbcDriverModels.TabularResult schemas(
      FederationJdbcDriverModels.DriverRequest request,
      String catalogPattern,
      String schemaPattern
  );

  /**
   * Returns table types supported by the current federation catalog.
   *
   * @param request driver request
   * @return tabular metadata result
   */
  FederationJdbcDriverModels.TabularResult tableTypes(FederationJdbcDriverModels.DriverRequest request);

  /**
   * Returns tables visible to the current federation catalog.
   *
   * @param request driver request
   * @param schemaPattern optional schema pattern
   * @param tablePattern optional table pattern
   * @param types optional table types
   * @return tabular metadata result
   */
  FederationJdbcDriverModels.TabularResult tables(
      FederationJdbcDriverModels.DriverRequest request,
      String catalogPattern,
      String schemaPattern,
      String tablePattern,
      List<String> types
  );

  /**
   * Returns columns visible to the current federation catalog.
   *
   * @param request driver request
   * @param catalogPattern optional catalog pattern
   * @param schemaPattern optional schema pattern
   * @param tablePattern optional table pattern
   * @param columnPattern optional column pattern
   * @return tabular metadata result
   */
  FederationJdbcDriverModels.TabularResult columns(
      FederationJdbcDriverModels.DriverRequest request,
      String catalogPattern,
      String schemaPattern,
      String tablePattern,
      String columnPattern
  );

  /**
   * Returns primary keys for the specified table.
   *
   * @param request driver request
   * @param catalog optional catalog name
   * @param schema optional schema name
   * @param table table name
   * @return tabular metadata result
   */
  FederationJdbcDriverModels.TabularResult primaryKeys(
      FederationJdbcDriverModels.DriverRequest request,
      String catalog,
      String schema,
      String table
  );

  /**
   * Returns index information for the specified table.
   *
   * @param request driver request
   * @param catalog optional catalog name
   * @param schema optional schema name
   * @param table table name
   * @param unique only unique indexes
   * @param approximate allow approximate results
   * @return tabular metadata result
   */
  FederationJdbcDriverModels.TabularResult indexInfo(
      FederationJdbcDriverModels.DriverRequest request,
      String catalog,
      String schema,
      String table,
      boolean unique,
      boolean approximate
  );

  /**
   * Returns imported (foreign) keys for the specified table.
   *
   * @param request driver request
   * @param catalog optional catalog name
   * @param schema optional schema name
   * @param table table name
   * @return tabular metadata result
   */
  FederationJdbcDriverModels.TabularResult importedKeys(
      FederationJdbcDriverModels.DriverRequest request,
      String catalog,
      String schema,
      String table
  );

  /**
   * Returns exported (foreign) keys for the specified table.
   *
   * @param request driver request
   * @param catalog optional catalog name
   * @param schema optional schema name
   * @param table table name
   * @return tabular metadata result
   */
  FederationJdbcDriverModels.TabularResult exportedKeys(
      FederationJdbcDriverModels.DriverRequest request,
      String catalog,
      String schema,
      String table
  );

  /**
   * Returns type information supported by the federation catalog.
   *
   * @param request driver request
   * @return tabular metadata result
   */
  FederationJdbcDriverModels.TabularResult typeInfo(FederationJdbcDriverModels.DriverRequest request);

  /**
   * Opens a reusable authenticated driver session.
   *
   * @param request driver request
   * @return authenticated driver session
   */
  DriverSession openSession(FederationJdbcDriverModels.DriverRequest request);

  /**
   * Verifies an existing driver session and returns connection metadata.
   *
   * @param session reusable driver session
   * @param contextId optional permission context id
   * @return ping result
   */
  FederationJdbcDriverModels.PingResult ping(DriverSession session, String contextId);

  /**
   * Returns the current federation catalog for an existing driver session.
   *
   * @param session reusable driver session
   * @param contextId optional permission context id
   * @return tabular metadata result
   */
  FederationJdbcDriverModels.TabularResult catalogs(DriverSession session, String contextId);

  /**
   * Returns schemas for an existing driver session.
   *
   * @param session reusable driver session
   * @param contextId optional permission context id
   * @param schemaPattern optional schema pattern
   * @return tabular metadata result
   */
  FederationJdbcDriverModels.TabularResult schemas(
      DriverSession session,
      String contextId,
      String catalogPattern,
      String schemaPattern
  );

  /**
   * Returns table types for an existing driver session.
   *
   * @param session reusable driver session
   * @param contextId optional permission context id
   * @return tabular metadata result
   */
  FederationJdbcDriverModels.TabularResult tableTypes(DriverSession session, String contextId);

  /**
   * Returns tables for an existing driver session.
   *
   * @param session reusable driver session
   * @param contextId optional permission context id
   * @param schemaPattern optional schema pattern
   * @param tablePattern optional table pattern
   * @param types optional table types
   * @return tabular metadata result
   */
  FederationJdbcDriverModels.TabularResult tables(
      DriverSession session,
      String contextId,
      String catalogPattern,
      String schemaPattern,
      String tablePattern,
      List<String> types
  );

  /**
   * Returns columns for an existing driver session.
   *
   * @param session reusable driver session
   * @param contextId optional permission context id
   * @param catalogPattern optional catalog pattern
   * @param schemaPattern optional schema pattern
   * @param tablePattern optional table pattern
   * @param columnPattern optional column pattern
   * @return tabular metadata result
   */
  FederationJdbcDriverModels.TabularResult columns(
      DriverSession session,
      String contextId,
      String catalogPattern,
      String schemaPattern,
      String tablePattern,
      String columnPattern
  );

  /**
   * Returns primary keys for an existing driver session.
   *
   * @param session reusable driver session
   * @param contextId optional permission context id
   * @param catalog optional catalog name
   * @param schema optional schema name
   * @param table table name
   * @return tabular metadata result
   */
  FederationJdbcDriverModels.TabularResult primaryKeys(
      DriverSession session,
      String contextId,
      String catalog,
      String schema,
      String table
  );

  /**
   * Returns index information for an existing driver session.
   *
   * @param session reusable driver session
   * @param contextId optional permission context id
   * @param catalog optional catalog name
   * @param schema optional schema name
   * @param table table name
   * @param unique only unique indexes
   * @param approximate allow approximate results
   * @return tabular metadata result
   */
  FederationJdbcDriverModels.TabularResult indexInfo(
      DriverSession session,
      String contextId,
      String catalog,
      String schema,
      String table,
      boolean unique,
      boolean approximate
  );

  /**
   * Returns imported keys for an existing driver session.
   *
   * @param session reusable driver session
   * @param contextId optional permission context id
   * @param catalog optional catalog name
   * @param schema optional schema name
   * @param table table name
   * @return tabular metadata result
   */
  FederationJdbcDriverModels.TabularResult importedKeys(
      DriverSession session,
      String contextId,
      String catalog,
      String schema,
      String table
  );

  /**
   * Returns exported keys for an existing driver session.
   *
   * @param session reusable driver session
   * @param contextId optional permission context id
   * @param catalog optional catalog name
   * @param schema optional schema name
   * @param table table name
   * @return tabular metadata result
   */
  FederationJdbcDriverModels.TabularResult exportedKeys(
      DriverSession session,
      String contextId,
      String catalog,
      String schema,
      String table
  );

  /**
   * Returns type information for an existing driver session.
   *
   * @param session reusable driver session
   * @param contextId optional permission context id
   * @return tabular metadata result
   */
  FederationJdbcDriverModels.TabularResult typeInfo(DriverSession session, String contextId);

  /**
   * Flushes all cached metadata entries from the global cache.
   *
   * @param session reusable driver session
   * @return the number of entries flushed, or -1 if cache is unavailable
   */
  long flushCache(DriverSession session);

  /**
   * Executes a read-only query through the current federation catalog.
   *
   * @param request driver request
   * @param queryRequest query request
   * @return query result
   */
  FederationQueryModels.SqlQueryResult query(
      FederationJdbcDriverModels.DriverRequest request,
      FederationJdbcDriverModels.QueryRequest queryRequest
  );

  /**
   * Executes a read-only query for an existing driver session.
   *
   * @param session reusable driver session
   * @param contextId optional permission context id
   * @param queryRequest query request
   * @return query result
   */
  FederationQueryModels.SqlQueryResult query(
      DriverSession session,
      String contextId,
      FederationJdbcDriverModels.QueryRequest queryRequest
  );

  /**
   * Executes a DML statement (INSERT / UPDATE / DELETE / UPSERT) through a request-based call.
   *
   * @param request driver request
   * @param queryRequest query request containing the DML SQL
   * @return update result with affected row count
   */
  FederationQueryModels.SqlUpdateResult executeUpdate(
      FederationJdbcDriverModels.DriverRequest request,
      FederationJdbcDriverModels.QueryRequest queryRequest
  );

  /**
   * Executes a DML statement for an existing driver session.
   *
   * @param session reusable driver session
   * @param contextId optional permission context id
   * @param queryRequest query request containing the DML SQL
   * @return update result with affected row count
   */
  FederationQueryModels.SqlUpdateResult executeUpdate(
      DriverSession session,
      String contextId,
      FederationJdbcDriverModels.QueryRequest queryRequest
  );
}
