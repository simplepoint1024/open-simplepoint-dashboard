package org.simplepoint.core.query;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Explicit public query contract. Unlisted fields never become structured-query capabilities. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface TableQuery {
  /** Read projections can reuse their entity's contract without duplicating field policies. */
  Class<?> model() default Void.class;

  /** Allowlisted scalar fields and their permitted operations. */
  QueryField[] value() default {};

  /** Correlated relationships are opt-in and never inferred from JPA mappings. */
  QueryRelation[] relations() default {};

  /** Endpoint-specific maximum page size, advertised to clients and enforced by repositories. */
  int maxPageSize() default 200;
}
