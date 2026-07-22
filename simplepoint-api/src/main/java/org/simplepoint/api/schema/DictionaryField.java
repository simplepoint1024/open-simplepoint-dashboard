package org.simplepoint.api.schema;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an entity field as being backed by a platform dictionary.
 *
 * <p>The JSON Schema generator exposes the dictionary code to clients so forms,
 * table cells, and table filters can share the same option source.</p>
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DictionaryField {

  /** Dictionary code used to load enabled options. */
  String value();
}
