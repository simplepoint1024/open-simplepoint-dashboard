package org.simplepoint.data.jpa;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.List;
import org.springframework.data.jpa.repository.query.EscapeCharacter;

/**
 * An interface for matching attributes in a query context.
 * This interface provides a method to build predicates based on attribute names and values.
 * It is used to dynamically construct query conditions for criteria queries.
 */
public interface AttributeMatcher {

  /**
   * Matches an attribute and adds the corresponding predicates to the provided list.
   * This method is used to dynamically generate query conditions for attributes based on
   * the given parameters, such as attribute name, value, and query context.
   *
   * @param <S>             the entity type
   * @param name            the name of the attribute
   * @param value           the value of the attribute
   * @param root            the root type in the criteria query
   * @param query           the criteria query being constructed
   * @param criteriaBuilder the builder for criteria query expressions
   * @param character       the escape character used in the query
   * @param predicates      the list of predicates to which conditions are added
   * @throws Exception if an error occurs during predicate construction
   */
  <S> void match(
      String name,
      String value,
      Root<S> root,
      CriteriaQuery<?> query,
      CriteriaBuilder criteriaBuilder,
      EscapeCharacter character,
      List<Predicate> predicates
  ) throws Exception;

}
