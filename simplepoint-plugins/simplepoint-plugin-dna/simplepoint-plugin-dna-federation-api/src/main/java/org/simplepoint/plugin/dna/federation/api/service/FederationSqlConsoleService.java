package org.simplepoint.plugin.dna.federation.api.service;

import org.simplepoint.plugin.dna.federation.api.vo.FederationQueryModels;

/**
 * Service contract for the federation SQL console.
 */
public interface FederationSqlConsoleService {

  /**
   * Produces an explain-plan analysis for a read-only federation SQL query.
   *
   * @param request SQL console request
   * @return explain-plan response
   */
  FederationQueryModels.SqlExplainResult explain(FederationQueryModels.SqlConsoleRequest request);

  /**
   * Executes a read-only federation SQL query.
   *
   * @param request SQL console request
   * @return execution response
   */
  FederationQueryModels.SqlQueryResult execute(FederationQueryModels.SqlConsoleRequest request);

  /**
   * Executes a read-only federation SQL query with an already resolved datasource id.
   *
   * @param dataSourceId resolved datasource id
   * @param request SQL console request
   * @return execution response
   */
  default FederationQueryModels.SqlQueryResult execute(
      final String dataSourceId,
      final FederationQueryModels.SqlConsoleRequest request
  ) {
    return execute(request);
  }

  /**
   * Executes a DML statement (INSERT / UPDATE / DELETE / UPSERT) against a single physical datasource.
   * The statement is pushed directly to the target database without Calcite optimization.
   *
   * @param request SQL console request containing the DML statement
   * @return update result with affected row count
   */
  FederationQueryModels.SqlUpdateResult executeUpdate(FederationQueryModels.SqlConsoleRequest request);

  /**
   * Executes a DML statement with an already resolved datasource id.
   *
   * @param dataSourceId resolved datasource id
   * @param request SQL console request containing the DML statement
   * @return update result with affected row count
   */
  default FederationQueryModels.SqlUpdateResult executeUpdate(
      final String dataSourceId,
      final FederationQueryModels.SqlConsoleRequest request
  ) {
    return executeUpdate(request);
  }

  /**
   * Executes a DDL statement (CREATE / ALTER / DROP / TRUNCATE / RENAME / COMMENT)
   * against a single physical datasource. The statement is pushed directly to
   * the target database without Calcite optimization.
   *
   * @param request SQL console request containing the DDL statement
   * @return update result (affectedRows is typically 0 for DDL)
   */
  FederationQueryModels.SqlUpdateResult executeDdl(FederationQueryModels.SqlConsoleRequest request);

  /**
   * Executes a DDL statement with an already resolved datasource id.
   *
   * @param dataSourceId resolved datasource id
   * @param request SQL console request containing the DDL statement
   * @return update result
   */
  default FederationQueryModels.SqlUpdateResult executeDdl(
      final String dataSourceId,
      final FederationQueryModels.SqlConsoleRequest request
  ) {
    return executeDdl(request);
  }
}
