package org.simplepoint.platform.composition;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a module capability annotation with the shared schemas it requires in its owning store.
 *
 * <p>The declaration is independent of persistence frameworks and schema providers. Planning must
 * reject missing providers instead of silently producing a partial installation.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface ModuleSharedSchemas {
  /** Stable infrastructure owner identities, independent of deployment hosts. */
  String[] value();
}
