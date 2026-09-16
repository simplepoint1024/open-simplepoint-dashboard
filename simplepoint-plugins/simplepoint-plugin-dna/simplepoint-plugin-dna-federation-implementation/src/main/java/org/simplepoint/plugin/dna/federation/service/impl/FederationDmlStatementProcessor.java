package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.requireValue;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.trimToNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlDelete;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlMerge;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlUpdate;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.federation.service.impl.FederationSqlAnalysisUtils.TableReferenceSummary;

/**
 * Resolves and rewrites DML statements for pushdown to a physical datasource.
 */
class FederationDmlStatementProcessor {

  private final JdbcDataSourceDefinitionService dataSourceService;

  FederationDmlStatementProcessor(final JdbcDataSourceDefinitionService dataSourceService) {
    this.dataSourceService = dataSourceService;
  }

  /**
   * Resolves the single physical datasource that is the DML target.
   * DML must target exactly one physical datasource — cross-source DML is forbidden.
   */
  DmlTarget resolve(
      final JdbcDataSourceDefinition selectedDataSource,
      final TableReferenceSummary references
  ) {
    if (!references.hasTableReferences()) {
      return new DmlTarget(selectedDataSource);
    }
    LinkedHashMap<String, JdbcDataSourceDefinition> resolved = new LinkedHashMap<>();
    List<JdbcDataSourceDefinition> enabledDataSources = dataSourceService.listEnabledDefinitions();
    LinkedHashMap<String, JdbcDataSourceDefinition> dataSourcesByCode = new LinkedHashMap<>();
    enabledDataSources.forEach(definition -> {
      String code = trimToNull(definition == null ? null : definition.getCode());
      if (code != null) {
        dataSourcesByCode.putIfAbsent(code.toLowerCase(Locale.ROOT), definition);
      }
    });
    boolean usesSelectedDataSource = false;
    for (List<String> identifier : references.identifiers()) {
      if (identifier.size() < 2) {
        usesSelectedDataSource = true;
        continue;
      }
      JdbcDataSourceDefinition explicitDataSource = dataSourcesByCode.get(identifier.get(0).toLowerCase(Locale.ROOT));
      if (explicitDataSource != null) {
        resolved.putIfAbsent(explicitDataSource.getId(), explicitDataSource);
      } else {
        usesSelectedDataSource = true;
      }
    }
    if (usesSelectedDataSource) {
      resolved.putIfAbsent(requireValue(selectedDataSource.getId(), "数据源ID不能为空"), selectedDataSource);
    }
    if (resolved.size() > 1) {
      throw new IllegalArgumentException("DML 语句不允许跨数据源操作，检测到多个目标数据源: "
          + String.join(", ", resolved.values().stream()
              .sorted(Comparator.comparing(JdbcDataSourceDefinition::getCode))
              .map(JdbcDataSourceDefinition::getCode).toList()));
    }
    if (resolved.isEmpty()) {
      return new DmlTarget(selectedDataSource);
    }
    return new DmlTarget(resolved.values().iterator().next());
  }

  /**
   * Rewrites federation DML SQL for the physical database by stripping the datasource code prefix.
   * E.g., {@code INSERT INTO mysql_ds.users ...} becomes {@code INSERT INTO users ...}
   */
  static String rewrite(final String sql, final String dataSourceCode) {
    String normalizedSql = trimToNull(sql);
    if (normalizedSql == null) {
      return sql;
    }
    try {
      SqlParser parser = SqlParser.create(normalizedSql);
      SqlNodeList statements = parser.parseStmtList();
      if (statements.size() != 1 || statements.get(0) == null) {
        return normalizedSql;
      }
      DmlTableRewriter rewriter = new DmlTableRewriter(normalizedSql, dataSourceCode);
      rewriter.rewrite(statements.get(0));
      return rewriter.apply();
    } catch (SqlParseException ex) {
      return normalizedSql;
    }
  }

  record DmlTarget(JdbcDataSourceDefinition dataSource) {
  }

