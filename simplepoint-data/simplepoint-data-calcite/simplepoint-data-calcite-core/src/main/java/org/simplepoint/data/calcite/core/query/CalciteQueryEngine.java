package org.simplepoint.data.calcite.core.query;

/**
 * Read-only Calcite SQL execution engine.
 */
public interface CalciteQueryEngine {

  /**
   * Produces an explain-plan analysis for the supplied read-only SQL.
   *
   * @param request          query request
   * @param schemaConfigurer schema registration callback
   * @return explain analysis
   */
  CalciteQueryAnalysis explain(CalciteQueryRequest request, CalciteSchemaConfigurer schemaConfigurer);

  /**
   * Executes the supplied read-only SQL and returns a paged in-memory snapshot.
   * This variant also produces an explain analysis as part of execution.
   *
   * @param request          query request
   * @param schemaConfigurer schema registration callback
   * @return query result (includes fresh analysis)
   */
  CalciteQueryResult execute(CalciteQueryRequest request, CalciteSchemaConfigurer schemaConfigurer);

  /**
   * Executes the supplied read-only SQL and returns a paged in-memory snapshot,
   * reusing a pre-computed explain analysis to skip the redundant explain phase.
   *
   * @param request          query request
   * @param schemaConfigurer schema registration callback
   * @param preComputedAnalysis analysis from a previous {@link #explain} call
   * @return query result (carries the supplied analysis)
   */
  default CalciteQueryResult execute(
      CalciteQueryRequest request,
      CalciteSchemaConfigurer schemaConfigurer,
      CalciteQueryAnalysis preComputedAnalysis
  ) {
    return execute(request, schemaConfigurer);
  }
}
