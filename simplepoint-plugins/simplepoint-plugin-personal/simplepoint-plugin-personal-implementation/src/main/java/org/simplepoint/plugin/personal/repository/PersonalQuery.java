package org.simplepoint.plugin.personal.repository;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.simplepoint.core.query.FilterCondition;
import org.simplepoint.core.query.QueryCapabilities;
import org.simplepoint.core.query.QueryDocument;
import org.simplepoint.data.jpa.QueryPredicates;
import org.simplepoint.data.jpa.StructuredQuery;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.query.EscapeCharacter;

/**
 * Typed, allowlisted translation of platform column filters; never replaces ownership predicates.
 */
final class PersonalQuery {
  private PersonalQuery() {}

  static Predicate filters(CriteriaBuilder cb, Root<?> root, Map<String, String> filters) {
    QueryDocument.validateBudget(filters);
    if (filters.size() > 16) {
      throw new IllegalArgumentException("表头筛选条件过多");
    }
    List<Predicate> predicates = new ArrayList<>();
    filters.forEach(
        (name, value) -> {
          if (QueryDocument.PARAMETER.equals(name)) {
            predicates.add(
                StructuredQuery.compile(cb, null, root, QueryDocument.parse(value).where()));
            return;
          }
          if (value != null && value.startsWith("{")) {
            var node = QueryDocument.parse(value).where();
            if (!name.equals(node.field()) || !node.children().isEmpty()) {
              throw new IllegalArgumentException("无效的列筛选");
            }
            predicates.add(StructuredQuery.compile(cb, null, root, node));
            return;
          }
          QueryCapabilities.require(
              root.getJavaType(), name, FilterCondition.parse(value).operator(), false);
          if (value == null || value.length() > 2000) {
            throw new IllegalArgumentException("无效的表头筛选条件");
          }
          if (!value.contains(":")) {
            throw new IllegalArgumentException("不支持的筛选操作符");
          }
          predicates.add(
              QueryPredicates.build(
                  cb, root.get(name), FilterCondition.parse(value), EscapeCharacter.DEFAULT, 20));
        });
    return cb.and(predicates.toArray(Predicate[]::new));
  }

  static List<Order> orders(CriteriaBuilder cb, Root<?> root, Sort sort) {
    List<Order> orders = new ArrayList<>();
    if (sort.stream().count() > 3) {
      throw new IllegalArgumentException("排序字段过多");
    }
    for (Sort.Order order : sort) {
      QueryCapabilities.require(root.getJavaType(), order.getProperty(), null, true);
      Path<?> path = root.get(order.getProperty());
      orders.add(cb.asc(cb.selectCase().when(cb.isNull(path), 1).otherwise(0)));
      orders.add(order.isAscending() ? cb.asc(path) : cb.desc(path));
    }
    orders.add(cb.asc(root.get("id")));
    return orders;
  }
}
