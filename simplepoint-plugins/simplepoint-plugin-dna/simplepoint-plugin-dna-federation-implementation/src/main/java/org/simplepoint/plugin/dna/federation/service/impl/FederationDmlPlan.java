package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.impl.FederationSqlStatementBoundary.unsupported;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.sql.SqlDelete;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlMerge;
import org.apache.calcite.sql.SqlUpdate;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;

/** One parsed statement supplies both its complete table footprint and native prefix rewriting. */
final class FederationDmlPlan {
  enum Role {
    TARGET,
    READ
  }

  record ObjectReference(
      Role role, List<String> names, List<Boolean> quoted, int start, int prefixEnd) {
    ObjectReference {
      names = List.copyOf(names);
      quoted = List.copyOf(quoted);
    }
  }

  private final String sql;
  private final boolean calcite;
  private final List<SqlIdentifier> tables;
  private final List<ObjectReference> objects;

  private FederationDmlPlan(
      String sql,
      boolean calcite,
      List<FederationSqlTableCollector.TableUse> uses,
      FederationSqlIdentifierSource source) {
    this.sql = sql;
    this.calcite = calcite;
    this.tables = uses.stream().map(FederationSqlTableCollector.TableUse::identifier).toList();
    this.objects =
        uses.stream()
            .map(
                use -> {
                  var reference = source.capture(use.identifier());
                  return new ObjectReference(
                      use.write() ? Role.TARGET : Role.READ,
                      reference.names(),
                      reference.quoted(),
                      reference.start(),
                      reference.prefixEnd());
                })
            .distinct()
            .toList();
  }

  static FederationDmlPlan parse(String sql) {
    FederationSqlStatementBoundary.requireSingle(sql);
    try {
      var statements = SqlParser.create(sql).parseStmtList();
      if (statements.size() != 1) {
        throw unsupported();
      }
      var node = statements.getFirst();
      if (!(node instanceof SqlInsert
          || node instanceof SqlUpdate
          || node instanceof SqlDelete
          || node instanceof SqlMerge)) {
        throw unsupported();
      }
      FederationWriteExpressionPolicy.verify(node);
      var source = new FederationSqlIdentifierSource(sql);
      var collector = new FederationSqlTableCollector(false, source);
      collector.walk(node, Set.of(), 0);
      if (collector.tables().isEmpty()) {
        throw unsupported();
      }
      return new FederationDmlPlan(
          sql,
          // JdbcTable has no native default/generated-column contract: relational INSERT fills
          // omitted columns with NULL. Choose the admitted native lane before planning; this is
          // never recovery from a planner error. Calcite still parses the complete statement.
          !(node instanceof SqlMerge || node instanceof SqlInsert),
          collector.uses(),
          source);
    } catch (SqlParseException ignored) {
      // Parser messages contain user SQL. A rejection is never permission for native fallback.
      throw unsupported();
    }
  }

  boolean calcite() {
    return calcite;
  }

  String sourceSql() {
    return sql;
  }

  /**
   * Physical admission consumes original spelling and access roles, not routing-normalized names.
   */
  List<ObjectReference> objects() {
    return objects;
  }

  FederationSqlAnalysisUtils.TableReferenceSummary references() {
    return new FederationSqlAnalysisUtils.TableReferenceSummary(
        tables.stream().map(identifier -> List.copyOf(identifier.names)).distinct().toList());
  }

  /**
   * Calcite's JDBC default schema is one root name, not a dotted path. Qualify only collected
   * physical table references; CTE names, aliases, columns and SQL text values remain unchanged.
   */
  String calciteSql(String defaultSchema) {
    if (!calcite) {
      throw unsupported();
    }
    return FederationSqlAnalysisUtils.normalizeQualifiedIdentifiers(qualifiedSql(defaultSchema));
  }

  /** Qualifies original unqualified tables for both execution lanes from the selected directory. */
  String qualifiedSql(String defaultSchema) {
    if (defaultSchema == null || defaultSchema.isBlank() || defaultSchema.length() > 1024) {
      throw unsupported();
    }
    try {
      var parsed =
          SqlParser.create(defaultSchema, SqlParser.config().withUnquotedCasing(Casing.UNCHANGED))
              .parseExpression();
      if (!(parsed instanceof SqlIdentifier identifier)
          || identifier.isStar()
          || identifier.names.isEmpty()
          || identifier.names.size() > 3) {
        throw unsupported();
      }
      String prefix =
          String.join(
                  ".",
                  identifier.names.stream()
                      .map(name -> "\"" + name.replace("\"", "\"\"") + "\"")
                      .toList())
              + ".";
      var result = new StringBuilder(sql);
      objects.stream()
          .filter(object -> object.names().size() == 1)
          .map(ObjectReference::start)
          .distinct()
          .sorted(Comparator.reverseOrder())
          .forEach(offset -> result.insert(offset, prefix));
      return result.toString();
    } catch (SqlParseException ignored) {
      throw unsupported();
    }
  }

  String nativeSql(String sourceCode) {
    if (sourceCode == null || sourceCode.isBlank()) {
      throw unsupported();
    }
    Map<Integer, Integer> prefixes = new LinkedHashMap<>();
    for (var table : objects) {
      if (table.names().size() < 2 || !table.names().getFirst().equalsIgnoreCase(sourceCode)) {
        continue;
      }
      int start = table.start();
      int end = table.prefixEnd();
      if (end <= start || end >= sql.length()) {
        throw unsupported();
      }
      Integer previous = prefixes.putIfAbsent(start, end);
      if (previous != null && previous != end) {
        throw unsupported();
      }
    }
    var ordered =
        prefixes.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.reverseOrder()))
            .toList();
    var result = new StringBuilder(sql);
    int boundary = sql.length();
    for (var prefix : ordered) {
      if (prefix.getValue() > boundary) {
        throw unsupported();
      }
      result.delete(prefix.getKey(), prefix.getValue());
      boundary = prefix.getKey();
    }
    return result.toString();
  }

  String nativeSql(String sourceCode, String identifierQuote) {
    if (!"\"".equals(identifierQuote) && !"`".equals(identifierQuote)) {
      throw unsupported();
    }
    String nativeText = nativeSql(sourceCode);
    var tokens = FederationSqlTokens.read(nativeText);
    var rendered = new StringBuilder(nativeText);
    for (var token : tokens.reversed()) {
      if (token.kind() == FederationSqlTokens.Kind.IDENTIFIER) {
        rendered.replace(
            token.start(),
            token.end(),
            identifierQuote
                + token.value().replace(identifierQuote, identifierQuote + identifierQuote)
                + identifierQuote);
      }
    }
    return rendered.toString();
  }
}
