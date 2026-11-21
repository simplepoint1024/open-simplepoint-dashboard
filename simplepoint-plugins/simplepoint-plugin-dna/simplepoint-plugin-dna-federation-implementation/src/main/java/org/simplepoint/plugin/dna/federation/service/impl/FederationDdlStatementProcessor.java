package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.trimToNull;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException;
import org.simplepoint.plugin.dna.federation.service.impl.FederationDdlPlan.ObjectKind;
import org.simplepoint.plugin.dna.federation.service.impl.FederationDdlPlan.Role;

/** Resolves every explicit datasource in a completely parsed DDL statement. */
class FederationDdlStatementProcessor {
  // Classification only. Admission and rewriting must use the complete parsed plan.
  static final Pattern DDL_PREFIX_PATTERN =
      Pattern.compile(
          "\\s*(CREATE|ALTER|DROP|TRUNCATE|RENAME|COMMENT\\s+ON)\\b", Pattern.CASE_INSENSITIVE);

  private final JdbcDataSourceDefinitionService dataSourceService;

  FederationDdlStatementProcessor(JdbcDataSourceDefinitionService dataSourceService) {
    this.dataSourceService = dataSourceService;
  }

  DdlTarget resolve(JdbcDataSourceDefinition selectedDataSource, String sql) {
    return resolve(selectedDataSource, FederationDdlPlan.parse(sql));
  }

  DdlTarget resolve(JdbcDataSourceDefinition selectedDataSource, FederationDdlPlan plan) {
    var byCode = new LinkedHashMap<String, JdbcDataSourceDefinition>();
    for (var definition : dataSourceService.listEnabledDefinitions()) {
      String code = trimToNull(definition == null ? null : definition.getCode());
      if (code != null) {
        var previous = byCode.putIfAbsent(code.toLowerCase(Locale.ROOT), definition);
        if (previous != null && !Objects.equals(previous.getId(), definition.getId())) {
          throw scopeInvalid();
        }
      }
    }
    var anchor =
        plan.objects().stream()
            .filter(object -> object.role() == Role.INDEX_OWNER)
            .findFirst()
            .orElse(plan.objects().getFirst());
    var resolved =
        anchor.names().size() < 2
            ? selectedDataSource
            : byCode.getOrDefault(
                anchor.names().getFirst().toLowerCase(Locale.ROOT), selectedDataSource);
    for (var object : plan.objects()) {
      var explicit =
          object.names().size() < 2
              ? null
              : byCode.get(object.names().getFirst().toLowerCase(Locale.ROOT));
      // Unqualified write targets retain the selected source. A dependency cannot relocate them.
      if (explicit == null && object.role() == Role.TARGET && object.kind() != ObjectKind.INDEX) {
        explicit = selectedDataSource;
      }
      if (explicit != null && !Objects.equals(resolved.getId(), explicit.getId())) {
        throw scopeInvalid();
      }
    }
    // An unqualified rename destination/index/foreign key belongs to the executing connection.
    // Unknown qualifiers remain native schema/catalog names on that same connection.
    return new DdlTarget(resolved);
  }

  private static FederationSqlWriteException scopeInvalid() {
    return new FederationSqlWriteException(
        FederationSqlWriteException.Code.SQL_WRITE_SCOPE_INVALID);
  }

  static String rewrite(String sql, String dataSourceCode) {
    return sql == null || dataSourceCode == null
        ? sql
        : FederationDdlPlan.parse(sql).nativeSql(dataSourceCode);
  }

  record DdlTarget(JdbcDataSourceDefinition dataSource) {}
}
