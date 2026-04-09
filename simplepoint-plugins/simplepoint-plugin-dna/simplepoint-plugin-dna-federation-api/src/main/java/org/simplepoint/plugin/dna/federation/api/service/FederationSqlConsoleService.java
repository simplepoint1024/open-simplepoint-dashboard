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
}
