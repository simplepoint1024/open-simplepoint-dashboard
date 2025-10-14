package org.simplepoint.core.annotation;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to declare a button in the SimplePoint framework.
 * This annotation can be used to mark methods or classes that represent a button declaration.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@JsonInclude(JsonInclude.Include.NON_NULL)
public @interface ButtonDeclarations {
  /**
   * An array of ButtonDeclaration annotations.
   * This can be used to declare multiple buttons in a single annotation.
   *
   * @return An array of ButtonDeclaration annotations.
   */
  ButtonDeclaration[] value() default {};
}
