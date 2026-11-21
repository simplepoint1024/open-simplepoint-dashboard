package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.requireValue;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.trimToNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.federation.service.impl.FederationSqlAnalysisUtils.TableReferenceSummary;

/** Resolves and rewrites DML statements for pushdown to a physical datasource. */
class FederationDmlStatementProcessor {

  private final JdbcDataSourceDefinitionService dataSourceService;

  FederationDmlStatementProcessor(final JdbcDataSourceDefinitionService dataSourceService) {
    this.dataSourceService = dataSourceService;
  }

  /**
   * Resolves the single physical datasource that is the DML target. DML must target exactly one
   * physical datasource — cross-source DML is forbidden.
   */
  DmlTarget resolve(
      final JdbcDataSourceDefinition selectedDataSource, final TableReferenceSummary references) {
    if (!references.hasTableReferences()) {
      throw FederationSqlStatementBoundary.unsupported();
    }
    LinkedHashMap<String, JdbcDataSourceDefinition> resolved = new LinkedHashMap<>();
    List<JdbcDataSourceDefinition> enabledDataSources = dataSourceService.listEnabledDefinitions();
    LinkedHashMap<String, JdbcDataSourceDefinition> dataSourcesByCode = new LinkedHashMap<>();
    enabledDataSources.forEach(
        definition -> {
          String code = trimToNull(definition == null ? null : definition.getCode());
          if (code != null) {
            var previous = dataSourcesByCode.putIfAbsent(code.toLowerCase(Locale.ROOT), definition);
            if (previous != null
                && !java.util.Objects.equals(previous.getId(), definition.getId())) {
              throw scopeInvalid();
            }
          }
        });
    boolean usesSelectedDataSource = false;
    for (List<String> identifier : references.identifiers()) {
      if (identifier.size() < 2) {
        usesSelectedDataSource = true;
        continue;
      }
      JdbcDataSourceDefinition explicitDataSource =
          dataSourcesByCode.get(identifier.get(0).toLowerCase(Locale.ROOT));
      if (explicitDataSource != null) {
        resolved.putIfAbsent(explicitDataSource.getId(), explicitDataSource);
      } else {
        usesSelectedDataSource = true;
      }
    }
    if (usesSelectedDataSource) {
      resolved.putIfAbsent(
          requireValue(selectedDataSource.getId(), "数据源ID不能为空"), selectedDataSource);
    }
    if (resolved.size() > 1) {
      throw scopeInvalid();
    }
    if (resolved.isEmpty()) {
      return new DmlTarget(selectedDataSource);
    }
    return new DmlTarget(resolved.values().iterator().next());
  }

  private static org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException
      scopeInvalid() {
    return new org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException(
        org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException.Code
            .SQL_WRITE_SCOPE_INVALID);
  }

  /**
   * Rewrites only a completely parsed statement; unknown grammar is never a native escape hatch.
   */
  static String rewrite(final String sql, final String dataSourceCode) {
    return sql == null ? null : FederationDmlPlan.parse(sql).nativeSql(dataSourceCode);
  }

  record DmlTarget(JdbcDataSourceDefinition dataSource) {}

  /** Standard mutations use Calcite; parsed MERGE/UPSERT retain their verified native boundary. */
  static boolean canExecuteWithCalcite(final String sql) {
    return FederationDmlPlan.parse(sql).calcite();
  }
}
