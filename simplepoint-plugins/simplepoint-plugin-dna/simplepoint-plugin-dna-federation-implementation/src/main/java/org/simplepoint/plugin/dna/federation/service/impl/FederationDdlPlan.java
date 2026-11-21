package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.impl.FederationSqlStatementBoundary.unsupported;

import java.util.Comparator;
import java.util.List;

/**
 * Complete admission for a bounded native DDL grammar, with Calcite query/expression parsing. This
 * is a syntactic footprint, not proof of database-side dependency or trigger safety.
 */
final class FederationDdlPlan {
  enum Operation {
    CREATE,
    ALTER,
    DROP,
    TRUNCATE,
    RENAME,
    COMMENT
  }

  enum ObjectKind {
    TABLE,
    VIEW,
    INDEX
  }

  enum Role {
    TARGET,
    RENAME_TARGET,
    DEPENDENCY,
    REFERENCE_TARGET,
    INDEX_OWNER
  }

  record ObjectReference(
      ObjectKind kind,
      Role role,
      List<String> names,
      List<Boolean> quoted,
      int start,
      int prefixEnd) {
    ObjectReference {
      names = List.copyOf(names);
      quoted = List.copyOf(quoted);
    }
  }

  private final String sql;
  private final List<ObjectReference> objects;
  private final Operation operation;
  private final boolean temporary;
  private final boolean cascade;

  private FederationDdlPlan(String sql, FederationDdlParser parser) {
    this.sql = sql;
    this.objects = parser.parse();
    this.operation = parser.operation();
    this.temporary = parser.temporary();
    this.cascade = parser.cascade();
  }

  static FederationDdlPlan parse(String sql) {
    return new FederationDdlPlan(sql, new FederationDdlParser(sql));
  }

  Operation operation() {
    return operation;
  }

  String sourceSql() {
    return sql;
  }

  boolean temporary() {
    return temporary;
  }

  boolean cascade() {
    return cascade;
  }

  List<ObjectReference> objects() {
    return objects;
  }

  FederationSqlAnalysisUtils.TableReferenceSummary references() {
    return new FederationSqlAnalysisUtils.TableReferenceSummary(
        objects.stream().map(ObjectReference::names).distinct().toList());
  }

  String nativeSql(String sourceCode) {
    if (sourceCode == null || sourceCode.isBlank()) {
      throw unsupported();
    }
    var prefixes =
        objects.stream()
            .filter(
                object ->
                    object.names().size() > 1
                        && object.names().getFirst().equalsIgnoreCase(sourceCode))
            .sorted(Comparator.comparingInt(ObjectReference::start).reversed())
            .toList();
    var result = new StringBuilder(sql);
    int boundary = sql.length();
    for (var object : prefixes) {
      if (object.prefixEnd() <= object.start() || object.prefixEnd() > boundary) {
        throw unsupported();
      }
      result.delete(object.start(), object.prefixEnd());
      boundary = object.start();
    }
    return result.toString();
  }
}
