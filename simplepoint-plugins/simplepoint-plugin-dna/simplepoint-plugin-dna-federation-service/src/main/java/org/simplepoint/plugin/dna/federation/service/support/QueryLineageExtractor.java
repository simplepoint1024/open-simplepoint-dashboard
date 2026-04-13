package org.simplepoint.plugin.dna.federation.service.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts table-level lineage information from a Calcite EXPLAIN PLAN text.
 *
 * <p>The plan text uses the format
 * {@code JdbcTableScan(table=[[datasource, schema, table]])} where each
 * segment maps to a physical object in the federation catalog hierarchy.
 */
public final class QueryLineageExtractor {

  private static final Pattern JDBC_TABLE_SCAN_PATTERN =
      Pattern.compile("JdbcTableScan\\(table=\\[\\[([^\\]]+)]]");

  private QueryLineageExtractor() {
  }

  /**
   * A resolved table reference from the Calcite plan.
   *
   * @param datasourceCode the federation datasource code
   * @param schemaName     the database schema (may be {@code null})
   * @param tableName      the table or view name
   */
  public record TableReference(
      String datasourceCode,
      String schemaName,
      String tableName
  ) {
  }

  /**
   * Extracts all source {@link TableReference}s from a Calcite EXPLAIN PLAN
   * text. Each {@code JdbcTableScan} node contributes one reference.
   *
   * @param planText EXPLAIN PLAN output
   * @return unique table references preserving discovery order
   */
  public static List<TableReference> extractSourceTables(final String planText) {
    if (planText == null || planText.isBlank()) {
      return List.of();
    }
    LinkedHashSet<TableReference> seen = new LinkedHashSet<>();
    Matcher matcher = JDBC_TABLE_SCAN_PATTERN.matcher(planText);
    while (matcher.find()) {
      String[] segments = matcher.group(1).split(",");
      List<String> trimmed = new ArrayList<>(segments.length);
      for (String s : segments) {
        String t = s.strip();
        if (!t.isEmpty()) {
          trimmed.add(t);
        }
      }
      if (trimmed.size() >= 2) {
        String dsCode = trimmed.get(0);
        String schema = trimmed.size() >= 3 ? trimmed.get(trimmed.size() - 2) : null;
        String table = trimmed.get(trimmed.size() - 1);
        seen.add(new TableReference(dsCode, schema, table));
      }
    }
    return List.copyOf(seen);
  }
}
