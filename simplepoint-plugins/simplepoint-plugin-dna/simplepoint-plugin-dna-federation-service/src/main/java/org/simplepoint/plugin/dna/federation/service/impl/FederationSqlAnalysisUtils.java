package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.trimToNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDelete;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlMerge;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlUpdate;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.SqlWithItem;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.simplepoint.data.calcite.core.query.CalciteQueryAnalysis;
import org.simplepoint.plugin.dna.federation.service.support.FederationSqlIdentifierNormalizer;

/**
 * Static analysis utilities for federation SQL execution.
 */
final class FederationSqlAnalysisUtils {

  private static final Pattern JDBC_TABLE_SCAN_PATTERN =
      Pattern.compile("JdbcTableScan\\(table=\\[\\[([^\\]]+)]]");

  private FederationSqlAnalysisUtils() {
  }

  static TableReferenceSummary collectTableReferences(final String sql) {
    String normalizedSql = trimToNull(sql);
    if (normalizedSql == null) {
      return TableReferenceSummary.empty();
    }
    try {
      SqlParser parser = SqlParser.create(normalizedSql);
      SqlNodeList statements = parser.parseStmtList();
      if (statements.size() != 1 || statements.get(0) == null) {
        return TableReferenceSummary.empty();
      }
      TableReferenceCollector collector = new TableReferenceCollector();
      collector.collectQuery(statements.get(0));
      return collector.summary();
    } catch (SqlParseException ex) {
      return TableReferenceSummary.empty();
    }
  }

  static List<String> extractUsedDataSources(
      final String planText,
      final List<String> candidateCodes
  ) {
    if (planText == null || planText.isBlank() || candidateCodes == null || candidateCodes.isEmpty()) {
      return List.of();
    }
    LinkedHashMap<String, String> candidatesByLowerCase = new LinkedHashMap<>();
    candidateCodes.forEach(code -> {
      String normalized = trimToNull(code);
      if (normalized != null) {
        candidatesByLowerCase.put(normalized.toLowerCase(Locale.ROOT), normalized);
      }
    });
    LinkedHashSet<String> usedSources = new LinkedHashSet<>();
    Matcher matcher = JDBC_TABLE_SCAN_PATTERN.matcher(planText);
    while (matcher.find()) {
      String[] segments = matcher.group(1).split(",");
      for (String segment : segments) {
        String normalized = trimToNull(segment);
        if (normalized == null) {
          continue;
        }
        String candidate = candidatesByLowerCase.get(normalized.toLowerCase(Locale.ROOT));
        if (candidate != null) {
          usedSources.add(candidate);
        }
      }
    }
    return List.copyOf(usedSources);
  }

  static List<String> resolveResponseDataSources(
      final String planText,
      final List<String> physicalDataSourceCodes,
      final List<String> fallbackDataSources
  ) {
    List<String> detected = extractUsedDataSources(planText, physicalDataSourceCodes);
    return detected.isEmpty() ? fallbackDataSources : detected;
  }

  static String normalizeQualifiedIdentifiers(final String sql) {
    return FederationSqlIdentifierNormalizer.normalize(sql);
  }

  static String buildPushdownSummary(
      final CalciteQueryAnalysis analysis,
      final List<String> dataSources
  ) {
    List<String> sections = new ArrayList<>();
    sections.add("命中数据源: " + (dataSources == null || dataSources.isEmpty() ? "未识别" : String.join(", ", dataSources)));
    if (analysis.pushedDownOperators().isEmpty()) {
      sections.add("未从执行计划中识别出明确的 JDBC 下推算子");
    } else {
      sections.add("检测到已下推算子: " + String.join(", ", analysis.pushedDownOperators()));
    }
    if (dataSources != null && dataSources.size() > 1) {
      sections.add(analysis.platformJoin()
          ? "检测到跨数据源 Join 保留在平台层执行"
          : "检测到跨数据源访问");
    } else if (analysis.pushedDownOperators().contains("Join")) {
      sections.add("检测到单数据源 Join 已下推到源库");
    }
    return String.join("；", sections);
  }

  record TableReferenceSummary(List<List<String>> identifiers) {

    static TableReferenceSummary empty() {
      return new TableReferenceSummary(List.of());
    }

    boolean hasTableReferences() {
      return identifiers != null && !identifiers.isEmpty();
    }
  }

  static final class TableReferenceCollector {

