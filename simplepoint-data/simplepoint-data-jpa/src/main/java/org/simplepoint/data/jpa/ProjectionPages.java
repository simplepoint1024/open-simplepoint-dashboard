package org.simplepoint.data.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.simplepoint.core.query.QueryCapabilities;
import org.simplepoint.core.query.DefaultTableOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Database pages for scalar read models. Authorization is mandatory and separate from filters. */
public final class ProjectionPages {
  private ProjectionPages() {}

  /** Mandatory domain boundary applied to both data and count queries. */
  @FunctionalInterface
  public interface Boundary<R> {
    /** Builds the caller-independent ownership or visibility predicate. */
    Predicate apply(CriteriaBuilder cb, CommonAbstractCriteria query, Root<R> root);
  }

  /** Applies declared filters and stable sorting before counting and pagination. */
  public static <R, T> Page<T> query(
      EntityManager em,
      Class<R> rowType,
      Class<?> outputType,
      Map<String, String> filters,
      Pageable requested,
      String defaultOrder,
      Set<String> ignoreCase,
      Boundary<R> boundary,
      Function<R, T> project) {
    Objects.requireNonNull(boundary, "A domain authorization boundary is required");
    Pageable page = requested;
    var defaultSort = DefaultTableOrder.forModel(rowType, name ->
        DefaultTableOrder.exists(rowType, name) && QueryCapabilities.readable(outputType, name));
    if (defaultSort.isUnsorted() && DefaultTableOrder.exists(rowType, defaultOrder)
        && QueryCapabilities.readable(outputType, defaultOrder)) defaultSort = Sort.by(Sort.Direction.DESC, defaultOrder);
    var compiled = ProjectionQuery.prepareIgnoringCase(outputType, filters, page, ignoreCase);
    var cb = em.getCriteriaBuilder();
    var query = cb.createQuery(rowType);
    var root = query.from(rowType);
    var orders = compiled.orders(cb, fields(outputType, root), root.get("id"));
    if (requested.getSort().isUnsorted() && defaultSort.isSorted()) {
      var order = defaultSort.iterator().next();
      var value = root.get(order.getProperty());
      orders.addFirst(order.isAscending() ? cb.asc(value) : cb.desc(value));
      orders.addFirst(cb.asc(cb.selectCase().when(cb.isNull(value), 1).otherwise(0)));
    }
    query
        .select(root)
        .where(
            cb.and(
                Objects.requireNonNull(boundary.apply(cb, query, root)),
                compiled.predicate(cb, fields(outputType, root))))
        .orderBy(orders);
    var rows =
        em.createQuery(query)
            .setFirstResult((int) page.getOffset())
            .setMaxResults(page.getPageSize())
            .getResultList();
    var count = cb.createQuery(Long.class);
    var countRoot = count.from(rowType);
    count
        .select(cb.count(countRoot))
        .where(
            cb.and(
                Objects.requireNonNull(boundary.apply(cb, count, countRoot)),
                compiled.predicate(cb, fields(outputType, countRoot))));
    return new PageImpl<>(
        rows.stream().map(project).toList(), page, em.createQuery(count).getSingleResult());
  }

  private static Map<String, Expression<?>> fields(Class<?> type, Root<?> root) {
    Map<String, Expression<?>> result = new LinkedHashMap<>();
    for (var field : QueryCapabilities.declaration(type).value()) {
      result.put(field.value(), root.get(field.value()));
    }
    return result;
  }
}
