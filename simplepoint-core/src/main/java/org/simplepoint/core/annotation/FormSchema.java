/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.core.annotation;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Mark to enable access control for table metadata.
 * This annotation is used to provide metadata for a table,
 * including its name, value, enabled status,
 * generics types, and description.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@JsonInclude(JsonInclude.Include.NON_NULL)
public @interface FormSchema {
  /**
   * Name of the access control.
   *
   * @return name of the access control.
   */
  String name() default "";

  /**
   * key.
   *
   * @return key.
   */
  String value() default "";

  /**
   * Whether to enable it.
   *
   * @return Whether to enable it.
   */
  boolean enabled() default true;

  /**
   * Whether to enable data access control permissions.
   *
   * @return Whether to enable data access control permissions.
   */
  boolean enabledDataScope() default false;

  /**
   * Generics types.
   *
   * @return Generics types.
   */
  GenericsType[] genericsTypes() default {};

  /**
   * Description of the access control.
   *
   * @return description of the access control.
   */
  String description() default "";
}
