package org.simplepoint.plugin.dna.federation.service.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.util.SqlShuttle;

/**
 * Normalizes qualified identifiers in SQL by quoting each component for Calcite.
 *
 * <p>Calcite uppercases unquoted identifiers by default. This shuttle rewrites
 * multi-part identifiers (e.g. {@code datasource.schema.table}) into their
 * double-quoted form ({@code "datasource"."schema"."table"}) so that Calcite
 * matches them case-sensitively against the registered federation schemas.</p>
 */
public final class FederationSqlIdentifierNormalizer extends SqlShuttle {

  private final String sql;

  private final int[] lineStartOffsets;

  private final List<IdentifierReplacement> replacements = new ArrayList<>();

  private FederationSqlIdentifierNormalizer(final String sql) {
    this.sql = sql;
    this.lineStartOffsets = resolveLineStartOffsets(sql);
  }

  /**
   * Normalizes qualified identifiers in the given SQL string.
   *
   * @param sql the SQL string
   * @return the SQL with qualified identifiers properly quoted, or the original SQL if parsing fails
   */
  public static String normalize(final String sql) {
    String normalized = trimToNull(sql);
    if (normalized == null) {
      return null;
    }
    try {
      SqlParser parser = SqlParser.create(normalized);
      SqlNodeList statements = parser.parseStmtList();
      if (statements.size() != 1 || statements.get(0) == null) {
        return normalized;
      }
      FederationSqlIdentifierNormalizer normalizer = new FederationSqlIdentifierNormalizer(normalized);
      statements.get(0).accept(normalizer);
      return normalizer.apply();
    } catch (SqlParseException ex) {
      return normalized;
    }
  }

  private String apply() {
    if (replacements.isEmpty()) {
      return sql;
    }
    StringBuilder builder = new StringBuilder(sql);
    replacements.stream()
        .sorted(Comparator.comparingInt(IdentifierReplacement::startOffset).reversed())
        .forEach(replacement -> builder.replace(
            replacement.startOffset(),
            replacement.endOffsetExclusive(),
            replacement.value()
        ));
    return builder.toString();
  }

  @Override
  public SqlNode visit(final SqlIdentifier identifier) {
    IdentifierReplacement replacement = buildReplacement(identifier);
    if (replacement != null) {
      replacements.add(replacement);
    }
    return identifier;
  }

  private IdentifierReplacement buildReplacement(final SqlIdentifier identifier) {
    if (!needsNormalization(identifier)) {
      return null;
    }
    List<String> normalizedComponents = new ArrayList<>();
    IdentifierComponentSource firstComponent = null;
    IdentifierComponentSource lastComponent = null;
    for (int index = 0; index < identifier.names.size(); index++) {
      IdentifierComponentSource componentSource = resolveComponentSource(identifier, index);
      if (componentSource == null) {
        return null;
      }
      if (firstComponent == null) {
        firstComponent = componentSource;
      }
      lastComponent = componentSource;
      if (isStarComponent(identifier.names.get(index))) {
        normalizedComponents.add("*");
        continue;
      }
      String componentText = unquoteIdentifier(componentSource.text());
      normalizedComponents.add(quoteIdentifier(componentText));
    }
    if (firstComponent == null || lastComponent == null) {
      return null;
    }
    String replacement = String.join(".", normalizedComponents);
    String original = sql.substring(firstComponent.startOffset(), lastComponent.endOffsetExclusive());
    return original.equals(replacement)
        ? null
        : new IdentifierReplacement(firstComponent.startOffset(), lastComponent.endOffsetExclusive(), replacement);
  }

  private IdentifierComponentSource resolveComponentSource(final SqlIdentifier identifier, final int index) {
    SqlParserPos position = componentPosition(identifier, index);
    int startOffset = toOffset(position.getLineNum(), position.getColumnNum());
    int endOffset = toOffset(position.getEndLineNum(), position.getEndColumnNum());
    for (int candidateEnd : List.of(Math.min(sql.length(), endOffset), Math.min(sql.length(), endOffset + 1))) {
      if (candidateEnd <= startOffset || candidateEnd > sql.length()) {
        continue;
      }
      String candidate = sql.substring(startOffset, candidateEnd);
      if (matchesParsedComponent(candidate, identifier, index)) {
        return new IdentifierComponentSource(startOffset, candidateEnd, candidate);
      }
    }
    return null;
  }

  private boolean matchesParsedComponent(
      final String candidate,
      final SqlIdentifier identifier,
      final int index
  ) {
    if (candidate == null || candidate.isEmpty()) {
      return false;
    }
    if (isStarComponent(identifier.names.get(index))) {
      return "*".equals(candidate);
    }
    String unquoted = unquoteIdentifier(candidate);
    String parsed = identifier.names.get(index);
    return parsed != null && unquoted.equalsIgnoreCase(parsed);
  }

  private int toOffset(final int lineNumber, final int columnNumber) {
    int lineIndex = Math.max(0, Math.min(lineStartOffsets.length - 1, lineNumber - 1));
    return Math.min(sql.length(), lineStartOffsets[lineIndex] + Math.max(0, columnNumber - 1));
  }

  // ── static helpers ──

  static boolean needsNormalization(final SqlIdentifier identifier) {
    if (identifier == null || identifier.names == null || identifier.names.isEmpty()) {
      return false;
    }
    int qualifiedDepth = 0;
    boolean hasUnquotedComponent = false;
    for (int index = 0; index < identifier.names.size(); index++) {
      String component = identifier.names.get(index);
      if (isStarComponent(component)) {
        continue;
      }
      qualifiedDepth++;
      if (!identifier.isComponentQuoted(index)) {
        hasUnquotedComponent = true;
      }
    }
    return qualifiedDepth >= 2 && hasUnquotedComponent;
  }

  /**
   * Unquotes an identifier component by stripping dialect-specific delimiters.
   * Handles double-quote ({@code "}), square-bracket ({@code []}), and backtick ({@code `}).
   */
  static String unquoteIdentifier(final String component) {
    if (component == null || component.length() < 2) {
      return component;
    }
    if (component.startsWith("\"") && component.endsWith("\"")) {
      return component.substring(1, component.length() - 1).replace("\"\"", "\"");
    }
    if (component.startsWith("[") && component.endsWith("]")) {
      return component.substring(1, component.length() - 1).replace("]]", "]");
    }
    if (component.startsWith("`") && component.endsWith("`")) {
      return component.substring(1, component.length() - 1).replace("``", "`");
    }
    return component;
  }

  /**
   * Quotes an identifier component using Calcite-standard double-quote style.
   */
  static String quoteIdentifier(final String component) {
    return "\"" + component.replace("\"", "\"\"") + "\"";
  }

  private static boolean isStarComponent(final String component) {
    return component == null || component.isEmpty() || "*".equals(component);
  }

  private static SqlParserPos componentPosition(final SqlIdentifier identifier, final int index) {
    SqlParserPos position = identifier.getComponentParserPosition(index);
    return position == null ? identifier.getParserPosition() : position;
  }

  private static int[] resolveLineStartOffsets(final String sql) {
    List<Integer> starts = new ArrayList<>();
    starts.add(0);
    for (int index = 0; index < sql.length(); index++) {
      if (sql.charAt(index) == '\n') {
        starts.add(index + 1);
      }
    }
    return starts.stream().mapToInt(Integer::intValue).toArray();
  }

  private static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private record IdentifierReplacement(
      int startOffset,
      int endOffsetExclusive,
      String value
  ) {
  }

  private record IdentifierComponentSource(
      int startOffset,
      int endOffsetExclusive,
      String text
  ) {
  }
}
