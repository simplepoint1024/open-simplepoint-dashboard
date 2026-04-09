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
   *
   * @param request          query request
   * @param schemaConfigurer schema registration callback
   * @return query result
   */
  CalciteQueryResult execute(CalciteQueryRequest request, CalciteSchemaConfigurer schemaConfigurer);
}
