package org.simplepoint.api.schema;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a string field as an object-storage upload value.
 *
 * <p>The JSON Schema generator converts this annotation into semantic
 * {@code x-upload} metadata and the matching {@code x-ui} widget/options.
 * The persisted value remains an OSS URL or path instead of an inline data URL.</p>
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface UploadField {

  /** Upload control type. */
  Type type() default Type.FILE;

  /** Optional object-storage directory. */
  String directory() default "";

  /** Optional source service name recorded with the uploaded object. */
  String sourceServiceName() default "";

  /** Maximum accepted file size in MiB. */
  int maxSizeMb() default 5;

  /** Optional browser accept expression, for example {@code application/pdf,.docx}. */
  String accept() default "";

  /** Optional image preview shape. Ignored by non-image upload controls. */
  String shape() default "";

  /** Supported object-storage upload controls. */
  enum Type {
    FILE,
    IMAGE
  }
}
