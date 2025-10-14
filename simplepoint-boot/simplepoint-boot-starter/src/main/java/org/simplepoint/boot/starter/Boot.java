/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.boot.starter;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.annotation.AliasFor;

/**
 * Simple boot.
 */
@Inherited
@Documented
@SpringBootApplication
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Boot {
  /**
   * For details, please refer to.
   * {@link  org.springframework.boot.autoconfigure.SpringBootApplication}
   *
   * @return {@link  org.springframework.boot.autoconfigure.SpringBootApplication}
   */
  @AliasFor(annotation = SpringBootApplication.class, attribute = "exclude")
  Class<?>[] exclude() default {};

  /**
   * For details, please refer to.
   * {@link  org.springframework.boot.autoconfigure.SpringBootApplication}
   *
   * @return {@link  org.springframework.boot.autoconfigure.SpringBootApplication}
   */
  @AliasFor(annotation = SpringBootApplication.class, attribute = "excludeName")
  String[] excludeName() default {};

  /**
   * For details, please refer to.
   * {@link  org.springframework.boot.autoconfigure.SpringBootApplication}
   *
   * @return {@link  org.springframework.boot.autoconfigure.SpringBootApplication}
   */
  @AliasFor(annotation = SpringBootApplication.class, attribute = "scanBasePackages")
  String[] scanBasePackages() default {"org.simplepoint.**"};

  /**
   * For details, please refer to.
   * {@link  org.springframework.boot.autoconfigure.SpringBootApplication}
   *
   * @return {@link  org.springframework.boot.autoconfigure.SpringBootApplication}
   */
  @AliasFor(annotation = SpringBootApplication.class, attribute = "scanBasePackageClasses")
  Class<?>[] scanBasePackageClasses() default {};

  /**
   * For details, please refer to.
   * {@link  org.springframework.boot.autoconfigure.SpringBootApplication}
   *
   * @return {@link  org.springframework.boot.autoconfigure.SpringBootApplication}
   */
  @AliasFor(annotation = SpringBootApplication.class, attribute = "nameGenerator")
  Class<? extends BeanNameGenerator> nameGenerator() default BeanNameGenerator.class;

  /**
   * For details, please refer to.
   * {@link  org.springframework.boot.autoconfigure.SpringBootApplication}
   *
   * @return {@link  org.springframework.boot.autoconfigure.SpringBootApplication}
   */
  @AliasFor(annotation = SpringBootApplication.class, attribute = "proxyBeanMethods")
  boolean proxyBeanMethods() default true;
}
