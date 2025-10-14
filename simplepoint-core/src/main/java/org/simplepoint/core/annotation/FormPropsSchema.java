/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Mark to enable access control for table columns.
 * This annotation is used to provide metadata for each column in a table,
 * including its name, value, sort order, format, visibility, and other properties.
 */
@Deprecated
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface FormPropsSchema {

  /**
   * Access name.
   *
   * @return Access name.
   */
  String name() default "";

  /**
   * Access key.
   *
   * @return Access key.
   */
  String value() default "";

  /**
   * Access key.
   *
   * @return Access key.
   */
  String type() default "string";

  /**
   * Access name.
   *
   * @return Access name.
   */
  int sort() default 9999;

  /**
   * Access format.
   *
   * @return Access format.
   */
  String format() default "";

  /**
   * Whether the access column is hidden.
   *
   * @return Whether the access column is hidden.
   */
  boolean hidden() default false;

  /**
   * Java type of the access column.
   *
   * @return Java type of the access column.
   */
  boolean nullable() default true;

  /**
   * Whether the access column is read-only.
   *
   * @return Whether the access column is read-only.
   */
  boolean readonly() default false;

  /**
   * Maximum length of the access column.
   *
   * @return Maximum length of the access column.
   */
  int maxLength() default 100;

  /**
   * Minimum length of the access column.
   *
   * @return Minimum length of the access column.
   */
  int minLength() default 0;

  /**
   * Access description.
   *
   * @return Access description.
   */
  String description() default "";
}