    private final List<List<String>> identifiers = new ArrayList<>();

    private final LinkedHashSet<String> commonTableExpressionNames = new LinkedHashSet<>();

    void collectQuery(final SqlNode node) {
      if (node == null) {
        return;
      }
      if (node instanceof SqlWith with) {
        for (SqlNode item : with.withList) {
          if (item instanceof SqlWithItem withItem) {
            String cteName = trimToNull(withItem.name == null ? null : withItem.name.getSimple());
            if (cteName != null) {
              commonTableExpressionNames.add(cteName.toLowerCase(Locale.ROOT));
            }
            collectQuery(withItem.query);
          }
        }
        collectQuery(with.body);
        return;
      }
      if (node instanceof SqlOrderBy orderBy) {
        collectQuery(orderBy.query);
        collectNestedQueries(orderBy.orderList);
        return;
      }
      if (node instanceof SqlInsert insert) {
        collectFrom(insert.getTargetTable());
        collectQuery(insert.getSource());
        return;
      }
      if (node instanceof SqlUpdate update) {
        collectFrom(update.getTargetTable());
        collectNestedQueries(update.getSourceExpressionList());
        collectNestedQueries(update.getCondition());
        return;
      }
      if (node instanceof SqlDelete delete) {
        collectFrom(delete.getTargetTable());
        collectNestedQueries(delete.getCondition());
        return;
      }
      if (node instanceof SqlMerge merge) {
        collectFrom(merge.getTargetTable());
        collectQuery(merge.getSourceTableRef());
        collectNestedQueries(merge.getCondition());
        if (merge.getUpdateCall() != null) {
          collectQuery(merge.getUpdateCall());
        }
        if (merge.getInsertCall() != null) {
          collectQuery(merge.getInsertCall());
        }
        return;
      }
      if (node instanceof SqlSelect select) {
        collectFrom(select.getFrom());
        collectNestedQueries(select.getSelectList());
        collectNestedQueries(select.getWhere());
        collectNestedQueries(select.getHaving());
        collectNestedQueries(select.getGroup());
        collectNestedQueries(select.getOrderList());
        return;
      }
      if (node instanceof SqlCall call) {
        collectNestedQueries(call);
      }
    }

    private void collectFrom(final SqlNode node) {
      if (node == null) {
        return;
      }
      if (node instanceof SqlIdentifier identifier) {
        addIdentifier(identifier);
        return;
      }
      if (node instanceof SqlJoin join) {
        collectFrom(join.getLeft());
        collectFrom(join.getRight());
        collectNestedQueries(join.getCondition());
        return;
      }
      if (node instanceof SqlSelect || node instanceof SqlWith || node instanceof SqlOrderBy) {
        collectQuery(node);
        return;
      }
      if (node instanceof SqlNodeList nodeList) {
        nodeList.forEach(this::collectFrom);
        return;
      }
      if (node instanceof SqlBasicCall call) {
        if (SqlKind.AS.equals(call.getKind()) && !call.getOperandList().isEmpty()) {
          collectFrom(call.getOperandList().get(0));
          return;
        }
        collectNestedQueries(call);
        return;
      }
      if (node instanceof SqlCall call) {
        collectNestedQueries(call);
      }
    }

    private void collectNestedQueries(final SqlNode node) {
      if (node == null) {
        return;
      }
      if (node instanceof SqlSelect || node instanceof SqlWith || node instanceof SqlOrderBy) {
        collectQuery(node);
        return;
      }
      if (node instanceof SqlNodeList nodeList) {
        nodeList.forEach(this::collectNestedQueries);
        return;
      }
      if (node instanceof SqlCall call) {
        for (SqlNode operand : call.getOperandList()) {
          collectNestedQueries(operand);
        }
      }
    }

    private void addIdentifier(final SqlIdentifier identifier) {
      if (identifier == null || identifier.names == null || identifier.names.isEmpty()) {
        return;
      }
      List<String> names = identifier.names.stream()
          .map(name -> trimToNull(name))
          .filter(Objects::nonNull)
          .toList();
      if (names.isEmpty()) {
        return;
      }
      if (names.size() == 1 && commonTableExpressionNames.contains(names.get(0).toLowerCase(Locale.ROOT))) {
        return;
      }
      identifiers.add(names);
    }

    TableReferenceSummary summary() {
      return new TableReferenceSummary(List.copyOf(identifiers));
    }
  }
}
