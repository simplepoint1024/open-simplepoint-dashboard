package org.simplepoint.data.jpa;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.jpa.repository.query.EscapeCharacter;

/**
 * A utility class for registering and retrieving AttributeMatchers.
 * This class manages a static map of matchers and provides methods
 * for registering, unregistering, and obtaining matchers by attribute name.
 */
public class AttributeMatchers {

  private static final Map<String, AttributeMatcher> attributeMatchers = new HashMap<>();

  static {
    registerAttributeMatcher("like", like());
    registerAttributeMatcher("in", in());
    registerAttributeMatcher("not:in", notIn());
    registerAttributeMatcher("not:like", notLike());
    registerAttributeMatcher("between", between());
    registerAttributeMatcher("not:between", notBetween());
    registerAttributeMatcher("equals", equals());
    registerAttributeMatcher("not:equals", notEquals());
    registerAttributeMatcher("than:greater", greaterThan());
    registerAttributeMatcher("than:less", lessThan());
    registerAttributeMatcher("than:equal:greater", greaterThanOrEqualTo());
    registerAttributeMatcher("than:equal:less", lessThanOrEqualTo());
    registerAttributeMatcher("is:null", isNull());
    registerAttributeMatcher("is:not:null", isNotNull());
  }

  /**
   * Retrieves an AttributeMatcher by attribute name.
   *
   * @param attributeName the name of the attribute
   * @return the AttributeMatcher corresponding to the attribute name
   */
  public static AttributeMatcher getAttributeMatcher(String attributeName) {
    return attributeMatchers.get(attributeName);
  }

  /**
   * Registers a new AttributeMatcher for a given attribute name.
   *
   * @param attributeName    the name of the attribute
   * @param attributeMatcher the AttributeMatcher to register
   */
  public static void registerAttributeMatcher(String attributeName,
                                              AttributeMatcher attributeMatcher) {
    attributeMatchers.put(attributeName, attributeMatcher);
  }

  /**
   * Unregisters an AttributeMatcher by attribute name.
   *
   * @param attributeName the name of the attribute
   */
  public static void unregisterAttributeMatcher(String attributeName) {
    attributeMatchers.remove(attributeName);
  }

  /**
   * Creates an AttributeMatcher for IN conditions.
   * This matcher generates predicates for attributes within a specified set of values.
   *
   * @return an AttributeMatcher for IN conditions
   */
  public static AttributeMatcher in() {
    return new AttributeMatcher() {
      @Override
      public <S> void match(String name, String value, Root<S> root, CriteriaQuery<?> query,
                            CriteriaBuilder criteriaBuilder, EscapeCharacter character,
                            List<Predicate> predicates) throws Exception {
        if (value != null && !value.trim().isEmpty()) {
          Object[] values = value.split(",");
          CriteriaBuilder.In<Object> inClause = criteriaBuilder.in(root.get(name));
          for (Object o : values) {
            inClause.value(o);
          }
          predicates.add(inClause);
        }
      }
    };
  }

  /**
   * Creates an AttributeMatcher for NOT IN conditions.
   *
   * @return an AttributeMatcher for NOT IN conditions
   */
  public static AttributeMatcher notIn() {
    return new AttributeMatcher() {
      @Override
      public <S> void match(String name, String value, Root<S> root, CriteriaQuery<?> query,
                            CriteriaBuilder criteriaBuilder, EscapeCharacter character,
                            List<Predicate> predicates) throws Exception {
        if (value != null && !value.trim().isEmpty()) {
          Object[] values = value.split(",");
          CriteriaBuilder.In<Object> inClause = criteriaBuilder.in(root.get(name));
          for (Object o : values) {
            inClause.value(o);
          }
          Predicate notInClause = criteriaBuilder.not(inClause);
          predicates.add(notInClause);
        }
      }
    };
  }

  /**
   * Creates an AttributeMatcher for LIKE conditions.
   *
   * @return an AttributeMatcher for LIKE conditions
   */
  public static AttributeMatcher like() {
    return new AttributeMatcher() {
      @Override
      public <S> void match(String name, String value, Root<S> root, CriteriaQuery<?> query,
                            CriteriaBuilder criteriaBuilder, EscapeCharacter character,
                            List<Predicate> predicates) throws Exception {
        if (value != null && !value.trim().isEmpty()) {
          predicates.add(
              criteriaBuilder.like(root.get(name), value, character.getEscapeCharacter()));
        }
      }
    };
  }

