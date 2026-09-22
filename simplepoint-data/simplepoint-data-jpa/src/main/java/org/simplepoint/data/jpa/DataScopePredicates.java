package org.simplepoint.data.jpa;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.Metamodel;
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;

/** Compiles a trusted, resolved data rule into a JPA predicate for rows and counts. */
public final class DataScopePredicates {
  private DataScopePredicates() {}

  /** Returns {@code null} only for unrestricted access; unsupported rules deny every row. */
  public static Predicate compile(DataScopeCondition scope, Root<?> root, CriteriaBuilder cb,
      Metamodel metamodel) {
    if (scope == null || "ALL".equals(scope.getScopeType())) return null;
    Set<String> attributes = metamodel.entity(root.getJavaType()).getAttributes().stream()
        .map(attribute -> attribute.getName()).collect(Collectors.toSet());
    Predicate self = self(scope, root, cb, attributes);
    return switch (scope.getScopeType() == null ? "" : scope.getScopeType()) {
      case "SELF" -> self == null ? cb.disjunction() : self;
      case "DEPT", "DEPT_AND_BELOW", "CUSTOM" -> {
        var allowed = new ArrayList<Predicate>();
        if (scope.getDeptField() != null && attributes.contains(scope.getDeptField())
            && scope.getDeptIds() != null && !scope.getDeptIds().isEmpty()) {
          allowed.add(root.get(scope.getDeptField()).in(scope.getDeptIds()));
        }
        if (scope.isIncludeSelf() && self != null) allowed.add(self);
        yield allowed.isEmpty() ? cb.disjunction() : cb.or(allowed.toArray(Predicate[]::new));
      }
      default -> cb.disjunction();
    };
  }

  private static Predicate self(DataScopeCondition scope, Root<?> root, CriteriaBuilder cb,
      Set<String> attributes) {
    if (scope.getOwnerField() == null || scope.getUserId() == null
        || !attributes.contains(scope.getOwnerField())) return null;
    return cb.equal(root.get(scope.getOwnerField()), scope.getUserId());
  }
}
