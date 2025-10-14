package org.simplepoint.core.annotation;

/**
 * Annotation to specify the type of a generic parameter.
 *
 * <p>This annotation is used to provide metadata about the type of a generic parameter in a class or method.
 */
public @interface GenericsType {
  /**
   * The type of the generic parameter.
   *
   * @return The class representing the type of the generic parameter.
   */
  Class<?> value();

  /**
   * The name of the generic parameter.
   *
   * @return The name of the generic parameter.
   */
  String name();
}