  /**
   * Creates an AttributeMatcher for NOT LIKE conditions.
   *
   * @return an AttributeMatcher for NOT LIKE conditions
   */
  public static AttributeMatcher notLike() {
    return new AttributeMatcher() {
      @Override
      public <S> void match(String name, String value, Root<S> root, CriteriaQuery<?> query,
                            CriteriaBuilder criteriaBuilder, EscapeCharacter character,
                            List<Predicate> predicates) throws Exception {
        if (value != null && !value.trim().isEmpty()) {
          Predicate notLikePredicate = criteriaBuilder.not(
              criteriaBuilder.like(root.get(name), value, character.getEscapeCharacter()));
          predicates.add(notLikePredicate);
        }
      }
    };
  }

  /**
   * Creates an AttributeMatcher for BETWEEN conditions.
   *
   * @return an AttributeMatcher for BETWEEN conditions
   */
  public static AttributeMatcher between() {
    return new AttributeMatcher() {
      @Override
      public <S> void match(String name, String value, Root<S> root, CriteriaQuery<?> query,
                            CriteriaBuilder criteriaBuilder, EscapeCharacter character,
                            List<Predicate> predicates) throws Exception {
        if (value != null && !value.trim().isEmpty()) {
          String[] values = value.split(",");
          if (values.length == 2) {
            String startValue = values[0].trim();
            String endValue = values[1].trim();
            Predicate betweenPredicate =
                criteriaBuilder.between(root.get(name), startValue, endValue);
            predicates.add(betweenPredicate);
          } else {
            throw new IllegalArgumentException(
                "Invalid value format for BETWEEN condition. Expected format: startValue,endValue");
          }
        }
      }
    };
  }

  /**
   * Creates an AttributeMatcher for NOT BETWEEN conditions.
   * This matcher generates predicates that exclude values within the specified range.
   *
   * @return an AttributeMatcher for NOT BETWEEN conditions
   */
  public static AttributeMatcher notBetween() {
    return new AttributeMatcher() {
      @Override
      public <S> void match(String name, String value, Root<S> root, CriteriaQuery<?> query,
                            CriteriaBuilder criteriaBuilder, EscapeCharacter character,
                            List<Predicate> predicates) throws Exception {
        if (value != null && !value.trim().isEmpty()) {
          String[] values = value.split(",");
          if (values.length == 2) {
            String startValue = values[0].trim();
            String endValue = values[1].trim();

            Predicate betweenPredicate =
                criteriaBuilder.between(root.get(name), startValue, endValue);
            Predicate notBetweenPredicate = criteriaBuilder.not(betweenPredicate);
            predicates.add(notBetweenPredicate);
          } else {
            throw new IllegalArgumentException(
                "Invalid value format for NOT BETWEEN condition."
                    + " Expected format: startValue,endValue"
            );
          }
        }
      }
    };
  }

  /**
   * Creates an AttributeMatcher for EQUAL conditions.
   * This matcher generates predicates for exact attribute value matches.
   *
   * @return an AttributeMatcher for EQUAL conditions
   */
  public static AttributeMatcher equals() {
    return new AttributeMatcher() {
      @Override
      public <S> void match(String name, String value, Root<S> root, CriteriaQuery<?> query,
                            CriteriaBuilder criteriaBuilder, EscapeCharacter character,
                            List<Predicate> predicates) throws Exception {
        if (value != null && !value.trim().isEmpty()) {
          Predicate equalsPredicate = criteriaBuilder.equal(root.get(name), value.trim());
          predicates.add(equalsPredicate);
        }
      }
    };
  }

  /**
   * Creates an AttributeMatcher for NOT EQUAL conditions.
   * This matcher generates predicates for attributes that do not match the specified value.
   *
   * @return an AttributeMatcher for NOT EQUAL conditions
   */
  public static AttributeMatcher notEquals() {
    return new AttributeMatcher() {
      @Override
      public <S> void match(String name, String value, Root<S> root, CriteriaQuery<?> query,
                            CriteriaBuilder criteriaBuilder, EscapeCharacter character,
                            List<Predicate> predicates) throws Exception {
        if (value != null && !value.trim().isEmpty()) {
          Predicate notEqualsPredicate = criteriaBuilder.notEqual(root.get(name), value.trim());
          predicates.add(notEqualsPredicate);
        }
      }
    };
  }

