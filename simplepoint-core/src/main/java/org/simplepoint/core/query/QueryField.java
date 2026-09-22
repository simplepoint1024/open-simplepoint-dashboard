package org.simplepoint.core.query;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** One explicitly exposed scalar query field. */
@Retention(RetentionPolicy.RUNTIME)
public @interface QueryField {
  /** Entity scalar property name; arbitrary paths are not accepted. */
  String value();

  /** Whether this field can participate in a public order clause. */
  boolean sortable() default true;

  /** Empty means the standard operator subset for this Java scalar type. */
  String[] operators() default {};
}
