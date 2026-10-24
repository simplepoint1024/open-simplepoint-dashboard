package org.simplepoint.api.schema;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares a localized field value, independently of the translation of its schema title. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.METHOD})
public @interface LocalizedField {
  int maxLength() default 255;
  int maxLanguages() default 32;
  boolean multiline() default false;
  boolean tenantOverride() default true;
}
