package org.simplepoint.core.query;

import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Pageable;

/** Validates a narrower read projection before a query is delegated to its owning entity service. */
public final class ProjectionQueries {
  private ProjectionQueries() {}

  /** Public filters cannot query credentials or other entity fields absent from the projection. */
  public static void validate(Class<?> projection, Map<String, String> filters, Pageable pageable) {
    QueryDocument.validateBudget(filters);
    if (pageable.isUnpaged() || pageable.getPageSize() > QueryCapabilities.declaration(projection).maxPageSize()
        || pageable.getOffset() > Integer.MAX_VALUE || pageable.getSort().stream().count() > 3) {
      throw new InvalidQueryException("分页范围超限");
    }
    pageable.getSort().forEach(order -> QueryCapabilities.require(projection, order.getProperty(), null, true));
    filters.forEach((field, value) -> {
      if (Set.of("page", "size", "sort").contains(field)) return;
      if (QueryDocument.PARAMETER.equals(field) || value != null && value.startsWith("{")) {
        var document = QueryDocument.parse(value);
        if (!QueryDocument.PARAMETER.equals(field) && !onlyField(document.where(), field)) {
          throw new InvalidQueryException("表头查询必须仅包含当前字段");
        }
        validateNode(projection, document.where());
      } else {
        var condition = FilterCondition.parse(value);
        QueryCapabilities.require(projection, field, condition.operator(), false);
      }
    });
  }

  private static boolean onlyField(QueryDocument.Node node, String field) {
    return Set.of("and", "or", "not").contains(node.op())
        ? node.children().stream().allMatch(child -> onlyField(child, field))
        : field.equals(node.field()) && !"exists".equals(node.op());
  }

  private static void validateNode(Class<?> projection, QueryDocument.Node node) {
    if (Set.of("and", "or", "not").contains(node.op())) {
      node.children().forEach(child -> validateNode(projection, child));
    } else {
      // Projection adapters intentionally do not inherit arbitrary entity relationships.
      QueryCapabilities.require(projection, node.field(), node.op(), false);
    }
  }
}