  /**
   * Rewrites DML SQL by stripping the federation datasource code prefix from table identifiers.
   * Uses position-based string replacement via Calcite SqlIdentifier positions.
   */
  static final class DmlTableRewriter {

    private final String originalSql;

    private final String dataSourceCode;

    private final List<IdentifierReplacement> replacements = new ArrayList<>();

    DmlTableRewriter(final String originalSql, final String dataSourceCode) {
      this.originalSql = originalSql;
      this.dataSourceCode = dataSourceCode;
    }

    void rewrite(final SqlNode node) {
      if (node == null) {
        return;
      }
      if (node instanceof SqlInsert insert) {
        rewriteTableIdentifier(insert.getTargetTable());
        rewriteNestedDml(insert.getSource());
        return;
      }
      if (node instanceof SqlUpdate update) {
        rewriteTableIdentifier(update.getTargetTable());
        return;
      }
      if (node instanceof SqlDelete delete) {
        rewriteTableIdentifier(delete.getTargetTable());
        return;
      }
      if (node instanceof SqlMerge merge) {
        rewriteTableIdentifier(merge.getTargetTable());
      }
    }

    private void rewriteNestedDml(final SqlNode node) {
      if (node instanceof SqlSelect select) {
        rewriteSelectFrom(select.getFrom());
      }
    }

    private void rewriteSelectFrom(final SqlNode node) {
      if (node == null) {
        return;
      }
      if (node instanceof SqlIdentifier identifier) {
        rewriteTableIdentifier(identifier);
        return;
      }
      if (node instanceof SqlJoin join) {
        rewriteSelectFrom(join.getLeft());
        rewriteSelectFrom(join.getRight());
        return;
      }
      if (node instanceof SqlBasicCall call) {
        if (SqlKind.AS.equals(call.getKind()) && !call.getOperandList().isEmpty()) {
          rewriteSelectFrom(call.getOperandList().get(0));
        }
      }
    }

    private void rewriteTableIdentifier(final SqlNode node) {
      if (!(node instanceof SqlIdentifier identifier)) {
        return;
      }
      if (identifier.names == null || identifier.names.size() < 2) {
        return;
      }
      String firstSegment = trimToNull(identifier.names.get(0));
      if (firstSegment == null || !firstSegment.equalsIgnoreCase(dataSourceCode)) {
        return;
      }
      List<String> nativeNames = identifier.names.subList(1, identifier.names.size());
      String nativeRef = String.join(".", nativeNames);
      var pos = identifier.getParserPosition();
      if (pos != null && pos.getLineNum() > 0) {
        replacements.add(new IdentifierReplacement(
            pos.getLineNum(),
            pos.getColumnNum(),
            pos.getEndLineNum(),
            pos.getEndColumnNum(),
            nativeRef
        ));
      }
    }

    String apply() {
      if (replacements.isEmpty()) {
        return originalSql;
      }
      replacements.sort(Comparator
          .comparingInt(IdentifierReplacement::endLine).reversed()
          .thenComparingInt(IdentifierReplacement::endColumn).reversed()
      );
      String[] lines = originalSql.split("\n", -1);
      for (IdentifierReplacement replacement : replacements) {
        int startLineIdx = replacement.startLine() - 1;
        int endLineIdx = replacement.endLine() - 1;
        if (startLineIdx < 0 || endLineIdx >= lines.length) {
          continue;
        }
        if (startLineIdx == endLineIdx) {
          String line = lines[startLineIdx];
          int startCol = replacement.startColumn() - 1;
          int endCol = replacement.endColumn();
          if (startCol >= 0 && endCol <= line.length()) {
            lines[startLineIdx] = line.substring(0, startCol) + replacement.replacement() + line.substring(endCol);
          }
        }
      }
      return String.join("\n", lines);
    }

    private record IdentifierReplacement(
        int startLine,
        int startColumn,
        int endLine,
        int endColumn,
        String replacement
    ) {
    }
  }
}
