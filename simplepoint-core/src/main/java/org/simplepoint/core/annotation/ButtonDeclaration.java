package org.simplepoint.core.annotation;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.simplepoint.core.enums.ButtonType;
import org.simplepoint.core.enums.ButtonVariantTypes;

/**
 * Annotation to declare a button in the SimplePoint framework.
 * This annotation can be used to mark methods or classes that represent a button declaration.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@JsonInclude(JsonInclude.Include.NON_NULL)
public @interface ButtonDeclaration {
  /**
   * The access value associated with the button.
   * This can be used to control access permissions for the button.
   */
  String text() default "";

  /**
   * The access value associated with the button.
   * This can be used to control access permissions for the button.
   */
  String title() default "";

  /**
   * The unique identifier for the button.
   * This can be used to reference the button in the system.
   */
  String key() default "";

  /**
   * The type of the button.
   * This can be used to define its behavior and appearance.
   */
  ButtonType type() default ButtonType.PRIMARY;

  /**
   * The color of the button.
   * This can be used to define its appearance.
   */
  String color() default "blue";

  /**
   * The variant of the button.
   * This can be used to define its appearance and style.
   */
  ButtonVariantTypes variant() default ButtonVariantTypes.OUTLINED;

  /**
   * The icon associated with the button.
   * This can be a string representing the icon name or a URL to the icon image.
   */
  String icon() default "";

  /**
   * The sort order of the button.
   * This can be used to determine its position in a list or menu.
   */
  int sort() default 99;
}
