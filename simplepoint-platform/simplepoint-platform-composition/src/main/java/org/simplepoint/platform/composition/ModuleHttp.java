package org.simplepoint.platform.composition;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Stable public HTTP namespace for an explicitly selected adapter module. */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ModuleHttp {
  /**
   * Returns an absolute, literal namespace, independent of host and servlet context paths. The root
   * namespace preserves protocol URLs and requires an explicit bounded routes reader.
   */
  String namespace();

  /** Business adapters require an owner-bound authorization context; protocol chains opt out. */
  boolean scopedAuthorization() default true;

  /**
   * Optional metadata reader deriving relative route roots from the owning adapter's declarations.
   * The default reserves the whole namespace. Readers must not construct business components.
   */
  Class<? extends ModuleHttpRoutes> routes() default ModuleHttpRoutes.class;
}
