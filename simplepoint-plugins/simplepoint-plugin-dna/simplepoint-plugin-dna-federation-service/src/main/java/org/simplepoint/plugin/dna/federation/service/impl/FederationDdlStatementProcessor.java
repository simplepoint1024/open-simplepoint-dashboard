package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.trimToNull;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;

/**
 * Resolves and rewrites DDL statements for pushdown to a physical datasource.
 */
class FederationDdlStatementProcessor {

  /**
   * Pattern that matches DDL keywords at the start of a SQL statement.
   * Covers: CREATE, ALTER, DROP, TRUNCATE, RENAME, COMMENT ON.
   */
  static final Pattern DDL_PREFIX_PATTERN = Pattern.compile(
      "\\s*(CREATE|ALTER|DROP|TRUNCATE|RENAME|COMMENT\\s+ON)\\b",
      Pattern.CASE_INSENSITIVE
  );

  /**
   * Pattern that extracts a possibly-qualified table name from DDL statements.
   * Matches: CREATE [TEMP/TEMPORARY/EXTERNAL/...] TABLE [IF NOT EXISTS] name
   *          ALTER TABLE name
   *          DROP TABLE [IF EXISTS] name
   *          TRUNCATE [TABLE] name
   *          RENAME TABLE name
   * The name may be dotted: {@code datasource.schema.table} or {@code datasource.table}.
   * Identifiers may be quoted with double-quotes, backticks, or square brackets.
   */
  static final Pattern DDL_TABLE_NAME_PATTERN = Pattern.compile(
      "(?i)(?:"
          + "(?:CREATE\\s+(?:(?:TEMP(?:ORARY)?|EXTERNAL|GLOBAL|LOCAL|VIRTUAL|UNLOGGED|MATERIALIZED|OR\\s+REPLACE)\\s+)*"
          + "(?:TABLE|VIEW|INDEX)\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?)"
          + "|(?:ALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?)"
          + "|(?:DROP\\s+(?:TABLE|VIEW|INDEX)\\s+(?:IF\\s+EXISTS\\s+)?)"
          + "|(?:TRUNCATE\\s+(?:TABLE\\s+)?)"
          + "|(?:RENAME\\s+TABLE\\s+)"
          + ")"
          + "((?:[\"\\[`]?[^\\s\"\\]`(),;]+[\"\\]`]?\\.)*[\"\\[`]?[^\\s\"\\]`(),;]+[\"\\]`]?)"
  );

  private final JdbcDataSourceDefinitionService dataSourceService;

  FederationDdlStatementProcessor(final JdbcDataSourceDefinitionService dataSourceService) {
    this.dataSourceService = dataSourceService;
  }

  /**
   * Resolves the target physical datasource for a DDL statement.
   * Uses regex-based identifier extraction since Calcite's standard parser
   * does not support DDL.
   */
  DdlTarget resolve(final JdbcDataSourceDefinition selectedDataSource, final String sql) {
    Matcher matcher = DDL_TABLE_NAME_PATTERN.matcher(sql);
    if (!matcher.find()) {
      return new DdlTarget(selectedDataSource);
    }
    String qualifiedName = matcher.group(1);
    if (qualifiedName == null || qualifiedName.isBlank()) {
      return new DdlTarget(selectedDataSource);
    }
    String[] segments = qualifiedName.split("\\.");
    if (segments.length < 2) {
      return new DdlTarget(selectedDataSource);
    }
    String candidateCode = stripQuotes(segments[0]);
    List<JdbcDataSourceDefinition> enabledDataSources = dataSourceService.listEnabledDefinitions();
    for (JdbcDataSourceDefinition definition : enabledDataSources) {
      String code = trimToNull(definition.getCode());
      if (code != null && code.equalsIgnoreCase(candidateCode)) {
        return new DdlTarget(definition);
      }
    }
    return new DdlTarget(selectedDataSource);
  }

  /**
   * Rewrites DDL SQL by stripping the federation datasource code prefix from table names.
   * Uses regex-based replacement since Calcite cannot parse DDL.
   */
  static String rewrite(final String sql, final String dataSourceCode) {
    if (sql == null || dataSourceCode == null) {
      return sql;
    }
    Matcher matcher = DDL_TABLE_NAME_PATTERN.matcher(sql);
    if (!matcher.find()) {
      return sql;
    }
    String qualifiedName = matcher.group(1);
    if (qualifiedName == null || qualifiedName.isBlank()) {
      return sql;
    }
    String[] segments = qualifiedName.split("\\.");
    if (segments.length < 2) {
      return sql;
    }
    String candidateCode = stripQuotes(segments[0]);
    if (!candidateCode.equalsIgnoreCase(dataSourceCode)) {
      return sql;
    }
    String nativeName = String.join(".", Arrays.copyOfRange(segments, 1, segments.length));
    return sql.substring(0, matcher.start(1)) + nativeName + sql.substring(matcher.end(1));
  }

  static String stripQuotes(final String identifier) {
    if (identifier == null || identifier.length() < 2) {
      return identifier;
    }
    char first = identifier.charAt(0);
    char last = identifier.charAt(identifier.length() - 1);
    if ((first == '"' && last == '"')
        || (first == '`' && last == '`')
        || (first == '[' && last == ']')) {
      return identifier.substring(1, identifier.length() - 1);
    }
    return identifier;
  }

  record DdlTarget(JdbcDataSourceDefinition dataSource) {
  }
}
