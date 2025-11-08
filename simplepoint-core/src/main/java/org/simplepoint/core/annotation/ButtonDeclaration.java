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
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@JsonInclude(JsonInclude.Include.NON_NULL)
public @interface ButtonDeclaration {
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
   * The path associated with the button.
   * This can be used to define the URL or endpoint for the button action.
   */
  String path() default "[default]";

  /**
   * The maximum size of the arguments the button accepts.
   * This can be used to limit the number of arguments for the button.
   */
  int argumentMaxSize() default -1;

  /**
   * The minimum size of the arguments the button accepts.
   * This can be used to enforce a minimum number of arguments for the button.
   */
  int argumentMinSize() default -1;

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
   * Indicates whether the button represents a dangerous action.
   * This can be used to apply special styling or behavior for dangerous actions.
   */
  boolean danger() default false;

  /**
   * The icon associated with the button.
   * This can be a string representing the icon name or a URL to the icon image.
   */
  String icon() default "";

  /**
   * The authority associated with the button.
   * This can be used to define the permissions or scope tied to the button.
   */
  String authority();

  /**
   * The sort order of the button.
   * This can be used to determine its position in a list or menu.
   */
  int sort() default 99;
}
