package org.simplepoint.data.jpa;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.simplepoint.core.query.FilterCondition;
import org.springframework.data.jpa.repository.query.EscapeCharacter;

/** Thread-safe operator registry retaining existing extension and factory APIs. */
public class AttributeMatchers {
  private static final Map<String, AttributeMatcher> MATCHERS = new ConcurrentHashMap<>();

  static {
    FilterCondition.OPERATORS.forEach(op -> registerAttributeMatcher(op, typed(op)));
  }

  /** Platform operator registry or typed predicate factory. */
  public static AttributeMatcher getAttributeMatcher(String operator) {
    return MATCHERS.get(operator);
  }

  /** Platform operator registry or typed predicate factory. */
  public static void registerAttributeMatcher(String operator, AttributeMatcher matcher) {
    MATCHERS.put(operator, matcher);
  }

  /** Platform operator registry or typed predicate factory. */
  public static void unregisterAttributeMatcher(String operator) {
    MATCHERS.remove(operator);
  }

  /** Platform operator registry or typed predicate factory. */
  public static Set<String> operators() {
    return Set.copyOf(MATCHERS.keySet());
  }

  private static AttributeMatcher typed(String op) {
    return new AttributeMatcher() {
      @Override
      public <S> void match(
          String name,
          String value,
          Root<S> root,
          CriteriaQuery<?> query,
          CriteriaBuilder cb,
          EscapeCharacter escape,
          List<Predicate> predicates) {
        predicates.add(
            QueryPredicates.build(cb, root.get(name), new FilterCondition(op, value), escape, 100));
      }
    };
  }

  /** Platform operator registry or typed predicate factory. */
  public static AttributeMatcher like() {
    return typed("like");
  }

  /** Platform operator registry or typed predicate factory. */
  public static AttributeMatcher notLike() {
    return typed("not:like");
  }

  /** Platform operator registry or typed predicate factory. */
  public static AttributeMatcher equals() {
    return typed("equals");
  }

  /** Platform operator registry or typed predicate factory. */
  public static AttributeMatcher notEquals() {
    return typed("not:equals");
  }

  /** Platform operator registry or typed predicate factory. */
  public static AttributeMatcher in() {
    return typed("in");
  }

  /** Platform operator registry or typed predicate factory. */
  public static AttributeMatcher notIn() {
    return typed("not:in");
  }

  /** Platform operator registry or typed predicate factory. */
  public static AttributeMatcher between() {
    return typed("between");
  }

  /** Platform operator registry or typed predicate factory. */
  public static AttributeMatcher notBetween() {
    return typed("not:between");
  }

  /** Platform operator registry or typed predicate factory. */
  public static AttributeMatcher greaterThan() {
    return typed("than:greater");
  }

  /** Platform operator registry or typed predicate factory. */
  public static AttributeMatcher lessThan() {
    return typed("than:less");
  }

  /** Platform operator registry or typed predicate factory. */
  public static AttributeMatcher greaterThanOrEqualTo() {
    return typed("than:equal:greater");
  }

  /** Platform operator registry or typed predicate factory. */
  public static AttributeMatcher lessThanOrEqualTo() {
    return typed("than:equal:less");
  }

  /** Platform operator registry or typed predicate factory. */
  public static AttributeMatcher isNull() {
    return typed("is:null");
  }

  /** Platform operator registry or typed predicate factory. */
  public static AttributeMatcher isNotNull() {
    return typed("is:not:null");
  }
}