  /**
   * Creates an AttributeMatcher for GREATER THAN conditions.
   *
   * @return an AttributeMatcher for GREATER THAN conditions
   */
  public static AttributeMatcher greaterThan() {
    return new AttributeMatcher() {
      @Override
      public <S> void match(String name, String value, Root<S> root, CriteriaQuery<?> query,
                            CriteriaBuilder criteriaBuilder, EscapeCharacter character,
                            List<Predicate> predicates) throws Exception {
        if (value != null && !value.trim().isEmpty()) {
          Predicate greaterThanPredicate = criteriaBuilder.greaterThan(root.get(name), value);
          predicates.add(greaterThanPredicate);
        }
      }
    };
  }

  /**
   * Creates an AttributeMatcher for LESS THAN conditions.
   *
   * @return an AttributeMatcher for LESS THAN conditions
   */
  public static AttributeMatcher lessThan() {
    return new AttributeMatcher() {
      @Override
      public <S> void match(String name, String value, Root<S> root, CriteriaQuery<?> query,
                            CriteriaBuilder criteriaBuilder, EscapeCharacter character,
                            List<Predicate> predicates) throws Exception {
        if (value != null && !value.trim().isEmpty()) {
          Predicate lessThanPredicate = criteriaBuilder.lessThan(root.get(name), value);
          predicates.add(lessThanPredicate);
        }
      }
    };
  }

  /**
   * Creates an AttributeMatcher for IS NULL conditions.
   *
   * @return an AttributeMatcher for IS NULL conditions
   */
  public static AttributeMatcher isNull() {
    return new AttributeMatcher() {
      @Override
      public <S> void match(String name, String value, Root<S> root, CriteriaQuery<?> query,
                            CriteriaBuilder criteriaBuilder, EscapeCharacter character,
                            List<Predicate> predicates) throws Exception {
        Predicate isNullPredicate = criteriaBuilder.isNull(root.get(name));
        predicates.add(isNullPredicate);
      }
    };
  }

  /**
   * Creates an AttributeMatcher for IS NOT NULL conditions.
   *
   * @return an AttributeMatcher for IS NOT NULL conditions
   */
  public static AttributeMatcher isNotNull() {
    return new AttributeMatcher() {
      @Override
      public <S> void match(String name, String value, Root<S> root, CriteriaQuery<?> query,
                            CriteriaBuilder criteriaBuilder, EscapeCharacter character,
                            List<Predicate> predicates) throws Exception {
        Predicate isNotNullPredicate = criteriaBuilder.isNotNull(root.get(name));
        predicates.add(isNotNullPredicate);
      }
    };
  }

  /**
   * Creates an AttributeMatcher for GREATER THAN OR EQUAL TO conditions.
   *
   * @return an AttributeMatcher for GREATER THAN OR EQUAL TO conditions
   */
  public static AttributeMatcher greaterThanOrEqualTo() {
    return new AttributeMatcher() {
      @Override
      public <S> void match(String name, String value, Root<S> root, CriteriaQuery<?> query,
                            CriteriaBuilder criteriaBuilder, EscapeCharacter character,
                            List<Predicate> predicates) throws Exception {
        Predicate gtePredicate = criteriaBuilder.greaterThanOrEqualTo(root.get(name), value);
        predicates.add(gtePredicate);
      }
    };
  }

  /**
   * Creates an AttributeMatcher for LESS THAN OR EQUAL TO conditions.
   *
   * @return an AttributeMatcher for LESS THAN OR EQUAL TO conditions
   */
  public static AttributeMatcher lessThanOrEqualTo() {
    return new AttributeMatcher() {
      @Override
      public <S> void match(String name, String value, Root<S> root, CriteriaQuery<?> query,
                            CriteriaBuilder criteriaBuilder, EscapeCharacter character,
                            List<Predicate> predicates) throws Exception {
        Predicate ltePredicate = criteriaBuilder.lessThanOrEqualTo(root.get(name), value);
        predicates.add(ltePredicate);
      }
    };
  }

}
