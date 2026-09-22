package org.simplepoint.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Makes a projected field inherit the strictest policy of its source fields. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface PermissionFields {
  /** Entity owning the source fields; dependencies must not form cycles. */
  Class<?> resource() default Void.class;

  /** Canonical entity name when an API module cannot depend on the owning implementation API. */
  String resourceName() default "";

  /** Source fields used by the projection, including every COALESCE input. */
  String[] value();
}
